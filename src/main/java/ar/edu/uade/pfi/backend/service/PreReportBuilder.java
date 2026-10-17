package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.InputSequence;
import ar.edu.uade.pfi.backend.domain.ReviewerAnnotation;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.InitialAnalysisComparisonDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import ar.edu.uade.pfi.backend.service.InitialAnalysisMeasurementReader.Measurement;
import ar.edu.uade.pfi.backend.service.InitialAnalysisMeasurementReader.Reading;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Arma, por código y a partir de la corrida EXACTA del visor, el contenido generado del preinforme (nunca con un modelo de
 * lenguaje). Todo elemento lleva una clave estable ({@code m:<id medición>}, {@code f:<id hallazgo>}, {@code a:<id
 * anotación>}) que es su referencia de origen: las ediciones del profesional se guardan contra esa clave, aparte.
 *
 * <p>Lo que NO hace, a propósito: no decide "dentro/fuera de rango" (los umbrales históricos de la interfaz no tienen
 * fuente ni aplicabilidad documentadas), no deriva revisión individual de la aprobación global, no usa el circuito de
 * demostración y no inventa ubicaciones: un trazo o una región solo viajan si la corrida los persistió y son válidos.
 */
@Component
public class PreReportBuilder {
    public static final String SCHEMA = "pfi.prereport.v1";

    static final List<String> LUMBAR_LEVELS = List.of("L1-L2", "L2-L3", "L3-L4", "L4-L5", "L5-S1");
    private static final List<String> LEVEL_ORDER = List.of("T9-T10", "T10-T11", "T11-T12", "T12-L1", "L1-L2", "L2-L3", "L3-L4", "L4-L5", "L5-S1");
    /** Mediciones propias del nivel que el cuerpo del documento incluye por defecto; el resto va al anexo. */
    private static final Set<String> BODY_METRICS = Set.of("disc height", "disc width", "disc area", "canal ap");
    private static final Locale ES = Locale.of("es", "AR");

    private final StudyRepository repository;
    private final InitialAnalysisMeasurementReader reader;

