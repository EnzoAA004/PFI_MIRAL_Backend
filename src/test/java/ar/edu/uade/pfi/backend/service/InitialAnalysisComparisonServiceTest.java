package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.controller.InitialAnalysisComparisonController;
import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.InitialAnalysisComparisonDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.exception.RunReviewException;
import ar.edu.uade.pfi.backend.service.exception.StudyNotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * Comparación entre estudios sobre las mediciones REALES del análisis inicial: correspondencia demostrable,
 * cálculo, identidad del paciente, selección de corrida y permisos. Nunca toca el circuito de demostración.
 */
class InitialAnalysisComparisonServiceTest {
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final InitialAnalysisComparisonService service =
        new InitialAnalysisComparisonService(repository, new InitialAnalysisMeasurementReader(repository));

    /* ------------------------------------------------------------------------------ fixtures */

    private Study study(String caseId, String patientId, String subjectRef, LocalDate date) {
        Study study = new Study(UUID.randomUUID().toString(), caseId, "registered", patientId, subjectRef, date, "MRI", "desc", "medium", T0, T0);
        repository.saveStudy(study);
        return study;
    }

    private InputResource input(Study study, String inputId, String weighting) {
        InputResource input = new InputResource(UUID.randomUUID().toString(), study.id(), "sagittal", inputId, "dcm", 10, "t2_tse_sag", weighting, 15, false, false, true, T0);
        repository.saveInput(input);
        return input;
    }

    /** Medición tal como la guarda el análisis inicial en {@code metrics_snapshot.planes.sagittal.measurements}. */
    private static Map<String, Object> m(String id, String labelKey, String level, String unit, Double value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("labelKey", labelKey);
        map.put("label", labelKey);
        map.put("level", level);
        map.put("levelScope", level == null ? "study" : "level");
        map.put("unit", unit);
        map.put("value", value);
        map.put("aiValue", value);
        map.put("plane", "sagittal");
        map.put("experimental", false);
        map.put("measurementBasis", unit.startsWith("px") ? "pixel_space" : "physical_spacing");
        map.put("sliceIndex", 7);
        map.put("source", "AI");
        return map;
    }

    private static Map<String, Object> disc(String level, String metric, Double value) {
        String unit = metric.equals("area") ? "mm2" : "mm";
        return m("sagittal-disc-" + level.toLowerCase() + "-" + metric, "disc " + metric, level, unit, value);
    }

    private StudyRun run(Study study, String multiplanarRunId, Instant createdAt, String sequenceInputId, List<Map<String, Object>> sagittal, List<Map<String, Object>> axial, String modelKey, boolean synthetic) {
        Map<String, Object> sagittalPlane = new LinkedHashMap<>();
        sagittalPlane.put("measurements", sagittal);
        sagittalPlane.put("input", Map.of("inputId", sequenceInputId));
        sagittalPlane.put("synthetic", synthetic);
        Map<String, Object> planes = new LinkedHashMap<>();
        planes.put("sagittal", sagittalPlane);
        planes.put("axial", Map.of("measurements", axial));
        StudyRun run = new StudyRun(
            UUID.randomUUID().toString(), study.id(), multiplanarRunId, "trace-" + multiplanarRunId, "real_baseline", "real_baseline",
            modelKey, "axial_t2_alkafri", "hash-1", "", "sag-" + multiplanarRunId, "ax-" + multiplanarRunId,
            Map.of(), Map.of("planes", planes), List.of(), "completed", "pending", "", null, "", createdAt, createdAt);
        repository.saveRun(run);
        return run;
    }

    private StudyRun run(Study study, String runId, Instant createdAt, List<Map<String, Object>> sagittal) {
        input(study, "inp-" + runId, "t2");
        return run(study, runId, createdAt, "inp-" + runId, sagittal, List.of(), "sagittal_spider", false);
    }

    private void correct(StudyRun run, String measurementId, Object correctedValue, String unit) {
        repository.saveReview(run.multiplanarRunId(), "observed", "Dra. Test", T0.plusSeconds(60), "nota larga",
            List.of(new MeasurementCorrection(UUID.randomUUID().toString(), run.id(), measurementId, "x", Map.of("value", 0), Map.of("value", correctedValue, "unit", unit), "", T0.plusSeconds(60))));
    }

