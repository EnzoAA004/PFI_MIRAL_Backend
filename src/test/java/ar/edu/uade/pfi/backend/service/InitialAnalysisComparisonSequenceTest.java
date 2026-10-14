package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.InputSequence;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.InitialAnalysisComparisonDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * La secuencia sagital declarada o detectada es lo que habilita (o no) comparar dos estudios.
 *
 * <p>Una declaración no es una detección ni una validación del modelo; desconocida no equivale a
 * desconocida; «Otra» no equivale a «Otra»; y el texto libre nunca se interpreta como ponderación.
 */
class InitialAnalysisComparisonSequenceTest {
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final InitialAnalysisComparisonService service =
        new InitialAnalysisComparisonService(repository, new InitialAnalysisMeasurementReader(repository));

    private Study study(String caseId) {
        Study study = new Study(UUID.randomUUID().toString(), caseId, "registered", "P-1", "VIS-1", LocalDate.of(2026, 4, 1), "MRI", "desc", "medium", T0, T0);
        repository.saveStudy(study);
        return study;
    }

    private static Map<String, Object> sequence(String value, String source, String description) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("value", value);
        map.put("source", source);
        map.put("description", description);
        return map;
    }

    private static Map<String, Object> measurement(double value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", "sagittal-disc-l4-l5-height");
        map.put("labelKey", "disc height");
        map.put("level", "L4-L5");
        map.put("levelScope", "level");
        map.put("unit", "mm");
        map.put("value", value);
        map.put("aiValue", value);
        map.put("measurementBasis", "physical_spacing");
        map.put("sliceIndex", 7);
        map.put("source", "AI");
        return map;
    }

    /** Corrida cuyo snapshot publica {@code input.sequence} (o no lo publica si {@code published} es nulo). */
    private void run(Study study, String runId, Instant createdAt, Map<String, Object> published, double value) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("inputId", "inp-" + runId);
        if (published != null) input.put("sequence", published);
        Map<String, Object> sagittal = new LinkedHashMap<>();
        sagittal.put("measurements", List.of(measurement(value)));
        sagittal.put("input", input);
        sagittal.put("synthetic", false);
        Map<String, Object> planes = new LinkedHashMap<>();
        planes.put("sagittal", sagittal);
        planes.put("axial", Map.of("measurements", List.of()));
        repository.saveRun(new StudyRun(
            UUID.randomUUID().toString(), study.id(), runId, "trace-" + runId, "real_baseline", "real_baseline",
            "sagittal_spider", "axial_t2_alkafri", "hash-1", "", "sag-" + runId, "ax-" + runId,
            Map.of(), Map.of("planes", planes), List.of(), "completed", "pending", "", null, "", createdAt, createdAt));
    }

    private InitialAnalysisComparisonDto compare(Map<String, Object> reference, Map<String, Object> current) {
        Study ref = study("ref");
        Study cur = study("cur");
        run(ref, "run-ref", T0, reference, 5.0);
        run(cur, "run-cur", T0.plusSeconds(100), current, 6.0);
        return service.compare("cur", "ref", null, null);
    }

    private static String methodMismatch(InitialAnalysisComparisonDto dto) {
        return dto.excluded().stream().filter((e) -> e.reasonCode().equals("method_mismatch")).findFirst().orElseThrow().reason();
    }

    /* ------------------------------------------------------------------------------ coincidencias */

    @Test
    void twoDeclaredT1StudiesAreComparedAndTheDeclarationIsIdentifiedAsSuch() {
        InitialAnalysisComparisonDto dto = compare(sequence("t1", "user_declared", ""), sequence("t1", "user_declared", ""));
        assertEquals(1, dto.comparisons().size());
        assertEquals("t1", dto.current().sagittalSequence());
        assertEquals("user_declared", dto.current().sagittalSequenceSource());
        assertEquals("user_declared", dto.reference().sagittalSequenceSource());
        String note = dto.limitations().get(dto.limitations().size() - 1);
        assertTrue(note.contains("T1 en ambos estudios") && note.contains("declarada por quien cargó el archivo"), note);
        assertTrue(note.contains("no es una detección automática ni una validación del modelo"), note);
        assertTrue(note.contains("no garantiza el mismo protocolo"), note);
    }

    @Test
    void aDeclaredAndADetectedSequenceCanMatchAndEachKeepsItsProvenance() {
        InitialAnalysisComparisonDto dto = compare(sequence("t2", "auto_detected", ""), sequence("t2", "user_declared", ""));
        assertEquals(1, dto.comparisons().size());
        assertEquals("auto_detected", dto.reference().sagittalSequenceSource());
        assertEquals("user_declared", dto.current().sagittalSequenceSource());
        String note = dto.limitations().get(dto.limitations().size() - 1);
        assertTrue(note.contains("detectada del estudio DICOM en la referencia") && note.contains("declarada por quien cargó el archivo en el estudio abierto"), note);
    }

    @Test
    void withoutAMatchingSequenceTheExtraLimitationIsNotAdded() {
        InitialAnalysisComparisonDto dto = compare(sequence("t1", "user_declared", ""), sequence("t2", "user_declared", ""));
        assertEquals(InitialAnalysisComparisonService.LIMITATIONS, dto.limitations());
    }

    /* ------------------------------------------------------------------------------ exclusiones */

    @Test
    void differentDeclaredSequencesAreNotCompared() {
        InitialAnalysisComparisonDto dto = compare(sequence("t1", "user_declared", ""), sequence("t2", "user_declared", ""));
        assertEquals(0, dto.comparisons().size());
        assertTrue(methodMismatch(dto).contains("Secuencias distintas (T1 en la referencia, T2 en el estudio abierto)"));
    }

    @Test
    void unknownIsNeverEquivalentToUnknown() {
        InitialAnalysisComparisonDto dto = compare(sequence("unknown", "unknown", ""), sequence("unknown", "unknown", ""));
        assertEquals(0, dto.comparisons().size());
        assertTrue(methodMismatch(dto).contains("no está informada"));
    }

    @Test
    void oneKnownAndOneUnknownAreNotCompared() {
        InitialAnalysisComparisonDto dto = compare(sequence("t1", "user_declared", ""), sequence("unknown", "unknown", ""));
        assertEquals(0, dto.comparisons().size());
        assertTrue(methodMismatch(dto).contains("no está informada"));
    }

    @Test
    void twoOtherSequencesAreNotEquivalentEvenWithTheSameDescription() {
        InitialAnalysisComparisonDto dto = compare(sequence("other", "user_declared", "STIR sagital"), sequence("other", "user_declared", "STIR sagital"));
        assertEquals(0, dto.comparisons().size());
        assertTrue(methodMismatch(dto).contains("«Otra»"));
        assertEquals("STIR sagital", dto.current().sagittalSequenceDescription());
    }

    @Test
    void otherIsNotEquivalentToT1OrT2() {
        assertEquals(0, compare(sequence("other", "user_declared", "T1 con contraste"), sequence("t1", "user_declared", "")).comparisons().size());
    }

    @Test
    void theFreeTextDescriptionIsNeverReadAsAWeighting() {
        // «other» con la descripcion "T1" no pasa a ser T1 aunque el otro lado sea T1.
        InitialAnalysisComparisonDto dto = compare(sequence("other", "user_declared", "T1"), sequence("t1", "user_declared", ""));
        assertEquals(0, dto.comparisons().size());
        assertEquals("other", dto.reference().sagittalSequence());
    }

    /* -------------------------------------------------------------------- historicos y retrocompatibilidad */

    @Test
    void aRunWithoutAPublishedSequenceFallsBackToTheStudysRegisteredWeightingAsDetected() {
        Study ref = study("ref");
        Study cur = study("cur");
        run(ref, "run-ref", T0, null, 5.0);
        run(cur, "run-cur", T0.plusSeconds(100), null, 6.0);
        // Filas del catalogo anteriores a la procedencia: solo `weighting`, que venia de la deteccion del zip.
        repository.saveInput(new InputResource(UUID.randomUUID().toString(), ref.id(), "sagittal", "inp-run-ref", "dcm", 1, "t2_tse", "t2", 15, false, false, true, T0));
        repository.saveInput(new InputResource(UUID.randomUUID().toString(), cur.id(), "sagittal", "inp-run-cur", "dcm", 1, "t2_tse", "t2", 15, false, false, true, T0));
        InitialAnalysisComparisonDto dto = service.compare("cur", "ref", null, null);
        assertEquals(1, dto.comparisons().size());
        assertEquals("auto_detected", dto.current().sagittalSequenceSource());
    }

    @Test
    void historicRunsWithoutAnyInformationStayUnknown() {
        Study ref = study("ref");
        Study cur = study("cur");
        run(ref, "run-ref", T0, null, 5.0);
        run(cur, "run-cur", T0.plusSeconds(100), null, 6.0);
        InitialAnalysisComparisonDto dto = service.compare("cur", "ref", null, null);
        assertEquals(0, dto.comparisons().size());
        assertEquals("unknown", dto.current().sagittalSequenceSource());
        assertTrue(methodMismatch(dto).contains("no está informada"));
    }

    @Test
    void theRunsOwnSequenceWinsOverTheCatalogue() {
        Study ref = study("ref");
        Study cur = study("cur");
        run(ref, "run-ref", T0, sequence("unknown", "unknown", ""), 5.0);
        run(cur, "run-cur", T0.plusSeconds(100), sequence("t2", "user_declared", ""), 6.0);
        // El catalogo dice t2 para la referencia, pero la corrida publico desconocida: manda la corrida.
        repository.saveInput(new InputResource(UUID.randomUUID().toString(), ref.id(), "sagittal", "inp-run-ref", "dcm", 1, "", "t2", 15, false, false, true, T0));
        assertEquals(0, service.compare("cur", "ref", null, null).comparisons().size());
    }

    /* ------------------------------------------------------------------------------ InputSequence */

    @Test
    void incoherentOrIncompleteSequencesAreUnknown() {
        assertFalse(InputSequence.fromMap(sequence("t1", "unknown", "")).isKnown());
        assertFalse(InputSequence.fromMap(sequence("unknown", "user_declared", "")).isKnown());
        assertFalse(InputSequence.fromMap(sequence("other", "user_declared", "  ")).isKnown());
        assertFalse(InputSequence.fromMap(sequence("other", "auto_detected", "STIR")).isKnown());
        assertFalse(InputSequence.fromMap(sequence("flair", "user_declared", "")).isKnown());
        assertFalse(InputSequence.fromMap(sequence("other", "user_declared", "x".repeat(81))).isKnown());
        assertFalse(InputSequence.fromMap(null).isKnown());
        assertFalse(InputSequence.fromStored("", "", "").isKnown());
        assertFalse(InputSequence.fromStored("other", "", "STIR").isKnown());
    }

    @Test
    void validSequencesKeepValueSourceAndOnlyOthersKeepADescription() {
        assertEquals(new InputSequence("t1", "user_declared", ""), InputSequence.fromMap(sequence("T1", "user_declared", "texto")));
        assertEquals(new InputSequence("other", "user_declared", "STIR"), InputSequence.fromMap(sequence("other", "user_declared", " STIR ")));
        assertEquals(new InputSequence("t2", "auto_detected", ""), InputSequence.fromStored("t2", "", ""));
        assertEquals(new InputSequence("t2", "user_declared", ""), InputSequence.fromStored("t2", "user_declared", ""));
    }
}