    public PreReportBuilder(StudyRepository repository, InitialAnalysisMeasurementReader reader) {
        this.repository = repository;
        this.reader = reader;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> build(Study study, StudyRun run) {
        Reading reading = reader.read(study, run);
        Map<String, Object> planes = map(run.metricsSnapshot().get("planes"));
        Map<String, Object> sagittal = map(planes.get("sagittal"));
        String analysisInputId = text(map(sagittal.get("input")).get("inputId"));
        Integer analysisIndex = integer(map(sagittal.get("coordinateSpace")).get("sourceSliceIndex"));
        String sagittalPlaneRunId = text(sagittal.get("planeRunId"));
        DiscFindingsComparator.Side findingsSide = DiscFindingsComparator.read(run.metricsSnapshot());
        List<InputResource> inputs = repository.findInputsByStudyId(study.id());

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", SCHEMA);
        doc.put("study", studySection(study));
        doc.put("run", runSection(run, reading));

        // ---- mediciones -----------------------------------------------------------------------------------------------
        List<Map<String, Object>> measurements = new ArrayList<>();
        for (Measurement measurement : reading.measurements()) measurements.add(measurementItem(measurement));
        doc.put("measurements", measurements);

        // ---- hallazgos P10.7 ------------------------------------------------------------------------------------------
        List<Map<String, Object>> findings = new ArrayList<>();
        List<Map<String, Object>> omitted = new ArrayList<>();
        String findingsStatus = switch (findingsSide.status()) {
            case READY -> "available";
            case NO_DATA -> "none";
            case INVALID -> "invalid";
        };
        if (findingsSide.status() == DiscFindingsComparator.SideStatus.READY) {
            for (DiscFindingsComparator.Finding finding : findingsSide.findings()) {
                findings.add(findingItem(finding, analysisInputId, analysisIndex, sagittalPlaneRunId));
            }
            for (DiscFindingsComparator.Rejected rejected : findingsSide.rejected()) {
                omitted.add(omission("finding_invalid", rejected.level() == null ? "resultado " + (rejected.index() + 1) : rejected.level(),
                    "El resultado no cumple el contrato y no se muestra: " + rejected.reason() + ". No se interpreta como «ausente»."));
            }
        }
        doc.put("findings", findings);
        doc.put("findingsStatus", findingsStatus);
        if (findingsSide.status() != DiscFindingsComparator.SideStatus.READY) doc.put("findingsStatusReason", findingsSide.reason());

        // ---- anotaciones profesionales ------------------------------------------------------------------------------------
        List<Map<String, Object>> annotations = new ArrayList<>();
        for (ReviewerAnnotation annotation : repository.findAnnotationsByRunId(run.multiplanarRunId())) annotations.add(annotationItem(annotation));
        doc.put("annotations", annotations);

        // ---- niveles --------------------------------------------------------------------------------------------------
        boolean anyFindings = !findings.isEmpty();
        List<String> levelNames = new ArrayList<>(LUMBAR_LEVELS);
        for (Map<String, Object> m : measurements) addLevel(levelNames, (String) m.get("level"));
        for (Map<String, Object> f : findings) addLevel(levelNames, (String) f.get("level"));
        levelNames.sort((a, b) -> Integer.compare(levelRank(a), levelRank(b)) != 0 ? Integer.compare(levelRank(a), levelRank(b)) : a.compareTo(b));
        List<Map<String, Object>> levels = new ArrayList<>();
        for (String level : levelNames) {
            List<String> measurementKeys = new ArrayList<>();
            List<String> findingKeys = new ArrayList<>();
            boolean positiveMain = false;
            for (Map<String, Object> m : measurements) if (level.equals(m.get("level"))) measurementKeys.add((String) m.get("key"));
            for (Map<String, Object> f : findings) {
                if (!level.equals(f.get("level"))) continue;
                findingKeys.add((String) f.get("key"));
                if ("principal".equals(f.get("section")) && Boolean.TRUE.equals(f.get("positive"))) positiveMain = true;
            }
            boolean lumbar = isLumbar(level);
            // Ficha por defecto: niveles con una predicción positiva de las cuatro categorías principales; si la corrida no
            // trae P10.7, los niveles lumbares con mediciones (para no entregar un cuerpo vacío). Con P10.7 sin positivos no
            // se arma ninguna ficha: el resumen compacto ya dice qué se evaluó.
            boolean defaultCard = anyFindings ? positiveMain : lumbar && !measurementKeys.isEmpty();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("level", level);
            entry.put("lumbar", lumbar);
            entry.put("evaluated", !measurementKeys.isEmpty() || !findingKeys.isEmpty());
            entry.put("measurementKeys", measurementKeys);
            entry.put("findingKeys", findingKeys);
            entry.put("defaultCard", defaultCard);
            levels.add(entry);
        }
        doc.put("levels", levels);

        // ---- valores por defecto de inclusión ---------------------------------------------------------------------------
        for (Map<String, Object> m : measurements) {
            boolean body = isLumbar(m.get("level")) && m.get("labelKey") != null && BODY_METRICS.contains(m.get("labelKey")) && m.get("invalidReason") == null;
            m.put("defaultInclude", body);
        }

        // ---- series analizadas ------------------------------------------------------------------------------------------
        doc.put("series", seriesSection(inputs, reading, run, findings, analysisInputId));
        doc.put("coverage", coverage(measurements, findings, findingsSide));

        // ---- omitidos del cuerpo (siempre explícitos) -------------------------------------------------------------------
        long research = findings.stream().filter(f -> "investigacion".equals(f.get("section"))).count();
        if (research > 0) {
            omitted.add(omission("research_findings", research + " resultado(s) de investigación",
                "Son resultados de investigación (no soportados para producto): quedan fuera del cuerpo salvo que el profesional los incluya, y entonces van en un apartado identificado."));
        }
        long noLevel = measurements.stream().filter(m -> m.get("level") == null).count();
        if (noLevel > 0) omitted.add(omission("measurements_without_level", noLevel + " medición(es) sin nivel asignado", "La corrida no pudo asociarlas a un nivel; figuran en el anexo."));
        long invalid = measurements.stream().filter(m -> m.get("invalidReason") != null).count();
        if (invalid > 0) omitted.add(omission("measurements_invalid", invalid + " medición(es) ilegible(s)", "No traen un valor numérico utilizable; figuran en el anexo con su motivo."));
        doc.put("omitted", omitted);
        doc.put("limitations", List.of(
            "Este preinforme resume mediciones automáticas y predicciones de un modelo experimental. Requiere revisión profesional y no constituye un diagnóstico.",
            "Las predicciones P10.7 no tienen validación externa; el grado de Pfirrmann es experimental. La región evaluada indica dónde se evaluó el resultado, no delimita una lesión.",
            "Cada medición sale de un único corte inferido por la corrida. Una corrección numérica del profesional no modifica el trazo original de la IA.",
            "El nivel de cada resultado lo asigna el análisis inicial contando los espacios discales desde el inferior (supone un encuadre que llega a L5-S1); no se verificó contra la anatomía de la imagen.",
            "La revisión individual de cada medición no está registrada: la aprobación global del estudio no la reemplaza ni se deduce de ella."));
        return doc;
    }

    /* ------------------------------------------------------------------ secciones */

    private Map<String, Object> studySection(Study study) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("caseId", study.caseId());
        section.put("subjectRef", study.subjectRef());
        section.put("studyDate", study.studyDate() == null ? null : study.studyDate().toString());
        section.put("modality", study.modality());
        Map<String, Object> clinical = new LinkedHashMap<>();
        clinical.put("ageAtStudy", study.ageAtStudy());
        clinical.put("ageProvenance", study.ageProvenance());
        clinical.put("reason", blankToNull(study.clinicalReason()));
        clinical.put("symptoms", blankToNull(study.symptoms()));
        clinical.put("symptomDuration", blankToNull(study.symptomDuration()));
        clinical.put("relevantHistory", blankToNull(study.relevantHistory()));
        section.put("clinical", clinical);
        return section;
    }