    private static InitialAnalysisComparisonDto.Row row(InitialAnalysisComparisonDto dto, String level, String structure, String metric) {
        return dto.comparisons().stream()
            .filter((r) -> r.level().equals(level) && r.structure().equals(structure) && r.metric().equals(metric))
            .findFirst().orElseThrow(() -> new AssertionError("sin fila " + level + "/" + structure + "/" + metric + ": " + dto.comparisons()));
    }

    private static List<InitialAnalysisComparisonDto.Exclusion> excluded(InitialAnalysisComparisonDto dto, String code) {
        return dto.excluded().stream().filter((e) -> e.reasonCode().equals(code)).toList();
    }

    private Study[] pair() {
        Study reference = study("ref", "P-1", "VIS-1", LocalDate.of(2026, 4, 1));
        Study current = study("cur", "P-1", "VIS-1", LocalDate.of(2026, 10, 7));
        return new Study[] {current, reference};
    }

    /* --------------------------------------------------------------------- correspondencia y cálculo */

    @Test
    void validMatchComputesDifferencesWithFullPrecision() {
        Study[] s = pair();
        run(s[1], "run-ref", T0, List.of(disc("L4-L5", "height", 5.79), disc("L4-L5", "area", 126.81), disc("L4-L5", "width", 37.37),
            m("sagittal-canal-ap-l4-l5", "canal ap", "L4-L5", "mm", 12.3)));
        run(s[0], "run-cur", T0.plusSeconds(100), List.of(disc("L4-L5", "height", 7.3), disc("L4-L5", "area", 120.0), disc("L4-L5", "width", 37.37),
            m("sagittal-canal-ap-l4-l5", "canal ap", "L4-L5", "mm", 13.1)));

        InitialAnalysisComparisonDto dto = service.compare("cur", "ref", null, null);

        assertEquals("initial_analysis", dto.source());
        assertEquals("diferencia = estudio abierto − referencia", dto.differenceDefinition());
        assertEquals(4, dto.summary().comparedCount());
        InitialAnalysisComparisonDto.Row height = row(dto, "L4-L5", "disc", "height");
        assertEquals("mm", height.unit());
        assertEquals(5.79, height.reference().effectiveValue());
        assertEquals(7.3, height.current().effectiveValue());
        assertEquals(1.51, height.deltaAbsolute(), 0.0, "sin el ruido de coma flotante (7.3 - 5.79)");
        assertEquals(1.51 / 5.79 * 100, height.deltaPercent(), 1e-9);
        assertEquals("increased", height.trend());
        InitialAnalysisComparisonDto.Row area = row(dto, "L4-L5", "disc", "area");
        assertEquals("mm2", area.unit());
        assertEquals(-6.81, area.deltaAbsolute(), 0.0);
        assertEquals("decreased", area.trend());
        assertEquals("unchanged", row(dto, "L4-L5", "disc", "width").trend());
        assertEquals(0.8, row(dto, "L4-L5", "canal", "ap_diameter").deltaAbsolute(), 0.0, "13.1 - 12.3 exacto");
        assertEquals("cur", dto.current().caseId());
        assertEquals("run-cur", dto.current().runId());
        assertEquals("run-ref", dto.reference().runId());
        assertEquals("patient_id", dto.current().patientLink());
    }

    @Test
    void professionalCorrectionIsUsedAndOriginalStaysQueryable() {
        Study[] s = pair();
        StudyRun reference = run(s[1], "run-ref", T0, List.of(disc("L4-L5", "height", 5.79)));
        StudyRun current = run(s[0], "run-cur", T0.plusSeconds(100), List.of(disc("L4-L5", "height", 6.0)));
        correct(reference, "sagittal-disc-l4-l5-height", "7.3", "mm");

        InitialAnalysisComparisonDto.Row row = row(service.compare("cur", "ref", null, null), "L4-L5", "disc", "height");

        assertTrue(row.reference().corrected());
        assertEquals(7.3, row.reference().effectiveValue());
        assertEquals(5.79, row.reference().originalValue(), "el original de la IA no se pierde");
        assertEquals(7.3, row.reference().correctedValue());
        assertFalse(row.current().corrected());
        assertNull(row.current().correctedValue());
        assertEquals(-1.3, row.deltaAbsolute(), 0.0);
        assertEquals(current.multiplanarRunId(), "run-cur");
    }

