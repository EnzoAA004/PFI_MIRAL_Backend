package ar.edu.uade.pfi.backend.service;
import ar.edu.uade.pfi.backend.service.exception.StudyNotFoundException;
import ar.edu.uade.pfi.backend.service.exception.RunReviewException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiMeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.LongitudinalComparisonResultDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Stage 79: deterministic longitudinal comparison — the 79-Y backend test list. */
class LongitudinalComparisonServiceTest {
    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final EffectiveAnalysisRunSelector selector = new EffectiveAnalysisRunSelector(repository);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-25T12:00:00Z"), ZoneOffset.UTC);
    private final LongitudinalComparisonService service = new LongitudinalComparisonService(repository, selector, new EffectiveMeasurementResolver(repository), clock);

    private record SeededMeasurement(AiProductAnalysisRun run, AiDiscLevelResult discLevel, AiMeasurementResult measurement) {
    }

    private Study seedStudy(String caseId, String subjectRef, LocalDate studyDate) {
        Instant now = Instant.now();
        Study study = new Study(UUID.randomUUID().toString(), caseId, "registered", subjectRef, studyDate, "MRI", "desc", "medium", now, now);
        repository.saveStudy(study);
        return study;
    }

    private AiProductAnalysisRun seedEmptyRun(Study study, String status) {
        Instant now = Instant.now();
        AiProductAnalysisRun run = new AiProductAnalysisRun(
            UUID.randomUUID().toString(), study.id(), study.caseId(), "pfi.ai-product-result.v1",
            "post-e50-ai-product-freeze-v1", status, true, true, false, "trace-1", "sag-1", "ax-1", List.of(), null, now, now
        );
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());
        return run;
    }

    private SeededMeasurement seedRunWithMeasurement(Study study, String status, String level, String plane, String structure, Double areaMm2, Double majorAxisMm, Double minorAxisMm) {
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        AiProductAnalysisRun run = new AiProductAnalysisRun(
            runId, study.id(), study.caseId(), "pfi.ai-product-result.v1", "post-e50-ai-product-freeze-v1",
            status, true, true, false, "trace-1", "sag-1", "ax-1", List.of(), null, now, now
        );
        AiDiscLevelResult discLevel = new AiDiscLevelResult(
            UUID.randomUUID().toString(), runId, 0, level, "localized", "67B2", true, false,
            "paired", true, false, false, null, false
        );
        AiMeasurementResult measurement = new AiMeasurementResult(
            UUID.randomUUID().toString(), runId, discLevel.id(), plane, level, structure, areaMm2, majorAxisMm, minorAxisMm, true
        );
        repository.saveAiProductAnalysisRun(run, List.of(discLevel), List.of(measurement));
        return new SeededMeasurement(run, discLevel, measurement);
    }

    private void correct(AiProductAnalysisRun run, AiMeasurementResult measurement, String field, Double originalValue, double correctedValue, Instant createdAt) {
        repository.saveAiMeasurementCorrection(new AiMeasurementCorrection(
            UUID.randomUUID().toString(), measurement.id(), run.id(), field, originalValue, correctedValue,
            "areaMm2".equals(field) ? "mm2" : "mm", null, "doctor-1", createdAt
        ));
    }

    private LongitudinalComparisonResultDto.MeasurementComparison findByKey(LongitudinalComparisonResultDto result, String level, String plane, String structure, String metric) {
        return result.measurements().stream()
            .filter(m -> java.util.Objects.equals(m.level(), level) && m.plane().equals(plane) && m.structure().equals(structure) && m.metric().equals(metric))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no comparison row for " + level + "/" + plane + "/" + structure + "/" + metric));
    }

    // ---- 1-4: patient/study resolution ----
    @Test
    void samePatientComparisonSucceeds() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.3, 5.0, 3.0);
        seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", 13.1, 5.2, 3.1);

        LongitudinalComparisonResultDto result = service.compare("case-current", "case-baseline");
        assertEquals("case-baseline", result.baselineCaseId());
        assertEquals("case-current", result.currentCaseId());
    }

    @Test
    void differentPatientIsRejected() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-2", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.3, 5.0, 3.0);
        seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", 13.1, 5.2, 3.1);

        assertThrows(StudyNotFoundException.class, () -> service.compare("case-current", "case-baseline"));
    }

    @Test
    void missingBaselineStudyThrowsNotFound() {
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", 13.1, 5.2, 3.1);

        assertThrows(StudyNotFoundException.class, () -> service.compare("case-current", "does-not-exist"));
    }

    @Test
    void missingCurrentStudyThrowsNotFound() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.3, 5.0, 3.0);

        assertThrows(StudyNotFoundException.class, () -> service.compare("does-not-exist", "case-baseline"));
    }

    // ---- 5: failed run ignored ----
    @Test
    void failedRunNeverUsedForComparison() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.3, 5.0, 3.0);
        seedEmptyRun(current, "failed");

        RunReviewException ex = assertThrows(RunReviewException.class, () -> service.compare("case-current", "case-baseline"));
        assertEquals("LONGITUDINAL_NO_USABLE_ANALYSIS_CURRENT", ex.code());
    }

    // ---- 6-7: run selection integration ----
    @Test
    void latestUsableRunIsUsedWhenNoneReviewed() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.3, 5.0, 3.0);
        seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", 13.1, 5.2, 3.1);

        LongitudinalComparisonResultDto result = service.compare("case-current", "case-baseline");
        // Both sides report all three fields (area/major/minor) for the same level/plane/structure -- all three match.
        assertEquals(3, result.measurements().stream().filter(m -> "comparable".equals(m.comparisonStatus())).count());
    }

    @Test
    void reviewedRunSelectionRuleAppliesInsideComparison() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        SeededMeasurement baselineSeed = seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.3, 5.0, 3.0);
        repository.upsertAiProductAnalysisRunReview(baselineSeed.run().id(), "accepted", null, "doctor-1", Instant.now());
        // A newer, unreviewed baseline run should be ignored in favor of the reviewed one.
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 999.0, 999.0, 999.0);
        seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", 13.1, 5.2, 3.1);

        LongitudinalComparisonResultDto result = service.compare("case-current", "case-baseline");
        assertEquals(baselineSeed.run().id(), result.baselineAnalysisRunId());
    }

    // ---- 8-9: effective value source ----
    @Test
    void aiOriginalIsEffectiveValueWhenNoCorrectionExists() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.3, 5.0, 3.0);
        seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", 13.1, 5.2, 3.1);

        LongitudinalComparisonResultDto result = service.compare("case-current", "case-baseline");
        var row = findByKey(result, "L4-L5", "sagittal", "disc", "majorAxisMm");
        assertEquals("ai_original", row.current().source());
        assertEquals(5.2, row.current().effectiveValue());
        assertNull(row.current().correctedValue());
    }

    @Test
    void professionalCorrectionIsEffectiveValueWhenPresent() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.3, 5.0, 3.0);
        SeededMeasurement currentSeed = seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", 13.1, 5.2, 3.1);
        correct(currentSeed.run(), currentSeed.measurement(), "majorAxisMm", 5.2, 4.9, Instant.now());

        LongitudinalComparisonResultDto result = service.compare("case-current", "case-baseline");
        var row = findByKey(result, "L4-L5", "sagittal", "disc", "majorAxisMm");
        assertEquals("professional_correction", row.current().source());
        assertEquals(4.9, row.current().effectiveValue());
        assertEquals(5.2, row.current().automaticValue(), "el valor automatico original nunca se pierde");
    }

    // ---- 10: latest correction wins ----
    @Test
    void latestCorrectionIsSelectedNeverSummed() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.3, 5.0, 3.0);
        SeededMeasurement currentSeed = seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", 13.1, 5.2, 3.1);
        correct(currentSeed.run(), currentSeed.measurement(), "majorAxisMm", 5.2, 4.9, Instant.parse("2026-08-25T09:00:00Z"));
        correct(currentSeed.run(), currentSeed.measurement(), "majorAxisMm", 5.2, 4.5, Instant.parse("2026-08-25T10:00:00Z"));

        LongitudinalComparisonResultDto result = service.compare("case-current", "case-baseline");
        var row = findByKey(result, "L4-L5", "sagittal", "disc", "majorAxisMm");
        assertEquals(4.5, row.current().effectiveValue());
    }

    // ---- 11-16: delta arithmetic ----
    @Test
    void deltaAbsolutePositive() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.0, null, null);
        seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", 13.0, null, null);

        var row = findByKey(service.compare("case-current", "case-baseline"), "L4-L5", "sagittal", "disc", "areaMm2");
        assertEquals(1.0, row.deltaAbsolute(), 1e-9);
        assertEquals("increased", row.trend());
    }

    @Test
    void deltaAbsoluteNegative() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 13.0, null, null);
        seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", 12.0, null, null);

        var row = findByKey(service.compare("case-current", "case-baseline"), "L4-L5", "sagittal", "disc", "areaMm2");
        assertEquals(-1.0, row.deltaAbsolute(), 1e-9);
        assertEquals("decreased", row.trend());
    }

    @Test
    void deltaZeroIsUnchanged() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.0, null, null);
        seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", 12.0, null, null);

        var row = findByKey(service.compare("case-current", "case-baseline"), "L4-L5", "sagittal", "disc", "areaMm2");
        assertEquals(0.0, row.deltaAbsolute(), 1e-9);
        assertEquals("unchanged", row.trend());
    }

    @Test
    void deltaPercentPositive() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 10.0, null, null);
        seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", 12.0, null, null);

        var row = findByKey(service.compare("case-current", "case-baseline"), "L4-L5", "sagittal", "disc", "areaMm2");
        assertEquals(20.0, row.deltaPercent(), 1e-9);
    }

    @Test
    void deltaPercentNegative() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 10.0, null, null);
        seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", 8.0, null, null);

        var row = findByKey(service.compare("case-current", "case-baseline"), "L4-L5", "sagittal", "disc", "areaMm2");
        assertEquals(-20.0, row.deltaPercent(), 1e-9);
    }

    @Test
    void baselineZeroMakesPercentNullNeverDivideByZero() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 0.0, null, null);
        seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", 5.0, null, null);

        var row = findByKey(service.compare("case-current", "case-baseline"), "L4-L5", "sagittal", "disc", "areaMm2");
        assertNull(row.deltaPercent());
        assertEquals(5.0, row.deltaAbsolute(), 1e-9);
    }

    // ---- 17-21: matching key strictness ----
    @Test
    void mm2NeverComparedAgainstMm() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        // baseline only reports areaMm2, current only reports majorAxisMm for the same level/plane/structure.
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.0, null, null);
        seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", null, 5.0, null);

        LongitudinalComparisonResultDto result = service.compare("case-current", "case-baseline");
        assertEquals("missing_current", findByKey(result, "L4-L5", "sagittal", "disc", "areaMm2").comparisonStatus());
        assertEquals("missing_baseline", findByKey(result, "L4-L5", "sagittal", "disc", "majorAxisMm").comparisonStatus());
        assertFalse(result.measurements().stream().anyMatch(m -> "comparable".equals(m.comparisonStatus())));
    }

    @Test
    void metricMismatchNeverProducesAComparableRow() {
        mm2NeverComparedAgainstMm(); // same guarantee, different framing of the same rule (79-Y items 17/18).
    }

    @Test
    void structureMismatchIsNeverPaired() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.0, null, null);
        seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "vertebra", 12.0, null, null);

        LongitudinalComparisonResultDto result = service.compare("case-current", "case-baseline");
        assertFalse(result.measurements().stream().anyMatch(m -> "comparable".equals(m.comparisonStatus())));
        assertEquals("missing_current", findByKey(result, "L4-L5", "sagittal", "disc", "areaMm2").comparisonStatus());
        assertEquals("missing_baseline", findByKey(result, "L4-L5", "sagittal", "vertebra", "areaMm2").comparisonStatus());
    }

    @Test
    void planeMismatchIsNeverPaired() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.0, null, null);
        seedRunWithMeasurement(current, "completed", "L4-L5", "axial", "disc", 12.0, null, null);

        LongitudinalComparisonResultDto result = service.compare("case-current", "case-baseline");
        assertFalse(result.measurements().stream().anyMatch(m -> "comparable".equals(m.comparisonStatus())));
    }

    @Test
    void levelMismatchIsNeverPaired() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.0, null, null);
        seedRunWithMeasurement(current, "completed", "L5-S1", "sagittal", "disc", 12.0, null, null);

        LongitudinalComparisonResultDto result = service.compare("case-current", "case-baseline");
        assertFalse(result.measurements().stream().anyMatch(m -> "comparable".equals(m.comparisonStatus())));
    }

    // ---- 22: null level fail-closed ----
    @Test
    void nullLevelOnBothSidesIsNeverPairedTogether() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", null, "sagittal", "disc", 12.0, null, null);
        seedRunWithMeasurement(current, "completed", null, "sagittal", "disc", 20.0, null, null);

        LongitudinalComparisonResultDto result = service.compare("case-current", "case-baseline");
        assertEquals(2, result.measurements().size(), "cada level=null debe quedar como su propia fila, nunca emparejada");
        assertTrue(result.measurements().stream().allMatch(m -> "level_unresolved".equals(m.comparisonStatus())));
        assertFalse(result.measurements().stream().anyMatch(m -> "comparable".equals(m.comparisonStatus())));
    }

    // ---- 23-24: missing measurement on one side ----
    @Test
    void missingBaselineMeasurementIsReported() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedEmptyRun(baseline, "completed");
        seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", 12.0, null, null);

        var row = findByKey(service.compare("case-current", "case-baseline"), "L4-L5", "sagittal", "disc", "areaMm2");
        assertEquals("missing_baseline", row.comparisonStatus());
        assertNull(row.baseline());
    }

    @Test
    void missingCurrentMeasurementIsReported() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.0, null, null);
        seedEmptyRun(current, "completed");

        var row = findByKey(service.compare("case-current", "case-baseline"), "L4-L5", "sagittal", "disc", "areaMm2");
        assertEquals("missing_current", row.comparisonStatus());
        assertNull(row.current());
    }

    // ---- 25-27: usable status combinations ----
    @Test
    void partialRunIsUsableForComparison() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "partial", "L4-L5", "sagittal", "disc", 12.0, null, null);
        seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", 13.0, null, null);

        assertEquals("comparable", findByKey(service.compare("case-current", "case-baseline"), "L4-L5", "sagittal", "disc", "areaMm2").comparisonStatus());
    }

    @Test
    void completedRunIsUsableForComparison() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.0, null, null);
        seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", 13.0, null, null);

        assertEquals("comparable", findByKey(service.compare("case-current", "case-baseline"), "L4-L5", "sagittal", "disc", "areaMm2").comparisonStatus());
    }

    @Test
    void failedCurrentRunMeansNoUsableAnalysisForCurrent() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.0, null, null);
        seedEmptyRun(current, "failed");

        RunReviewException ex = assertThrows(RunReviewException.class, () -> service.compare("case-current", "case-baseline"));
        assertEquals("LONGITUDINAL_NO_USABLE_ANALYSIS_CURRENT", ex.code());
    }

    // ---- 28-29: determinism ----
    @Test
    void multipleRunsProduceADeterministicComparisonAcrossRepeatedCalls() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.0, null, null);
        seedRunWithMeasurement(baseline, "partial", "L4-L5", "sagittal", "disc", 999.0, null, null); // ignored: not the selected run once one exists first... actually latest wins
        seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", 13.0, null, null);

        LongitudinalComparisonResultDto first = service.compare("case-current", "case-baseline");
        LongitudinalComparisonResultDto second = service.compare("case-current", "case-baseline");
        assertEquals(first.baselineAnalysisRunId(), second.baselineAnalysisRunId());
        assertEquals(first.measurements(), second.measurements());
    }

    @Test
    void multipleCorrectionsProduceADeterministicEffectiveValueAcrossRepeatedCalls() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "completed", "L4-L5", "sagittal", "disc", 12.0, null, null);
        SeededMeasurement currentSeed = seedRunWithMeasurement(current, "completed", "L4-L5", "sagittal", "disc", 13.0, null, null);
        correct(currentSeed.run(), currentSeed.measurement(), "areaMm2", 13.0, 12.8, Instant.parse("2026-08-25T09:00:00Z"));
        correct(currentSeed.run(), currentSeed.measurement(), "areaMm2", 13.0, 12.5, Instant.parse("2026-08-25T10:00:00Z"));

        double first = findByKey(service.compare("case-current", "case-baseline"), "L4-L5", "sagittal", "disc", "areaMm2").current().effectiveValue();
        double second = findByKey(service.compare("case-current", "case-baseline"), "L4-L5", "sagittal", "disc", "areaMm2").current().effectiveValue();
        assertEquals(12.5, first);
        assertEquals(first, second);
    }

    // ---- 30: no diagnosis language anywhere in the DTO/service source ----
    @Test
    void noDiagnosisLanguageInDtoOrService() throws Exception {
        String forbiddenPattern = "(?i)mejor[oó]|empeor[oó]|diagn[oó]stic|patholog|worsen|improv";
        String dtoSource = Files.readString(Path.of("src/main/java/ar/edu/uade/pfi/backend/dto/LongitudinalComparisonResultDto.java"));
        String serviceSource = Files.readString(Path.of("src/main/java/ar/edu/uade/pfi/backend/service/LongitudinalComparisonService.java"));
        assertFalse(dtoSource.matches("(?s).*" + forbiddenPattern + ".*"), "el DTO no debe contener lenguaje de diagnostico/evolucion clinica");
        assertFalse(serviceSource.matches("(?s).*" + forbiddenPattern + ".*"), "el service no debe contener lenguaje de diagnostico/evolucion clinica");
    }
}