    private Map<String, Object> runSection(StudyRun run, Reading reading) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("runId", run.multiplanarRunId());
        section.put("status", run.status());
        section.put("modelKey", reading.context().modelKey());
        section.put("inferenceMode", reading.context().effectiveInferenceMode());
        section.put("reviewStatus", ReviewStatusMapper.toApiStatus(run.reviewStatus()));
        return section;
    }

    private Map<String, Object> measurementItem(Measurement m) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", "m:" + m.id());
        item.put("id", m.id());
        item.put("plane", m.plane());
        item.put("labelKey", m.labelKey());
        item.put("label", AssistantContextBuilder.metricLabel(m.labelKey()));
        item.put("level", m.level());
        item.put("levelScope", m.levelScope());
        item.put("unit", m.unit());
        item.put("original", m.originalValue());
        item.put("corrected", m.corrected() ? m.correctedValue() : null);
        item.put("effective", m.invalidReason() == null ? m.effectiveValue() : null);
        item.put("origin", m.corrected() ? "profesional" : "ia");
        item.put("experimental", m.experimental());
        item.put("sliceIndex", m.sliceIndex());
        item.put("invalidReason", m.invalidReason());
        item.put("text", measurementText(m));
        InitialAnalysisComparisonDto.Location location = m.location();
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("status", location.status());
        trace.put("reason", location.reason());
        trace.put("kind", location.geometryKind());
        boolean located = InitialAnalysisComparisonDto.Location.LOCATED.equals(location.status());
        boolean professionalTrace = located && !location.correctedGeometry().isEmpty();
        List<InitialAnalysisComparisonDto.Point> shown = professionalTrace ? location.correctedGeometry() : location.geometry();
        trace.put("points", located ? points(shown) : List.of());
        trace.put("origin", located ? (professionalTrace ? "profesional" : "ia") : null);
        Map<String, Object> space = new LinkedHashMap<>();
        space.put("name", location.coordinateSpace());
        space.put("width", location.coordinateWidth());
        space.put("height", location.coordinateHeight());
        trace.put("space", space);
        item.put("trace", trace);
        // La imagen del trazo es la del plano de la corrida (input.png), a la que están alineadas las coordenadas.
        item.put("imagePath", located && location.planeRunId() != null ? "/api/ai/assets/" + location.planeRunId() + "/" + location.plane() + "/input.png" : null);
        return item;
    }

    private static String measurementText(Measurement m) {
        if (m.invalidReason() != null) return AssistantContextBuilder.metricLabel(m.labelKey()) + ": sin valor utilizable.";
        String unit = m.unit().isEmpty() ? "" : " " + displayUnit(m.unit());
        String label = AssistantContextBuilder.metricLabel(m.labelKey());
        label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
        if (m.corrected()) {
            return label + ": " + fmt(m.correctedValue()) + unit + " · corrección profesional (IA: " + fmt(m.originalValue()) + unit + ")";
        }
        return label + ": " + fmt(m.effectiveValue()) + unit + " · IA";
    }

    private Map<String, Object> findingItem(DiscFindingsComparator.Finding f, String analysisInputId, Integer analysisIndex, String sagittalPlaneRunId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", "f:" + f.findingId());
        item.put("findingId", f.findingId());
        item.put("level", f.level());
        item.put("type", f.findingType());
        item.put("typeLabel", AssistantContextBuilder.FINDING_LABELS.getOrDefault(f.findingType(), f.findingType()));
        item.put("label", f.label());
        item.put("className", className(f));
        item.put("confidence", f.confidence());
        item.put("classProbabilities", f.classProbabilities());
        item.put("deploymentStatus", f.deploymentStatus());
        String section = switch (f.deploymentStatus()) {
            case "supported_internal" -> "principal";
            case "experimental" -> "experimental";
            default -> "investigacion";
        };
        item.put("section", section);
        boolean positive = !"pfirrmann_grade".equals(f.findingType()) && !"absent".equals(f.label()) && !"none".equals(f.label());
        item.put("positive", positive);
        item.put("evaluationDataset", f.evaluationDataset());
        item.put("externalValidation", f.externalValidationAvailable());
        item.put("modalities", f.availableRoles());
        item.put("text", findingText(f));
        item.put("defaultInclude", ("principal".equals(section) && positive) || "experimental".equals(section));
        List<Map<String, Object>> sources = new ArrayList<>();
        for (DiscFindingsComparator.Source source : f.sources()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", source.role());
            entry.put("roleLabel", "sagittal_t1".equals(source.role()) ? "Sagital T1" : "Sagital T2");
            entry.put("available", source.available());
            entry.put("regionIssue", source.regionIssue());
            DiscFindingsComparator.Region region = source.region();
            if (region == null) {
                entry.put("region", null);
                entry.put("imagePath", null);
            } else {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("inputId", region.inputId());
                r.put("sliceIndex", region.sliceIndex());
                r.put("point", Map.of("x", region.pointX(), "y", region.pointY()));
                r.put("bbox", Map.of("x0", region.x0(), "y0", region.y0(), "x1", region.x1(), "y1", region.y1()));
                r.put("width", region.width());
                r.put("height", region.height());
                entry.put("region", r);
                boolean onAnalysisImage = region.inputId().equals(analysisInputId) && analysisIndex != null && analysisIndex == region.sliceIndex() && !sagittalPlaneRunId.isEmpty();
                entry.put("imagePath", onAnalysisImage
                    ? "/api/ai/assets/" + sagittalPlaneRunId + "/sagittal/input.png"
                    : "/api/ai/series/" + region.inputId() + "/slices/" + region.sliceIndex());
            }
            sources.add(entry);
        }
        item.put("sources", sources);
        return item;
    }

    private static String findingText(DiscFindingsComparator.Finding f) {
        String probability = f.confidence() == null ? "probabilidad no informada" : fmt(f.confidence() * 100) + " %";
        String tag = "pfirrmann_grade".equals(f.findingType()) ? " · experimental" : "not_product_supported".equals(f.deploymentStatus()) ? " · investigación" : "";
        return AssistantContextBuilder.FINDING_LABELS.getOrDefault(f.findingType(), f.findingType()) + ": predicción del modelo «" + className(f) + "» (" + probability + ")" + tag;
    }

    private static String className(DiscFindingsComparator.Finding f) {
        return switch (f.findingType()) {
            case "pfirrmann_grade" -> "grado " + f.label();
            case "modic_change" -> f.label().equals("none") ? "sin cambios Modic" : "Modic tipo " + f.label();
            default -> f.label().equals("present") ? "presente" : "ausente";
        };
    }

    private Map<String, Object> annotationItem(ReviewerAnnotation a) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", "a:" + a.id());
        item.put("kind", a.kind());
        item.put("scope", a.scope());
        item.put("plane", a.plane());
        item.put("seriesId", a.seriesId());
        item.put("sliceIndex", a.sliceIndex());
        item.put("level", a.level());
        item.put("text", a.text());
        item.put("author", a.author());
        item.put("value", a.value());
        item.put("unit", a.unit());
        return item;
    }

    private List<Map<String, Object>> seriesSection(List<InputResource> inputs, Reading reading, StudyRun run, List<Map<String, Object>> findings, String analysisInputId) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        Map<String, Object> planes = map(run.metricsSnapshot().get("planes"));
        for (String plane : List.of("sagittal", "axial")) {
            Map<String, Object> input = map(map(planes.get(plane)).get("input"));
            String inputId = text(input.get("inputId"));
            if (inputId.isEmpty()) continue;
            InputSequence sequence = plane.equals("sagittal") ? reading.context().sequence() : InputSequence.unknown();
            byId.put(inputId, seriesEntry(inputId, plane, sequence, integer(input.get("sliceCount")), true, null));
        }
        for (Map<String, Object> finding : findings) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources = (List<Map<String, Object>>) finding.get("sources");
            for (Map<String, Object> source : sources) {
                @SuppressWarnings("unchecked")
                Map<String, Object> region = (Map<String, Object>) source.get("region");
                if (region == null) continue;
                String inputId = (String) region.get("inputId");
                byId.computeIfAbsent(inputId, id -> seriesEntry(id, "sagittal", InputSequence.unknown(), null, false, null));
            }
        }
        for (InputResource input : inputs) {
            Map<String, Object> entry = byId.get(input.inputId());
            if (entry == null) continue;
            InputSequence sequence = InputSequence.fromStored(input.weighting(), input.sequenceSource(), input.sequenceDescription());
            Map<String, Object> filled = seriesEntry(input.inputId(), input.plane(), sequence, input.sliceCount(), Boolean.TRUE.equals(entry.get("analyzed")), input.description());
            byId.put(input.inputId(), filled);
        }
        return new ArrayList<>(byId.values());
    }

    private static Map<String, Object> seriesEntry(String inputId, String plane, InputSequence sequence, Integer sliceCount, boolean analyzed, String description) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("inputId", inputId);
        entry.put("plane", plane);
        String value = sequence.isKnown() ? sequence.value().toUpperCase(Locale.ROOT) : null;
        entry.put("sequence", value);
        entry.put("sequenceSource", sequence.isKnown() ? (sequence.isDeclared() ? "declarada" : "detectada") : null);
        entry.put("sliceCount", sliceCount);
        entry.put("analyzed", analyzed);
        entry.put("description", description == null || description.isBlank() ? null : description);
        entry.put("label", ("sagittal".equals(plane) ? "Sagital" : "Axial") + (value == null ? "" : " " + value));
        return entry;
    }

    private Map<String, Object> coverage(List<Map<String, Object>> measurements, List<Map<String, Object>> findings, DiscFindingsComparator.Side findingsSide) {
        Map<String, Object> coverage = new LinkedHashMap<>();
        long usable = measurements.stream().filter(m -> m.get("invalidReason") == null).count();
        coverage.put("measurementsTotal", measurements.size());
        coverage.put("measurementsUsable", usable);
        coverage.put("measurementsWithTrace", measurements.stream().filter(m -> "ia".equals(map(m.get("trace")).get("origin")) || "profesional".equals(map(m.get("trace")).get("origin"))).count());
        coverage.put("measurementsCorrected", measurements.stream().filter(m -> m.get("corrected") != null).count());
        coverage.put("findingsTotal", findings.size());
        coverage.put("findingsPositiveMain", findings.stream().filter(f -> "principal".equals(f.get("section")) && Boolean.TRUE.equals(f.get("positive"))).count());
        coverage.put("findingsRejected", findingsSide.status() == DiscFindingsComparator.SideStatus.READY ? findingsSide.rejected().size() : 0);
        return coverage;
    }

    /* ------------------------------------------------------------------ utilidades */

    private static boolean isLumbar(Object level) {
        return level != null && LUMBAR_LEVELS.contains(level);
    }

    private static void addLevel(List<String> names, String level) {
        // Solo espacios discales (T11-T12, L4-L5…): las vértebras sueltas (L4) no son un nivel de ficha y quedan en el anexo.
        if (level != null && level.contains("-") && !names.contains(level)) names.add(level);
    }

    private static int levelRank(String level) {
        int index = LEVEL_ORDER.indexOf(level);
        return index >= 0 ? index : 100;
    }

    private static Map<String, Object> omission(String kind, String what, String reason) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", kind);
        entry.put("what", what);
        entry.put("reason", reason);
        return entry;
    }

    private static List<List<Double>> points(List<InitialAnalysisComparisonDto.Point> list) {
        List<List<Double>> result = new ArrayList<>();
        for (InitialAnalysisComparisonDto.Point point : list) result.add(List.of(point.x(), point.y()));
        return result;
    }

    static String fmt(Double value) {
        return value == null ? "sin dato" : String.format(ES, "%.1f", value);
    }

    private static String displayUnit(String unit) {
        return switch (unit) {
            case "mm2" -> "mm²";
            case "deg" -> "°";
            default -> unit;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String text(Object value) {
        return value instanceof String s ? s.trim() : "";
    }

    private static Integer integer(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