    @Test
    void invalidCorrectionIsExcludedWithItsReasonInsteadOfFallingBackToTheOriginal() {
        Study[] s = pair();
        StudyRun reference = run(s[1], "run-ref", T0, List.of(disc("L4-L5", "height", 5.79), disc("L4-L5", "width", 30.0)));
        run(s[0], "run-cur", T0.plusSeconds(100), List.of(disc("L4-L5", "height", 6.0), disc("L4-L5", "width", 31.0)));
        correct(reference, "sagittal-disc-l4-l5-height", "abc", "mm");

        InitialAnalysisComparisonDto dto = service.compare("cur", "ref", null, null);

        assertEquals(1, dto.comparisons().size());
        assertEquals("invalid_value", excluded(dto, "invalid_value").get(0).reasonCode());
        assertTrue(excluded(dto, "invalid_value").get(0).reason().contains("no es un número"));
        // la contraparte queda sin equivalente: no se compara contra el valor original
        assertEquals(1, excluded(dto, "missing_in_other").size());
    }

    @Test
    void correctionWithAnotherUnitIsNotApplied() {
        Study[] s = pair();
        StudyRun reference = run(s[1], "run-ref", T0, List.of(disc("L4-L5", "height", 5.79), disc("L4-L5", "width", 30.0)));
        run(s[0], "run-cur", T0.plusSeconds(100), List.of(disc("L4-L5", "height", 6.0), disc("L4-L5", "width", 31.0)));
        correct(reference, "sagittal-disc-l4-l5-height", 7.3, "px");

        InitialAnalysisComparisonDto dto = service.compare("cur", "ref", null, null);

        assertTrue(excluded(dto, "invalid_value").get(0).reason().contains("otra unidad"));
    }

    @Test
    void missingValuesAreNeverTurnedIntoZero() {
        Study[] s = pair();
        run(s[1], "run-ref", T0, List.of(disc("L4-L5", "height", null), disc("L4-L5", "width", 30.0)));
        run(s[0], "run-cur", T0.plusSeconds(100), List.of(disc("L4-L5", "height", 6.0), disc("L4-L5", "width", 31.0), disc("L3-L4", "width", 29.0)));

        InitialAnalysisComparisonDto dto = service.compare("cur", "ref", null, null);

        assertEquals(1, dto.comparisons().size(), "solo el ancho de L4-L5 tiene dos valores");
        assertEquals(1.0, row(dto, "L4-L5", "disc", "width").deltaAbsolute(), 0.0);
        assertEquals(1, excluded(dto, "invalid_value").size());
        List<InitialAnalysisComparisonDto.Exclusion> missing = excluded(dto, "missing_in_other");
        assertEquals(2, missing.size());
        assertTrue(missing.stream().allMatch((e) -> e.reason().contains("no se cuenta como cero")));
        assertTrue(dto.comparisons().stream().noneMatch((r) -> r.reference().effectiveValue() == 0.0));
    }

    @Test
    void zeroReferenceHasNoPercentage() {
        Study[] s = pair();
        run(s[1], "run-ref", T0, List.of(disc("L4-L5", "height", 0.0), disc("L4-L5", "width", 10.0)));
        run(s[0], "run-cur", T0.plusSeconds(100), List.of(disc("L4-L5", "height", 2.5), disc("L4-L5", "width", 10.0)));

        InitialAnalysisComparisonDto.Row row = row(service.compare("cur", "ref", null, null), "L4-L5", "disc", "height");

        assertEquals(2.5, row.deltaAbsolute(), 0.0);
        assertNull(row.deltaPercent());
        assertEquals("not_computable_reference_zero", row.percentStatus());
        assertEquals("increased", row.trend());
    }

    @Test
    void incompatibleUnitsAreExcludedNotConverted() {
        Study[] s = pair();
        run(s[1], "run-ref", T0, List.of(disc("L4-L5", "height", 5.79), disc("L4-L5", "width", 30.0)));
        Map<String, Object> pixelHeight = m("sagittal-disc-l4-l5-height", "disc height", "L4-L5", "px", 12.0);
        run(s[0], "run-cur", T0.plusSeconds(100), List.of(pixelHeight, disc("L4-L5", "width", 31.0)));

        InitialAnalysisComparisonDto dto = service.compare("cur", "ref", null, null);

        assertEquals(1, dto.comparisons().size());
        InitialAnalysisComparisonDto.Exclusion exclusion = excluded(dto, "unit_mismatch").get(0);
        assertEquals("both", exclusion.side());
        assertTrue(exclusion.reason().contains("Unidades incompatibles (mm en la referencia, px en el estudio abierto)"));
    }

