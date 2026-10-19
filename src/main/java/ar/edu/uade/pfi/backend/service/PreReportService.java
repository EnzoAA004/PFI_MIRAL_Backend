package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.ReportDraft;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.PreReportViewDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import ar.edu.uade.pfi.backend.service.exception.RunReviewException;
import ar.edu.uade.pfi.backend.service.exception.StudyNotFoundException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Preinforme visual: contenido generado por código desde la corrida exacta + borrador editable del profesional.
 *
 * <ul>
 *   <li>Abrir o consultar NO escribe nada: no cambia la revisión ni crea una aprobación.</li>
 *   <li>Guardar un borrador escribe solo {@code domain_report_drafts} (más un evento de auditoría del propio borrador). No toca
 *       predicciones, correcciones, anotaciones ni el estado de revisión del estudio.</li>
 *   <li>Lo generado y lo editado se guardan por separado. Si las fuentes cambian, el borrador queda «basado en datos anteriores»
 *       (huella distinta) y NO se reescribe: la actualización es explícita ({@link #updateSources}) y conserva las ediciones.</li>
 * </ul>
 */
@Service
public class PreReportService {
    static final int MAX_EDITS_BYTES = 400_000;
    static final int MAX_TEXT_LENGTH = 6_000;
    private static final Set<String> EDIT_KEYS = Set.of("include", "text", "observations", "levelNotes", "renderParams");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final StudyRepository repository;
    private final PreReportBuilder builder;
    private final ObjectMapper objectMapper;
    private final ObjectMapper canonical;
    private final AuditService auditService;
    private final Clock clock;

    @Autowired
    public PreReportService(StudyRepository repository, PreReportBuilder builder, ObjectMapper objectMapper, @Nullable AuditService auditService) {
        this(repository, builder, objectMapper, auditService, Clock.systemUTC());
    }

    PreReportService(StudyRepository repository, PreReportBuilder builder, ObjectMapper objectMapper, AuditService auditService, Clock clock) {
        this.repository = repository;
        this.builder = builder;
        this.objectMapper = objectMapper;
        this.canonical = objectMapper.copy()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.auditService = auditService;
        this.clock = clock;
    }

    /** Vista de solo lectura: contenido actual, borrador guardado (si hay) y si quedó basado en datos anteriores. */
    public PreReportViewDto view(String caseId, String runId) {
        Context context = context(caseId, runId);
        ReportDraft draft = repository.findReportDraft(context.study.id(), context.run.multiplanarRunId()).orElse(null);
        return toView(context, draft, List.of());
    }

    public PreReportViewDto save(String caseId, String runId, Integer expectedVersion, Map<String, Object> edits, String actor, String traceId) {
        Context context = context(caseId, runId);
        Map<String, Object> clean = validateEdits(edits);
        ReportDraft existing = repository.findReportDraft(context.study.id(), context.run.multiplanarRunId()).orElse(null);
        if (existing == null && expectedVersion != null) throw conflict();
        if (existing != null && (expectedVersion == null || expectedVersion != existing.version())) throw conflict();
        Instant now = clock.instant();
        // El contenido generado se fija al CREAR el borrador y no cambia al guardar ediciones: actualizar las fuentes es explícito.
        String generatedJson = existing == null ? write(context.generated) : existing.generatedJson();
        String fingerprint = existing == null ? context.fingerprint : existing.sourceFingerprint();
        ReportDraft toSave = new ReportDraft(
            existing == null ? UUID.randomUUID().toString() : existing.id(), context.study.id(), context.run.multiplanarRunId(),
            existing == null ? 1 : existing.version() + 1, fingerprint, generatedJson, write(clean),
            existing == null ? now : existing.createdAt(), now, actor == null ? "" : actor);
        ReportDraft saved = save(toSave, existing == null ? null : existing.version());
        audit("prereport.saved", context.study.id(), traceId, actor, Map.of("version", saved.version(), "stale", !saved.sourceFingerprint().equals(context.fingerprint)));
        return toView(context, saved, List.of());
    }

    /** Actualización EXPLÍCITA: reemplaza lo generado por lo actual y conserva todas las ediciones. Informa las ediciones huérfanas. */
    public PreReportViewDto updateSources(String caseId, String runId, Integer expectedVersion, String actor, String traceId) {
        Context context = context(caseId, runId);
        ReportDraft existing = repository.findReportDraft(context.study.id(), context.run.multiplanarRunId())
            .orElseThrow(() -> new RunReviewException(HttpStatus.NOT_FOUND, "REPORT_DRAFT_NOT_FOUND", "Todavía no hay un borrador guardado para esta corrida."));
        if (expectedVersion == null || expectedVersion != existing.version()) throw conflict();
        ReportDraft toSave = new ReportDraft(
            existing.id(), existing.studyId(), existing.runId(), existing.version() + 1, context.fingerprint, write(context.generated),
            existing.editsJson(), existing.createdAt(), clock.instant(), actor == null ? "" : actor);
        ReportDraft saved = save(toSave, existing.version());
        List<String> orphaned = orphanedEditKeys(read(saved.editsJson()), context.generated);
        audit("prereport.sources_updated", context.study.id(), traceId, actor, Map.of("version", saved.version(), "orphanedEdits", orphaned.size()));
        return toView(context, saved, orphaned);
    }

    /* ------------------------------------------------------------------ internos */

    private record Context(Study study, StudyRun run, Map<String, Object> generated, String fingerprint) {
    }

    private Context context(String caseId, String runId) {
        Study study = repository.findStudyByCaseId(caseId).orElseThrow(() -> new StudyNotFoundException(caseId));
        if (runId == null || runId.isBlank()) {
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "REPORT_RUN_REQUIRED", "Hace falta indicar la corrida del análisis. No se elige otra en silencio.");
        }
        StudyRun run = repository.findRunByMultiplanarRunId(runId.trim()).filter(candidate -> candidate.studyId().equals(study.id()))
            .orElseThrow(() -> new RunReviewException(HttpStatus.NOT_FOUND, "REPORT_RUN_NOT_FOUND", "No se pudo recuperar el análisis indicado. No se reemplaza por otro análisis."));
        Map<String, Object> generated = builder.build(study, run);
        return new Context(study, run, generated, fingerprint(generated));
    }

    private PreReportViewDto toView(Context context, ReportDraft draft, List<String> orphaned) {
        boolean usable = !((List<?>) context.generated.get("measurements")).isEmpty() || !((List<?>) context.generated.get("findings")).isEmpty();
        PreReportViewDto.DraftDto draftDto = null;
        boolean stale = false;
        List<Map<String, Object>> changes = List.of();
        if (draft != null) {
            Map<String, Object> storedGenerated = read(draft.generatedJson());
            stale = !draft.sourceFingerprint().equals(context.fingerprint);
            if (stale) changes = diff(storedGenerated, context.generated);
            draftDto = new PreReportViewDto.DraftDto(
                draft.id(), draft.version(), draft.sourceFingerprint(), storedGenerated, read(draft.editsJson()),
                draft.createdAt(), draft.updatedAt(), draft.updatedBy());
        }
        return new PreReportViewDto(context.generated, context.fingerprint, clock.instant(), usable, draftDto, stale, changes, orphaned);
    }

    /** Solo claves y tamaños conocidos: el borrador no es un lugar para guardar cualquier cosa. */
    @SuppressWarnings("unchecked")
    Map<String, Object> validateEdits(Map<String, Object> edits) {
        if (edits == null) return new LinkedHashMap<>();
        for (String key : edits.keySet()) {
            if (!EDIT_KEYS.contains(key)) throw bad("REPORT_EDITS_INVALID", "Clave desconocida en las ediciones: " + key);
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        Object include = edits.get("include");
        if (include != null) {
            if (!(include instanceof Map<?, ?> m)) throw bad("REPORT_EDITS_INVALID", "«include» debe ser un mapa clave → verdadero/falso.");
            Map<String, Boolean> flags = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String key) || !(e.getValue() instanceof Boolean flag)) throw bad("REPORT_EDITS_INVALID", "«include» solo admite clave → verdadero/falso.");
                flags.put(key, flag);
            }
            clean.put("include", flags);
        }
        for (String name : List.of("text", "levelNotes")) {
            Object value = edits.get(name);
            if (value == null) continue;
            if (!(value instanceof Map<?, ?> m)) throw bad("REPORT_EDITS_INVALID", "«" + name + "» debe ser un mapa de textos.");
            Map<String, String> texts = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String key) || !(e.getValue() instanceof String text)) throw bad("REPORT_EDITS_INVALID", "«" + name + "» solo admite textos.");
                if (text.length() > MAX_TEXT_LENGTH) throw bad("REPORT_EDITS_TOO_LONG", "Un texto de «" + name + "» supera el largo máximo.");
                texts.put(key, text);
            }
            clean.put(name, texts);
        }
        Object observations = edits.get("observations");
        if (observations != null) {
            if (!(observations instanceof String text)) throw bad("REPORT_EDITS_INVALID", "«observations» debe ser un texto.");
            if (text.length() > MAX_TEXT_LENGTH * 2) throw bad("REPORT_EDITS_TOO_LONG", "Las observaciones superan el largo máximo.");
            clean.put("observations", text);
        }
        Object render = edits.get("renderParams");
        if (render != null) {
            if (!(render instanceof Map<?, ?>)) throw bad("REPORT_EDITS_INVALID", "«renderParams» debe ser un mapa.");
            clean.put("renderParams", render);
        }
        if (write(clean).getBytes(StandardCharsets.UTF_8).length > MAX_EDITS_BYTES) throw bad("REPORT_EDITS_TOO_LONG", "Las ediciones superan el tamaño máximo.");
        return clean;
    }

    /** Claves editadas que ya no existen en el contenido generado actual: se conservan y se informan. */
    @SuppressWarnings("unchecked")
    List<String> orphanedEditKeys(Map<String, Object> edits, Map<String, Object> generated) {
        Set<String> known = itemKeys(generated);
        Set<String> orphaned = new LinkedHashSet<>();
        for (String section : List.of("include", "text")) {
            Object value = edits.get(section);
            if (value instanceof Map<?, ?> m) for (Object key : m.keySet()) if (key instanceof String k && isItemKey(k) && !known.contains(k)) orphaned.add(k);
        }
        return new ArrayList<>(orphaned);
    }

    private static boolean isItemKey(String key) {
        return key.startsWith("m:") || key.startsWith("f:") || key.startsWith("a:");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> itemKeys(Map<String, Object> generated) {
        Set<String> keys = new LinkedHashSet<>();
        for (String section : List.of("measurements", "findings", "annotations")) {
            if (generated.get(section) instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> m && m.get("key") instanceof String k) keys.add(k);
        }
        return keys;
    }

    /** Qué cambió entre lo generado al crear/actualizar el borrador y lo que sale hoy de las fuentes. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> diff(Map<String, Object> before, Map<String, Object> after) {
        List<Map<String, Object>> changes = new ArrayList<>();
        Map<String, Object> studyBefore = (Map<String, Object>) before.getOrDefault("study", Map.of());
        Map<String, Object> studyAfter = (Map<String, Object>) after.getOrDefault("study", Map.of());
        compareScalars("Contexto del estudio", "study", flatten(studyBefore), flatten(studyAfter), changes);
        for (String section : List.of("measurements", "findings", "annotations")) {
            Map<String, Map<String, Object>> a = index(before.get(section));
            Map<String, Map<String, Object>> b = index(after.get(section));
            for (Map.Entry<String, Map<String, Object>> entry : b.entrySet()) {
                Map<String, Object> old = a.get(entry.getKey());
                if (old == null) changes.add(change("added", section, entry.getKey(), label(entry.getValue()), null, null));
                else if (!canonicalEquals(old, entry.getValue())) {
                    changes.add(change("changed", section, entry.getKey(), label(entry.getValue()), summary(old), summary(entry.getValue())));
                }
            }
            for (Map.Entry<String, Map<String, Object>> entry : a.entrySet()) {
                if (!b.containsKey(entry.getKey())) changes.add(change("removed", section, entry.getKey(), label(entry.getValue()), null, null));
            }
        }
        compareDocument(before, after, changes);
        return changes;
    }

    /**
     * Lo que no son datos del estudio pero entra en la huella: el texto con que el sistema genera el documento (limitaciones,
     * apartados omitidos, estado de los hallazgos, corrida) y, si no hay otra explicación, su estructura derivada. Sin esto una
     * huella distinta aparecía como «cambiaron las correcciones o el contexto» con una lista de cambios vacía.
     * Estos cambios van en la sección «document»: no son resultados clínicos ni se atribuyen al estudio.
     */
    private void compareDocument(Map<String, Object> before, Map<String, Object> after, List<Map<String, Object>> changes) {
        boolean dataChanged = !changes.isEmpty();
        Map<String, String> direct = new LinkedHashMap<>();
        direct.put("schema", "Versión del esquema del documento");
        direct.put("run", "Datos de la corrida en el documento");
        direct.put("findingsStatus", "Estado de la comparación de hallazgos");
        direct.put("findingsStatusReason", "Motivo del estado de los hallazgos");
        direct.put("omitted", "Apartados omitidos del cuerpo");
        for (Map.Entry<String, String> entry : direct.entrySet()) {
            if (!canonicalEquals(before.get(entry.getKey()), after.get(entry.getKey()))) {
                changes.add(change("changed", "document", "doc:" + entry.getKey(), entry.getValue(), null, null));
            }
        }
        Set<String> oldLimits = new LinkedHashSet<>(stringList(before.get("limitations")));
        Set<String> newLimits = new LinkedHashSet<>(stringList(after.get("limitations")));
        for (String text : newLimits) if (!oldLimits.contains(text)) changes.add(change("added", "document", "doc:limitation:" + Integer.toHexString(text.hashCode()), "Limitación (texto del documento): " + shorten(text), null, null));
        for (String text : oldLimits) if (!newLimits.contains(text)) changes.add(change("removed", "document", "doc:limitation:" + Integer.toHexString(text.hashCode()), "Limitación (texto anterior): " + shorten(text), null, null));
        if (!dataChanged && changes.isEmpty()) {
            Map<String, String> derived = new LinkedHashMap<>();
            derived.put("series", "Series analizadas");
            derived.put("coverage", "Cobertura del documento");
            derived.put("levels", "Niveles y fichas");
            for (Map.Entry<String, String> entry : derived.entrySet()) {
                if (!canonicalEquals(before.get(entry.getKey()), after.get(entry.getKey()))) {
                    changes.add(change("changed", "document", "doc:" + entry.getKey(), entry.getValue(), null, null));
                }
            }
        }
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) for (Object item : list) if (item instanceof String s) out.add(s);
        return out;
    }

    private static String shorten(String text) {
        return text.length() <= 120 ? text : text.substring(0, 117) + "…";
    }

    private void compareScalars(String title, String section, Map<String, Object> a, Map<String, Object> b, List<Map<String, Object>> changes) {
        Set<String> keys = new LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String key : keys) {
            if (!Objects.equals(a.get(key), b.get(key))) changes.add(change("changed", section, "study:" + key, title + " · " + key, String.valueOf(a.get(key)), String.valueOf(b.get(key))));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flatten(Map<String, Object> source) {
        Map<String, Object> flat = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> nested) {
                for (Map.Entry<String, Object> n : flatten((Map<String, Object>) nested).entrySet()) flat.put(e.getKey() + "." + n.getKey(), n.getValue());
            } else {
                flat.put(e.getKey(), e.getValue());
            }
        }
        return flat;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> index(Object list) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (list instanceof List<?> items) for (Object item : items) if (item instanceof Map<?, ?> m && m.get("key") instanceof String k) result.put(k, (Map<String, Object>) m);
        return result;
    }

    private static String label(Map<String, Object> item) {
        Object text = item.get("text");
        return text instanceof String s && !s.isBlank() ? s : String.valueOf(item.get("key"));
    }

    private static String summary(Map<String, Object> item) {
        Object text = item.get("text");
        return text instanceof String s ? s : String.valueOf(item.get("key"));
    }

    private static Map<String, Object> change(String kind, String section, String key, String label, String before, String after) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("kind", kind);
        change.put("section", section);
        change.put("key", key);
        change.put("label", label);
        change.put("before", before);
        change.put("after", after);
        return change;
    }

    private boolean canonicalEquals(Object a, Object b) {
        return write(a).equals(write(b));
    }

    String fingerprint(Map<String, Object> generated) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.writeValueAsString(generated).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("src-");
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo calcular la huella del preinforme", ex);
        }
    }

    private ReportDraft save(ReportDraft draft, Integer expectedVersion) {
        try {
            return repository.saveReportDraft(draft, expectedVersion);
        } catch (IllegalStateException ex) {
            if ("REPORT_DRAFT_VERSION_CONFLICT".equals(ex.getMessage())) throw conflict();
            throw ex;
        }
    }

    private String write(Object value) {
        try {
            return canonical.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo serializar el preinforme", ex);
        }
    }

    private Map<String, Object> read(String json) {
        try {
            return objectMapper.readValue(json, MAP);
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo leer el borrador del preinforme", ex);
        }
    }

    private static RunReviewException conflict() {
        return new RunReviewException(HttpStatus.CONFLICT, "REPORT_DRAFT_VERSION_CONFLICT",
            "El borrador cambió desde que lo abriste (otra pestaña o persona). Recargalo para no pisar ese trabajo.");
    }

    private static RunReviewException bad(String code, String message) {
        return new RunReviewException(HttpStatus.BAD_REQUEST, code, message);
    }

    private void audit(String action, String studyId, String traceId, String actor, Map<String, Object> metadata) {
        if (auditService == null) return;
        try {
            auditService.record(actor == null || actor.isBlank() ? "system" : actor, action, studyId, traceId == null || traceId.isBlank() ? "unavailable" : traceId, new LinkedHashMap<>(metadata));
        } catch (RuntimeException ignored) {
            // El borrador no debe fallar porque no se pueda persistir la auditoría.
        }
    }
}
