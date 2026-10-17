package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.controller.PreReportController;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.PreReportViewDto;
import ar.edu.uade.pfi.backend.service.exception.RunReviewException;
import ar.edu.uade.pfi.backend.service.exception.StudyNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * Preinforme visual: contenido generado desde la corrida exacta, borrador separado de lo generado, huella de fuentes,
 * actualización explícita, conflictos y permisos. Son pruebas de contrato del servidor (sin modelo de lenguaje).
 */
class PreReportServiceTest {
    private final AssistantTestFixtures fx = new AssistantTestFixtures();
    private final ObjectMapper mapper = new ObjectMapper();

    private PreReportService service() {
        return new PreReportService(fx.repository, new PreReportBuilder(fx.repository, new InitialAnalysisMeasurementReader(fx.repository)), mapper, null);
    }

    private static Map<String, Object> traced(String id, String labelKey, String level, String unit, double value, double[][] points) {
        Map<String, Object> m = AssistantTestFixtures.measurement(id, labelKey, level, unit, value);
        List<Map<String, Object>> pts = new ArrayList<>();
        for (double[] p : points) pts.add(Map.of("x", p[0], "y", p[1]));
        m.put("points", pts);
        return m;
    }

    /** Corrida con plano sagital que declara su espacio de coordenadas (como la real), trazos y hallazgos con regiones. */
    private StudyRun realisticRun(Study study, String runId, boolean withFindings) {
        fx.input(study, "inp-t2", "t2");
        fx.input(study, "inp-t1", "t1");
        List<Map<String, Object>> measurements = new ArrayList<>();
        measurements.add(traced("sagittal-disc-l5-s1-height", "disc height", "L5-S1", "mm", 13.43, new double[][] {{150, 200}, {150.5, 215}}));
        measurements.add(AssistantTestFixtures.measurement("sagittal-disc-l5-s1-area", "disc area", "L5-S1", "mm2", 312.23));
        measurements.add(traced("sagittal-disc-l4-l5-height", "disc height", "L4-L5", "mm", 9.31, new double[][] {{140, 160}, {140.5, 170}}));
        measurements.add(AssistantTestFixtures.measurement("sagittal-vertebra-l4-area", "vertebra area", "L4", "mm2", 700.0));
        measurements.add(AssistantTestFixtures.measurement("sagittal-canal-area", "canal area", null, "mm2", 2975.0));
        Map<String, Object> sagittal = new LinkedHashMap<>();
        sagittal.put("measurements", measurements);
        sagittal.put("input", Map.of("inputId", "inp-t2"));
        sagittal.put("synthetic", false);
        sagittal.put("planeRunId", "sag-" + runId);
        sagittal.put("coordinateSpace", Map.of("name", "model_256x256", "units", "pixel", "width", 256, "height", 256, "sourceSliceIndex", 7));
        Map<String, Object> planes = new LinkedHashMap<>();
        planes.put("sagittal", sagittal);
        planes.put("axial", Map.of("measurements", List.of()));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("planes", planes);
        if (withFindings) {
            Map<String, Object> bulgingL5 = AssistantTestFixtures.bulging("L5-S1", true, 0.704);
            bulgingL5.put("sourceSeries", List.of(
                region("sagittal_t2", "inp-t2", 7, 93.0, 228.5, new double[] {87, 205, 99, 252}, 384),
                region("sagittal_t1", "inp-t1", 7, 76.5, 188.5, new double[] {71, 170, 82, 207}, 320)));
            Map<String, Object> bulgingL4 = AssistantTestFixtures.bulging("L4-L5", false, 0.9);
            Map<String, Object> badRegion = AssistantTestFixtures.finding("L3-L4", "disc_narrowing", "present", Map.of("absent", 0.4, "present", 0.6));
            badRegion.put("sourceSeries", List.of(
                region("sagittal_t2", "inp-t2", 7, 93.0, 228.5, new double[] {87, 205, 99, 900}, 384),
                Map.of("role", "sagittal_t1", "available", true, "positions", List.of(3, 4, 5))));
            Map<String, Object> research = AssistantTestFixtures.finding("L5-S1", "spondylolisthesis", "present", Map.of("absent", 0.3, "present", 0.7));
            Map<String, Object> pfirrmann = AssistantTestFixtures.finding("L5-S1", "pfirrmann_grade", "II", Map.of("I", 0.07, "II", 0.358, "III", 0.356, "IV", 0.206, "V", 0.01));
            snapshot.put("discDegenerativeFindings", Map.of("schemaVersion", DiscFindingsComparator.SCHEMA_VERSION, "findings", List.of(bulgingL5, bulgingL4, badRegion, research, pfirrmann)));
            snapshot.put("discDegenerativeGovernance", Map.of("humanReviewRequired", true, "notClinicalDiagnosis", true, "autonomousDiagnosis", false,
                "predictionImmutable", true, "reviewStoredSeparately", true));
        }
        StudyRun run = new StudyRun(java.util.UUID.randomUUID().toString(), study.id(), runId, "trace", "real_baseline", "real_baseline", "sagittal_spider", "axial",
            "h", "", "sag-" + runId, "ax-" + runId, Map.of(), snapshot, List.of(), "completed", "pending", "", null, "", AssistantTestFixtures.T0, AssistantTestFixtures.T0);
        fx.repository.saveRun(run);
        return run;
    }