    @Test
    void pixelOnlyMeasurementsAreNotUsable() {
        Study[] s = pair();
        run(s[1], "run-ref", T0, List.of(m("sagittal-disc-l4-l5-height", "disc height", "L4-L5", "px", 10.0)));
        run(s[0], "run-cur", T0.plusSeconds(100), List.of(disc("L4-L5", "height", 6.0)));

        RunReviewException error = assertThrows(RunReviewException.class, () -> service.compare("cur", "ref", null, null));
        assertEquals("COMPARISON_NO_USABLE_MEASUREMENTS_REFERENCE", error.code());
    }

    @Test
    void duplicatedMetricsAreAmbiguousAndExcludedWithoutAveraging() {
        Study[] s = pair();
        run(s[1], "run-ref", T0, List.of(disc("L4-L5", "height", 5.79), disc("L4-L5", "width", 30.0)));
        Map<String, Object> second = disc("L4-L5", "height", 9.0);
        second.put("id", "sagittal-disc-d1-height-bis");
        run(s[0], "run-cur", T0.plusSeconds(100), List.of(disc("L4-L5", "height", 6.0), second, disc("L4-L5", "width", 31.0)));

        InitialAnalysisComparisonDto dto = service.compare("cur", "ref", null, null);

        assertEquals(1, dto.comparisons().size());
        InitialAnalysisComparisonDto.Exclusion ambiguous = excluded(dto, "ambiguous_duplicate").get(0);
        assertEquals("current", ambiguous.side());
        assertEquals(2, ambiguous.measurementIds().size());
        assertTrue(ambiguous.reason().contains("ambigua"));
        assertTrue(dto.comparisons().stream().noneMatch((r) -> r.metric().equals("height")));
    }

    @Test
    void anIdenticalIdIsNotEquivalenceAndALevelNeverComesFromPosition() {
        Study[] s = pair();
        // El id de una vértebra es posicional: el mismo id puede ser otra vértebra en otro estudio.
        run(s[1], "run-ref", T0, List.of(m("sagittal-vertebra-v1", "vertebra height", "L5", "mm", 25.0), disc("L4-L5", "width", 30.0)));
        run(s[0], "run-cur", T0.plusSeconds(100), List.of(m("sagittal-vertebra-v1", "vertebra height", "L4", "mm", 26.0), disc("L4-L5", "width", 31.0)));

        InitialAnalysisComparisonDto dto = service.compare("cur", "ref", null, null);

        assertEquals(1, dto.comparisons().size());
        assertEquals("disc", dto.comparisons().get(0).structure());
        assertEquals(2, excluded(dto, "missing_in_other").size(), "L4 y L5 no se parean aunque el id coincida");
    }

    @Test
    void measurementsWithoutProvenEquivalenceAreExcludedWithConcreteReasons() {
        Study[] s = pair();
        Map<String, Object> noLevel = disc("L4-L5", "height", 5.0);
        noLevel.put("level", null);
        noLevel.put("id", "sagittal-disc-d3-height");
        Map<String, Object> canalArea = m("sagittal-canal-area", "canal area", null, "mm2", 2945.26);
        Map<String, Object> axial = m("axial-raw_50-area", "raw_50 area", "L4-L5", "mm2", 100.0);
        axial.put("plane", "axial");
        Map<String, Object> unknown = m("sagittal-other", "mystery metric", "L4-L5", "mm", 3.0);
        List<Map<String, Object>> sagittal = List.of(noLevel, canalArea, unknown, disc("L4-L5", "width", 30.0));
        input(s[1], "inp-ref", "t2");
        run(s[1], "run-ref", T0, "inp-ref", sagittal, List.of(axial), "sagittal_spider", false);
        input(s[0], "inp-cur", "t2");
        run(s[0], "run-cur", T0.plusSeconds(100), "inp-cur", sagittal, List.of(axial), "sagittal_spider", false);

        InitialAnalysisComparisonDto dto = service.compare("cur", "ref", null, null);

        assertEquals(1, dto.comparisons().size());
        assertEquals(2, excluded(dto, "no_level").size());
        assertEquals(2, excluded(dto, "axial_unmapped").size());
        assertEquals(4, excluded(dto, "no_equivalent_definition").size());
        assertTrue(excluded(dto, "no_equivalent_definition").stream().anyMatch((e) -> e.reason().contains("depende de cuánta columna abarque")));
    }

    @Test
    void differentModelOrSequenceOrSyntheticRunsAreNotCompared() {
        Study[] s = pair();
        List<Map<String, Object>> sagittal = List.of(disc("L4-L5", "height", 5.0), disc("L4-L5", "width", 30.0));
        input(s[1], "inp-ref", "t2");
        run(s[1], "run-ref", T0, "inp-ref", sagittal, List.of(), "sagittal_spider", false);
        input(s[0], "inp-cur", "t1");
        run(s[0], "run-cur", T0.plusSeconds(100), "inp-cur", sagittal, List.of(), "sagittal_spider", false);

        InitialAnalysisComparisonDto sequence = service.compare("cur", "ref", null, null);
        assertEquals(0, sequence.comparisons().size());
        assertTrue(excluded(sequence, "method_mismatch").get(0).reason().contains("Secuencias distintas (T2 en la referencia, T1 en el estudio abierto)"));

        Study other = study("cur2", "P-1", "VIS-1", LocalDate.of(2026, 11, 1));
        input(other, "inp-2", "t2");
        run(other, "run-2", T0.plusSeconds(200), "inp-2", sagittal, List.of(), "otro_modelo", false);
        assertTrue(excluded(service.compare("cur2", "ref", null, null), "method_mismatch").get(0).reason().contains("Modelos distintos"));

        Study unknown = study("cur3", "P-1", "VIS-1", LocalDate.of(2026, 11, 2));
        input(unknown, "inp-3", "unknown");
        run(unknown, "run-3", T0.plusSeconds(300), "inp-3", sagittal, List.of(), "sagittal_spider", false);
        assertTrue(excluded(service.compare("cur3", "ref", null, null), "method_mismatch").get(0).reason().contains("no está informada"));

        Study synthetic = study("cur4", "P-1", "VIS-1", LocalDate.of(2026, 11, 3));
        input(synthetic, "inp-4", "t2");
        run(synthetic, "run-4", T0.plusSeconds(400), "inp-4", sagittal, List.of(), "sagittal_spider", true);
        assertTrue(excluded(service.compare("cur4", "ref", null, null), "method_mismatch").get(0).reason().contains("sintética"));
    }

    /* ---------------------------------------------------------------------------- identidad del paciente */

    private void seedComparableRuns(Study current, Study reference) {
        run(reference, "run-" + reference.caseId(), T0, List.of(disc("L4-L5", "height", 5.0)));
        run(current, "run-" + current.caseId(), T0.plusSeconds(100), List.of(disc("L4-L5", "height", 6.0)));
    }

    @Test
    void differentPatientIdsAreRejectedEvenWhenSubjectRefMatches() {
        Study reference = study("ref", "P-1", "VIS-1", LocalDate.of(2026, 4, 1));
        Study current = study("cur", "P-2", "VIS-1", LocalDate.of(2026, 10, 7));
        seedComparableRuns(current, reference);
        assertThrows(StudyNotFoundException.class, () -> service.compare("cur", "ref", null, null));
        assertTrue(service.candidates("cur", null, ignored -> true).candidates().isEmpty());
    }

    @Test
    void studiesWithoutPatientOrReferenceAreNeverAssumedToBeTheSamePerson() {
        Study reference = study("ref", null, null, LocalDate.of(2026, 4, 1));
        Study current = study("cur", null, null, LocalDate.of(2026, 10, 7));
        seedComparableRuns(current, reference);
        assertThrows(StudyNotFoundException.class, () -> service.compare("cur", "ref", null, null));
        Study blank = study("cur-blank", null, "  ", LocalDate.of(2026, 10, 8));
        Study blank2 = study("ref-blank", null, "", LocalDate.of(2026, 10, 9));
        seedComparableRuns(blank, blank2);
        assertThrows(StudyNotFoundException.class, () -> service.compare("cur-blank", "ref-blank", null, null));
    }

    @Test
    void differentSubjectRefsWithoutPatientIdAreRejected() {
        Study reference = study("ref", null, "VIS-1", LocalDate.of(2026, 4, 1));
        Study current = study("cur", null, "VIS-2", LocalDate.of(2026, 10, 7));
        seedComparableRuns(current, reference);
        assertThrows(StudyNotFoundException.class, () -> service.compare("cur", "ref", null, null));
    }