    private static Map<String, Object> region(String role, String inputId, int slice, double py, double px, double[] bbox, int size) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("role", role);
        source.put("available", true);
        source.put("positions", List.of(slice - 1, slice, slice + 1));
        source.put("inputId", inputId);
        source.put("sliceIndex", slice);
        source.put("pointYx", List.of(py, px));
        source.put("bboxYx", List.of(bbox[0], bbox[1], bbox[2], bbox[3]));
        source.put("coordinateSpaceHeight", size);
        source.put("coordinateSpaceWidth", size);
        return source;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> byKey(Map<String, Object> generated, String section, String key) {
        return ((List<Map<String, Object>>) generated.get(section)).stream().filter(item -> key.equals(item.get("key"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> level(Map<String, Object> generated, String level) {
        return ((List<Map<String, Object>>) generated.get("levels")).stream().filter(item -> level.equals(item.get("level"))).findFirst().orElseThrow();
    }

    /* ------------------------------------------------------------------ contenido generado */

    @Test
    @SuppressWarnings("unchecked")
    void generatedContentComesFromTheExactRunWithSourceReferencesAndPersistedEvidence() {
        Study study = fx.studyWithContext("MEN-1", "P-1", LocalDate.of(2026, 10, 7), null, null, null, null, null, null);
        StudyRun run = realisticRun(study, "run-1", true);
        fx.correct(run, "sagittal-disc-l5-s1-height", 12.0, "mm");

        Map<String, Object> generated = service().view("MEN-1", "run-1").generated();

        Map<String, Object> height = byKey(generated, "measurements", "m:sagittal-disc-l5-s1-height");
        assertEquals(13.43, (Double) height.get("original"), 1e-9);
        assertEquals(12.0, (Double) height.get("corrected"), 1e-9);
        assertEquals(12.0, (Double) height.get("effective"), 1e-9);
        assertEquals("profesional", height.get("origin"));
        // La corrección es solo numérica: el trazo sigue siendo el de la IA, identificado como tal.
        Map<String, Object> trace = (Map<String, Object>) height.get("trace");
        assertEquals("located", trace.get("status"));
        assertEquals("ia", trace.get("origin"));
        assertEquals(2, ((List<?>) trace.get("points")).size());
        assertEquals("/api/ai/assets/sag-run-1/sagittal/input.png", height.get("imagePath"));
        assertTrue(((String) height.get("text")).contains("corrección profesional (IA: 13,4 mm)"), (String) height.get("text"));
        // Una medición sin trazo guardado no inventa imagen ni trazo.
        Map<String, Object> area = byKey(generated, "measurements", "m:sagittal-disc-l5-s1-area");
        assertNull(area.get("imagePath"));
        assertEquals("ia", area.get("origin"));
        assertEquals(List.of(), ((Map<String, Object>) area.get("trace")).get("points"));

        // Hallazgos: el positivo de las categorías principales se incluye; la región apunta a la imagen correcta.
        Map<String, Object> bulging = ((List<Map<String, Object>>) generated.get("findings")).stream()
            .filter(f -> "L5-S1".equals(f.get("level")) && "disc_bulging".equals(f.get("type"))).findFirst().orElseThrow();
        assertEquals("principal", bulging.get("section"));
        assertEquals(true, bulging.get("positive"));
        assertEquals(true, bulging.get("defaultInclude"));
        List<Map<String, Object>> sources = (List<Map<String, Object>>) bulging.get("sources");
        assertEquals("/api/ai/assets/sag-run-1/sagittal/input.png", sources.get(0).get("imagePath"), "serie y corte analizados: la imagen canónica de la corrida");
        assertEquals("/api/ai/series/inp-t1/slices/7", sources.get(1).get("imagePath"), "otra serie: su corte, no uno genérico");
        assertEquals(384, ((Map<String, Object>) sources.get(0).get("region")).get("width"));

        // Región inválida: se conserva el resultado, sin localización y con el motivo.
        Map<String, Object> narrowing = ((List<Map<String, Object>>) generated.get("findings")).stream().filter(f -> "L3-L4".equals(f.get("level"))).findFirst().orElseThrow();
        Map<String, Object> badSource = ((List<Map<String, Object>>) narrowing.get("sources")).get(0);
        assertNull(badSource.get("region"));
        assertNull(badSource.get("imagePath"));
        assertTrue(((String) badSource.get("regionIssue")).contains("fuera de su espacio de coordenadas"), String.valueOf(badSource.get("regionIssue")));

        // Secciones: Pfirrmann experimental; investigación fuera del cuerpo por defecto.
        Map<String, Object> pfirrmann = ((List<Map<String, Object>>) generated.get("findings")).stream().filter(f -> "pfirrmann_grade".equals(f.get("type"))).findFirst().orElseThrow();
        assertEquals("experimental", pfirrmann.get("section"));
        assertTrue(((String) pfirrmann.get("text")).contains("experimental"));
        Map<String, Object> research = ((List<Map<String, Object>>) generated.get("findings")).stream().filter(f -> "spondylolisthesis".equals(f.get("type"))).findFirst().orElseThrow();
        assertEquals("investigacion", research.get("section"));
        assertEquals(false, research.get("defaultInclude"));
        assertTrue(((List<Map<String, Object>>) generated.get("omitted")).stream().anyMatch(o -> "research_findings".equals(o.get("kind"))));

        // Fichas por defecto: solo el nivel con una predicción positiva principal (L3-L4 también: narrowing present).
        assertEquals(true, level(generated, "L5-S1").get("defaultCard"));
        assertEquals(true, level(generated, "L3-L4").get("defaultCard"));
        assertEquals(false, level(generated, "L4-L5").get("defaultCard"));
        assertEquals(false, level(generated, "L1-L2").get("defaultCard"));
        assertEquals(false, level(generated, "L1-L2").get("evaluated"), "un nivel sin resultados figura como no evaluado");
        // Series: la analizada y las que usaron los hallazgos, con corte en base 0 y secuencia.
        List<Map<String, Object>> series = (List<Map<String, Object>>) generated.get("series");
        assertTrue(series.stream().anyMatch(s -> "inp-t2".equals(s.get("inputId")) && Boolean.TRUE.equals(s.get("analyzed"))));
        assertTrue(series.stream().anyMatch(s -> "inp-t1".equals(s.get("inputId")) && "T1".equals(s.get("sequence"))));
    }

    @Test
    void thereAreNoRangeVerdictsNoOutlierCountAndNoInventedContext() throws Exception {
        Study study = fx.study("MEN-1", "P-1", "S", null);
        realisticRun(study, "run-1", true);
        String json = mapper.writeValueAsString(service().view("MEN-1", "run-1").generated());
        for (String forbidden : List.of("outlier", "within", "below", "above", "fuera de rango", "dentro del rango", "real_baseline_x")) assertFalse(json.contains(forbidden), forbidden);
        @SuppressWarnings("unchecked")
        Map<String, Object> clinical = (Map<String, Object>) ((Map<String, Object>) service().view("MEN-1", "run-1").generated().get("study")).get("clinical");
        assertNull(clinical.get("ageAtStudy"), "no se inventa la edad");
        assertNull(((Map<?, ?>) service().view("MEN-1", "run-1").generated().get("study")).get("studyDate"), "no se inventa la fecha");
    }

    @Test
    void aRunWithoutP107ResultsIsExplicitAndNeverReadAsNegative() {
        Study study = fx.study("OSF-1", "P-2", "S2", null);
        realisticRun(study, "run-2", false);
        Map<String, Object> generated = service().view("OSF-1", "run-2").generated();
        assertEquals("none", generated.get("findingsStatus"));
        assertEquals(List.of(), generated.get("findings"));
        assertEquals(true, level(generated, "L5-S1").get("defaultCard"), "sin P10.7 el cuerpo no queda vacío: niveles lumbares con mediciones");
        assertEquals(false, level(generated, "L1-L2").get("defaultCard"), "un nivel sin mediciones no tiene ficha");
    }

    @Test
    void theRunMustBeIdentifiedAndOwnedByTheStudy() {
        Study study = fx.study("MEN-1", "P-1", "S", null);
        Study other = fx.study("OTRO", "P-9", "S9", null);
        realisticRun(study, "run-1", true);
        realisticRun(other, "run-ajena", true);
        PreReportService service = service();
        assertEquals("REPORT_RUN_REQUIRED", assertThrows(RunReviewException.class, () -> service.view("MEN-1", null)).code());
        assertEquals("REPORT_RUN_NOT_FOUND", assertThrows(RunReviewException.class, () -> service.view("MEN-1", "run-ajena")).code());
        assertThrows(StudyNotFoundException.class, () -> service.view("NO-EXISTE", "run-1"));
    }

    /* ------------------------------------------------------------------ borrador */

    @Test
    void openingNeverWritesAndSavingOnlyWritesTheDraftNotTheReview() {
        Study study = fx.study("MEN-1", "P-1", "S", null);
        StudyRun run = realisticRun(study, "run-1", true);
        PreReportService service = service();
        PreReportViewDto opened = service.view("MEN-1", "run-1");
        assertNull(opened.draft());
        assertTrue(fx.repository.findReportDraft(study.id(), "run-1").isEmpty(), "abrir no crea nada");

        PreReportViewDto saved = service.save("MEN-1", "run-1", null, Map.of("observations", "Revisar L5-S1.", "include", Map.of("m:sagittal-disc-l5-s1-area", true)), "dra", null);
        assertEquals(1, saved.draft().version());
        assertEquals(opened.fingerprint(), saved.draft().sourceFingerprint());
        assertEquals("Revisar L5-S1.", saved.draft().edits().get("observations"));
        // La revisión del estudio, las correcciones y las anotaciones no se tocaron.
        StudyRun after = fx.repository.findRunByMultiplanarRunId("run-1").orElseThrow();
        assertEquals("pending", after.reviewStatus());
        assertEquals(List.of(), fx.repository.findCorrectionsByStudyRunId(run.id()));
        assertEquals(List.of(), fx.repository.findAnnotationsByRunId("run-1"));
        assertEquals(run.metricsSnapshot(), after.metricsSnapshot(), "las predicciones no cambian");
        // Sobrevive a la recarga: otro servicio sobre el mismo repositorio lo recupera igual.
        PreReportViewDto reloaded = service().view("MEN-1", "run-1");
        assertEquals("Revisar L5-S1.", reloaded.draft().edits().get("observations"));
        assertFalse(reloaded.stale());
    }

    @Test
    void versionConflictsAreRejectedNeverOverwritten() {
        Study study = fx.study("MEN-1", "P-1", "S", null);
        realisticRun(study, "run-1", true);
        PreReportService service = service();
        service.save("MEN-1", "run-1", null, Map.of("observations", "A"), "dra", null);
        assertEquals("REPORT_DRAFT_VERSION_CONFLICT", assertThrows(RunReviewException.class, () -> service.save("MEN-1", "run-1", null, Map.of("observations", "B"), "dra", null)).code());
        assertEquals("REPORT_DRAFT_VERSION_CONFLICT", assertThrows(RunReviewException.class, () -> service.save("MEN-1", "run-1", 7, Map.of("observations", "B"), "dra", null)).code());
        PreReportViewDto ok = service.save("MEN-1", "run-1", 1, Map.of("observations", "C"), "dra", null);
        assertEquals(2, ok.draft().version());
        assertEquals("C", service.view("MEN-1", "run-1").draft().edits().get("observations"));
    }

    @Test
    void changedSourcesMarkTheDraftAsOutdatedWithoutRewritingItAndUpdatingIsExplicit() {
        Study study = fx.studyWithContext("MEN-1", "P-1", LocalDate.of(2026, 10, 7), 54, Study.AGE_REPORTED, "Lumbalgia", null, null, null);
        StudyRun run = realisticRun(study, "run-1", true);
        PreReportService service = service();
        PreReportViewDto first = service.save("MEN-1", "run-1", null, Map.of(
            "observations", "Mi observación.",
            "text", Map.of("m:sagittal-disc-l5-s1-height", "Texto editado por la profesional."),
            "include", Map.of("m:sagittal-disc-l5-s1-height", true)), "dra", null);

        // Cambian una corrección y el contexto clínico.
        fx.correct(run, "sagittal-disc-l5-s1-height", 12.0, "mm");
        fx.repository.saveStudy(new Study(study.id(), study.caseId(), study.status(), study.patientId(), study.subjectRef(), study.studyDate(), study.modality(),
            study.description(), study.reviewPriority(), 55, Study.AGE_REPORTED, "Lumbalgia", "Dolor irradiado", null, null, Study.CONTEXT_VERSION, study.createdAt(), study.updatedAt()));

        PreReportViewDto outdated = service.view("MEN-1", "run-1");
        assertTrue(outdated.stale());
        assertNotEquals(first.fingerprint(), outdated.fingerprint());
        assertEquals(first.draft().generated(), outdated.draft().generated(), "el borrador NO se reescribe en silencio");
        assertEquals("Mi observación.", outdated.draft().edits().get("observations"));
        assertTrue(outdated.changes().stream().anyMatch(c -> "m:sagittal-disc-l5-s1-height".equals(c.get("key")) && "changed".equals(c.get("kind"))), outdated.changes().toString());
        assertTrue(outdated.changes().stream().anyMatch(c -> String.valueOf(c.get("key")).equals("study:clinical.ageAtStudy")), "el cambio de contexto se informa");
        assertTrue(outdated.changes().stream().anyMatch(c -> String.valueOf(c.get("key")).equals("study:clinical.symptoms")));

        // Guardar ediciones sobre un borrador desactualizado no cambia lo generado ni "lo pone al día".
        PreReportViewDto stillOld = service.save("MEN-1", "run-1", first.draft().version(), Map.of("observations", "Mi observación 2."), "dra", null);
        assertTrue(stillOld.stale());
        assertEquals(first.draft().generated(), stillOld.draft().generated());

        // Actualización explícita: lo generado pasa a lo actual y las ediciones se conservan.
        String reviewBefore = fx.repository.findRunByMultiplanarRunId("run-1").orElseThrow().reviewStatus();
        PreReportViewDto updated = service.updateSources("MEN-1", "run-1", stillOld.draft().version(), "dra", null);
        assertFalse(updated.stale());
        assertEquals(outdated.fingerprint(), updated.draft().sourceFingerprint());
        assertEquals("Mi observación 2.", updated.draft().edits().get("observations"));
        assertEquals(stillOld.draft().version() + 1, updated.draft().version());
        assertEquals(List.of(), updated.orphanedEdits());
        assertEquals(reviewBefore, fx.repository.findRunByMultiplanarRunId("run-1").orElseThrow().reviewStatus(), "actualizar el borrador no toca la revisión");
    }

    @Test
    void editsOfElementsThatNoLongerExistAreKeptAndReportedAsOrphaned() {
        Study study = fx.study("MEN-1", "P-1", "S", null);
        realisticRun(study, "run-1", true);
        PreReportService service = service();
        PreReportViewDto saved = service.save("MEN-1", "run-1", null, Map.of("text", Map.of("m:no-existe-mas", "nota"), "include", Map.of("f:viejo", true)), "dra", null);
        PreReportViewDto updated = service.updateSources("MEN-1", "run-1", saved.draft().version(), "dra", null);
        assertEquals(List.of("f:viejo", "m:no-existe-mas").stream().sorted().toList(), updated.orphanedEdits().stream().sorted().toList());
        assertEquals("nota", ((Map<?, ?>) updated.draft().edits().get("text")).get("m:no-existe-mas"));
    }

    @Test
    void editsAreValidated() {
        Study study = fx.study("MEN-1", "P-1", "S", null);
        realisticRun(study, "run-1", true);
        PreReportService service = service();
        assertEquals("REPORT_EDITS_INVALID", assertThrows(RunReviewException.class, () -> service.save("MEN-1", "run-1", null, Map.of("approved", true), "dra", null)).code());
        assertEquals("REPORT_EDITS_INVALID", assertThrows(RunReviewException.class, () -> service.save("MEN-1", "run-1", null, Map.of("include", Map.of("m:x", "si")), "dra", null)).code());
        assertEquals("REPORT_EDITS_TOO_LONG", assertThrows(RunReviewException.class, () -> service.save("MEN-1", "run-1", null, Map.of("text", Map.of("m:x", "x".repeat(PreReportService.MAX_TEXT_LENGTH + 1))), "dra", null)).code());
        assertEquals("REPORT_DRAFT_NOT_FOUND", assertThrows(RunReviewException.class, () -> service.updateSources("MEN-1", "run-1", 1, "dra", null)).code());
    }

    /* ------------------------------------------------------------------ permisos */

    @Test
    void onlyProfessionalRolesCanUseThePreReport() {
        Study study = fx.study("MEN-1", "P-1", "S", null);
        realisticRun(study, "run-1", true);
        PreReportController controller = new PreReportController(service(), new RoleAuthorizationService(null));
        MockHttpServletRequest doctor = new MockHttpServletRequest();
        doctor.setAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE, new TokenService.Claims("u1", "u@x", "U", List.of("DOCTOR")));
        assertTrue(controller.view("MEN-1", "run-1", doctor).usable());
        assertEquals(1, controller.save("MEN-1", "run-1", new PreReportController.SaveRequest(null, Map.of("observations", "x")), doctor).draft().version());
        for (List<String> denied : java.util.Arrays.asList(List.of("USER"), List.<String>of(), null)) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            if (denied != null) request.setAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE, new TokenService.Claims("u2", "u@x", "U", denied));
            assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.view("MEN-1", "run-1", request)).getStatusCode().value());
            assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.save("MEN-1", "run-1", null, request)).getStatusCode().value());
            assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.updateSources("MEN-1", "run-1", null, request)).getStatusCode().value());
        }
    }
}