    @Test
    void historicalSubjectRefAssociationIsAcceptedWhenThereIsNoPatientIdOnOneSide() {
        Study reference = study("ref", null, " vis-1 ", LocalDate.of(2026, 4, 1));
        Study current = study("cur", "P-1", "VIS-1", LocalDate.of(2026, 10, 7));
        seedComparableRuns(current, reference);
        InitialAnalysisComparisonDto dto = service.compare("cur", "ref", null, null);
        assertEquals("subject_ref_historical", dto.current().patientLink());
        assertEquals(1, dto.comparisons().size());

        Study bothHistoric = study("hist", null, "VIS-9", LocalDate.of(2026, 1, 1));
        Study bothHistoric2 = study("hist2", null, "vis-9", LocalDate.of(2026, 2, 1));
        seedComparableRuns(bothHistoric2, bothHistoric);
        assertEquals("subject_ref_historical", service.compare("hist2", "hist", null, null).current().patientLink());
    }

    @Test
    void samePatientIdComparesEvenWithDifferentSubjectRef() {
        Study reference = study("ref", "P-1", "OLD-REF", LocalDate.of(2026, 4, 1));
        Study current = study("cur", "P-1", "NEW-REF", LocalDate.of(2026, 10, 7));
        seedComparableRuns(current, reference);
        assertEquals("patient_id", service.compare("cur", "ref", null, null).current().patientLink());
    }

    @Test
    void sameStudyAndUnknownStudiesAreRejected() {
        Study[] s = pair();
        seedComparableRuns(s[0], s[1]);
        assertEquals("COMPARISON_SAME_STUDY", assertThrows(RunReviewException.class, () -> service.compare("cur", "cur", null, null)).code());
        assertThrows(StudyNotFoundException.class, () -> service.compare("cur", "nope", null, null));
        assertThrows(StudyNotFoundException.class, () -> service.compare("nope", "ref", null, null));
    }

    /* ---------------------------------------------------------------------------- selección de corrida */

    @Test
    void theLatestRunOfEachStudyIsUsedAndOlderRunsAreNotSubstituted() {
        Study[] s = pair();
        run(s[1], "ref-old", T0, List.of(disc("L4-L5", "height", 4.0)));
        run(s[1], "ref-new", T0.plusSeconds(500), List.of(disc("L4-L5", "height", 5.0)));
        run(s[0], "cur-old", T0.plusSeconds(100), List.of(disc("L4-L5", "height", 9.0)));
        run(s[0], "cur-new", T0.plusSeconds(600), List.of(disc("L4-L5", "height", 6.0)));

        InitialAnalysisComparisonDto dto = service.compare("cur", "ref", null, null);

        assertEquals("ref-new", dto.reference().runId());
        assertEquals("cur-new", dto.current().runId());
        assertEquals(1.0, dto.comparisons().get(0).deltaAbsolute(), 0.0);
        assertNotNull(dto.reference().runCreatedAt());
    }

    @Test
    void theRunShownByTheViewerCanBeRequestedExplicitly() {
        Study[] s = pair();
        run(s[1], "ref-1", T0, List.of(disc("L4-L5", "height", 5.0)));
        run(s[0], "cur-old", T0.plusSeconds(100), List.of(disc("L4-L5", "height", 9.0)));
        run(s[0], "cur-new", T0.plusSeconds(600), List.of(disc("L4-L5", "height", 6.0)));

        InitialAnalysisComparisonDto dto = service.compare("cur", "ref", "cur-old", null);

        assertEquals("cur-old", dto.current().runId());
        assertEquals(4.0, dto.comparisons().get(0).deltaAbsolute(), 0.0);
    }

    @Test
    void aRunThatCannotBeRecoveredIsReportedAndNeverReplaced() {
        Study[] s = pair();
        run(s[1], "ref-1", T0, List.of(disc("L4-L5", "height", 5.0)));
        run(s[0], "cur-1", T0.plusSeconds(100), List.of(disc("L4-L5", "height", 6.0)));

        RunReviewException missing = assertThrows(RunReviewException.class, () -> service.compare("cur", "ref", "no-existe", null));
        assertEquals("COMPARISON_RUN_NOT_FOUND", missing.code());
        assertTrue(missing.getMessage().contains("No se reemplaza por otro análisis"));
        // una corrida que existe pero es de otro estudio tampoco se acepta
        RunReviewException foreign = assertThrows(RunReviewException.class, () -> service.compare("cur", "ref", "ref-1", null));
        assertEquals("COMPARISON_RUN_NOT_FOUND", foreign.code());
        assertThrows(RunReviewException.class, () -> service.compare("cur", "ref", null, "cur-1"));
    }

    @Test
    void ifTheLatestRunHasNoUsableMeasurementsAnOlderRunIsNotSubstituted() {
        Study[] s = pair();
        run(s[1], "ref-old", T0, List.of(disc("L4-L5", "height", 5.0)));
        run(s[1], "ref-new", T0.plusSeconds(500), List.of());
        run(s[0], "cur-1", T0.plusSeconds(100), List.of(disc("L4-L5", "height", 6.0)));

        RunReviewException error = assertThrows(RunReviewException.class, () -> service.compare("cur", "ref", null, null));
        assertEquals("COMPARISON_NO_USABLE_MEASUREMENTS_REFERENCE", error.code());
        assertTrue(error.getMessage().contains("ref-new"));
        assertTrue(error.getMessage().contains("No se reemplaza por otro análisis"));
    }

    @Test
    void aStudyWithoutAnyAnalysisIsReportedAsSuch() {
        Study[] s = pair();
        run(s[0], "cur-1", T0.plusSeconds(100), List.of(disc("L4-L5", "height", 6.0)));
        assertEquals("COMPARISON_NO_ANALYSIS_REFERENCE", assertThrows(RunReviewException.class, () -> service.compare("cur", "ref", null, null)).code());
    }

    @Test
    void theDemoProductCircuitIsNeverUsedAsAFallback() {
        Study[] s = pair();
        run(s[0], "cur-1", T0.plusSeconds(100), List.of(disc("L4-L5", "height", 6.0)));
        // el estudio de referencia solo tiene corrida de producto (demostración) con valores
        AiProductAnalysisRun demo = new AiProductAnalysisRun(
            UUID.randomUUID().toString(), s[1].id(), s[1].caseId(), "pfi.ai-product-result.v1", "post-e50-ai-product-freeze-v1",
            "partial", true, true, false, "trace", "sag", "ax", List.of(), null, T0, T0);
        AiDiscLevelResult level = new AiDiscLevelResult(UUID.randomUUID().toString(), demo.id(), 0, "L4-L5", "localized", "67B2", true, false, "paired", true, false, false, null, false);
        AiMeasurementResult measurement = new AiMeasurementResult(UUID.randomUUID().toString(), demo.id(), level.id(), "sagittal", "L4-L5", "disc", 100.0, 10.0, 5.0, true);
        repository.saveAiProductAnalysisRun(demo, List.of(level), List.of(measurement));

        assertEquals("COMPARISON_NO_ANALYSIS_REFERENCE", assertThrows(RunReviewException.class, () -> service.compare("cur", "ref", null, null)).code());
    }

    /* ------------------------------------------------------------------------------ candidatos */

    @Test
    void candidatesAreThePatientsOtherStudiesWithTheirUsability() {
        Study current = study("cur", "P-1", "VIS-1", LocalDate.of(2026, 10, 7));
        Study usable = study("usable", "P-1", "VIS-1", LocalDate.of(2026, 4, 1));
        Study empty = study("empty", "P-1", "VIS-1", LocalDate.of(2026, 6, 1));
        Study noRun = study("no-run", null, "VIS-1", LocalDate.of(2026, 2, 1));
        Study otherPatient = study("other", "P-2", "VIS-1", LocalDate.of(2026, 3, 1));
        Study unrelated = study("unrelated", null, "VIS-OTHER", LocalDate.of(2026, 3, 1));
        run(current, "cur-1", T0.plusSeconds(100), List.of(disc("L4-L5", "height", 6.0)));
        run(usable, "usable-1", T0, List.of(disc("L4-L5", "height", 5.0), disc("L4-L5", "width", 30.0)));
        run(empty, "empty-1", T0, List.of(m("x", "mystery", "L4-L5", "mm", 1.0)));
        run(otherPatient, "other-1", T0, List.of(disc("L4-L5", "height", 5.0)));
        run(unrelated, "unrelated-1", T0, List.of(disc("L4-L5", "height", 5.0)));

        InitialAnalysisComparisonDto.Candidates candidates = service.candidates("cur", null, ignored -> true);

        assertEquals("cur-1", candidates.current().runId());
        assertEquals(1, candidates.current().usableMeasurementCount());
        List<String> ids = candidates.candidates().stream().map(InitialAnalysisComparisonDto.Candidate::caseId).toList();
        assertEquals(List.of("empty", "usable", "no-run"), ids, "solo del mismo paciente, de la más reciente a la más antigua");
        assertFalse(ids.contains("other"));
        assertFalse(ids.contains("unrelated"));
        assertFalse(ids.contains("cur"));
        InitialAnalysisComparisonDto.Candidate usableCandidate = candidates.candidates().get(1);
        assertTrue(usableCandidate.eligible());
        assertEquals(2, usableCandidate.usableMeasurementCount());
        assertEquals("usable-1", usableCandidate.runId());
        assertFalse(candidates.candidates().get(0).eligible());
        assertEquals("no_usable_measurements", candidates.candidates().get(0).reasonCode());
        assertEquals("no_analysis", candidates.candidates().get(2).reasonCode());
        assertEquals("subject_ref_historical", candidates.candidates().get(2).patientLink());
        assertTrue(candidates.runSelectionRule().contains("más reciente"));
    }

    @Test
    void candidatesOfAStudyWithoutAnalysisStillListTheOthers() {
        Study current = study("cur", "P-1", "VIS-1", LocalDate.of(2026, 10, 7));
        Study other = study("other", "P-1", "VIS-1", LocalDate.of(2026, 4, 1));
        run(other, "other-1", T0, List.of(disc("L4-L5", "height", 5.0)));
        InitialAnalysisComparisonDto.Candidates candidates = service.candidates("cur", null, ignored -> true);
        assertNull(candidates.current().runId());
        assertEquals(0, candidates.current().usableMeasurementCount());
        assertEquals(1, candidates.candidates().size());
        assertEquals(List.of(), service.candidates("other", "other-1", ignored -> true).candidates().stream().map((c) -> c.runId()).filter((id) -> "other-1".equals(id)).toList());
    }

    /* ------------------------------------------------------------------------------------ permisos */

    private MockHttpServletRequest requestWithRoles(List<String> roles) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (roles != null) request.setAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE, new TokenService.Claims("user-1", "u@x", "User", roles));
        return request;
    }

    @Test
    void onlyProfessionalRolesCanReadComparisons() {
        Study[] s = pair();
        seedComparableRuns(s[0], s[1]);
        repository.claimStudyOwner("cur", "user-1");
        var controller = new InitialAnalysisComparisonController(service, new RoleAuthorizationService(null, repository));
        assertTrue(controller.candidates("cur", null, requestWithRoles(List.of("DOCTOR"))).candidates().isEmpty());
        assertEquals(404, assertThrows(ResponseStatusException.class,
            () -> controller.compare("cur", "ref", null, null, requestWithRoles(List.of("REVIEWER")))).getStatusCode().value());
        repository.claimStudyOwner("ref", "user-1");

        assertEquals(1, controller.compare("cur", "ref", null, null, requestWithRoles(List.of("REVIEWER"))).comparisons().size());
        assertEquals(1, controller.candidates("cur", null, requestWithRoles(List.of("DOCTOR"))).candidates().size());
        for (List<String> denied : java.util.Arrays.asList(List.of("USER"), List.<String>of(), null)) {
            ResponseStatusException compare = assertThrows(ResponseStatusException.class, () -> controller.compare("cur", "ref", null, null, requestWithRoles(denied)));
            assertEquals(403, compare.getStatusCode().value());
            ResponseStatusException candidates = assertThrows(ResponseStatusException.class, () -> controller.candidates("cur", null, requestWithRoles(denied)));
            assertEquals(403, candidates.getStatusCode().value());
        }
    }

    @Test
    void rowsComeOrderedByAnatomicalLevelThenStructureThenMetric() {
        Study[] s = pair();
        List<Map<String, Object>> measurements = new ArrayList<>(List.of(
            disc("L5-S1", "height", 4.0), disc("L1-L2", "width", 30.0), disc("L4-L5", "area", 120.0), disc("L4-L5", "height", 5.0),
            m("sagittal-canal-ap-l4-l5", "canal ap", "L4-L5", "mm", 12.0)));
        run(s[1], "ref-1", T0, measurements);
        run(s[0], "cur-1", T0.plusSeconds(100), measurements);

        List<String> order = service.compare("cur", "ref", null, null).comparisons().stream()
            .map((r) -> r.level() + "/" + r.structure() + "/" + r.metric()).toList();

        assertEquals(List.of("L1-L2/disc/width", "L4-L5/disc/height", "L4-L5/disc/area", "L4-L5/canal/ap_diameter", "L5-S1/disc/height"), order);
    }
}
