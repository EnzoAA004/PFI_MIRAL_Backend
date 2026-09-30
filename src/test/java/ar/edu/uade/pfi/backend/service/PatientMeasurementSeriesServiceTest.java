package ar.edu.uade.pfi.backend.service;
import ar.edu.uade.pfi.backend.service.exception.StudyMetadataException;

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
import ar.edu.uade.pfi.backend.dto.MeasurementSeriesDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Stage 84-I through 84-N: N-Study measurement series, built on top of the exact Stage-79
 * {@code EffectiveAnalysisRunSelector}/{@code EffectiveMeasurementResolver} logic — never a second
 * matching-key or effective-value rule.
 */
class PatientMeasurementSeriesServiceTest {
    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final EffectiveAnalysisRunSelector selector = new EffectiveAnalysisRunSelector(repository);
    private final EffectiveMeasurementResolver resolver = new EffectiveMeasurementResolver(repository);
    private final PatientMeasurementSeriesService service = new PatientMeasurementSeriesService(repository, selector, resolver);

    private Study seedStudy(String caseId, String subjectRef, LocalDate studyDate) {
        Instant now = Instant.now();
        Study study = new Study(UUID.randomUUID().toString(), caseId, "registered", subjectRef, studyDate, "MRI", "desc", "medium", now, now);
        repository.saveStudy(study);
        return study;
    }

    private record SeededRun(AiProductAnalysisRun run, AiMeasurementResult measurement) {
    }

    private SeededRun seedRunWithMeasurement(Study study, String status, String level, String plane, String structure, Double areaMm2, Double majorAxisMm, Double minorAxisMm) {
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
        return new SeededRun(run, measurement);
    }

    private void correct(AiProductAnalysisRun run, AiMeasurementResult measurement, String field, Double originalValue, double correctedValue, Instant createdAt) {
        repository.saveAiMeasurementCorrection(new AiMeasurementCorrection(
            UUID.randomUUID().toString(), measurement.id(), run.id(), field, originalValue, correctedValue,
            "areaMm2".equals(field) ? "mm2" : "mm", null, "doctor-1", createdAt
        ));
    }

    // ---- 17-19: series with 2/3+ studies ----
    @Test
    void seriesWithTwoStudiesReturnsTwoPointsInChronologicalOrder() {
        Study a = seedStudy("case-a", "PAT-84-1", LocalDate.of(2026, 2, 1));
        Study b = seedStudy("case-b", "PAT-84-1", LocalDate.of(2026, 5, 10));
        seedRunWithMeasurement(a, "completed", "L4-L5", "sagittal", "disc", 12.3, 11.9, 8.0);
        seedRunWithMeasurement(b, "completed", "L4-L5", "sagittal", "disc", 12.3, 12.3, 8.0);

        MeasurementSeriesDto series = service.series("PAT-84-1", "L4-L5", "sagittal", "disc", "majorAxisMm");
        assertEquals(2, series.points().size());
        assertEquals("case-a", series.points().get(0).caseId());
        assertEquals("case-b", series.points().get(1).caseId());
        assertEquals(11.9, series.points().get(0).effectiveValue());
        assertEquals(12.3, series.points().get(1).effectiveValue());
    }

    @Test
    void seriesWithThreeStudiesIncludesTheCorrectedThirdPoint() {
        Study a = seedStudy("case-a", "PAT-84-2", LocalDate.of(2026, 2, 1));
        Study b = seedStudy("case-b", "PAT-84-2", LocalDate.of(2026, 5, 10));
        Study c = seedStudy("case-c", "PAT-84-2", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(a, "completed", "L4-L5", "sagittal", "disc", 12.3, 11.9, 8.0);
        seedRunWithMeasurement(b, "completed", "L4-L5", "sagittal", "disc", 12.3, 12.3, 8.0);
        var runC = seedRunWithMeasurement(c, "partial", "L4-L5", "sagittal", "disc", 12.3, 13.1, 8.0);
        correct(runC.run(), runC.measurement(), "majorAxisMm", 13.1, 12.9, Instant.parse("2026-08-25T10:00:00Z"));

        MeasurementSeriesDto series = service.series("PAT-84-2", "L4-L5", "sagittal", "disc", "majorAxisMm");
        assertEquals(3, series.points().size());
        var lastPoint = series.points().get(2);
        assertEquals("case-c", lastPoint.caseId());
        assertEquals(13.1, lastPoint.automaticValue()); // 21: original preserved
        assertEquals(12.9, lastPoint.correctedValue());
        assertEquals(12.9, lastPoint.effectiveValue()); // 20: corrected effective value
        assertEquals("professional_correction", lastPoint.effectiveSource());
    }

    // ---- 19/22: effective AI value / latest correction wins ----
    @Test
    void aiOriginalValueUsedWhenNoCorrectionExists() {
        Study a = seedStudy("case-a", "PAT-84-3", LocalDate.of(2026, 2, 1));
        Study b = seedStudy("case-b", "PAT-84-3", LocalDate.of(2026, 5, 10));
        seedRunWithMeasurement(a, "completed", "L4-L5", "sagittal", "disc", 12.3, 11.9, 8.0);
        seedRunWithMeasurement(b, "completed", "L4-L5", "sagittal", "disc", 12.3, 12.3, 8.0);

        MeasurementSeriesDto series = service.series("PAT-84-3", "L4-L5", "sagittal", "disc", "majorAxisMm");
        assertEquals("ai_original", series.points().get(0).effectiveSource());
        assertNull(series.points().get(0).correctedValue());
    }

    @Test
    void latestCorrectionWinsOverAnEarlierOne() {
        Study a = seedStudy("case-a", "PAT-84-4", LocalDate.of(2026, 8, 25));
        var runA = seedRunWithMeasurement(a, "completed", "L4-L5", "sagittal", "disc", 12.3, 13.1, 8.0);
        correct(runA.run(), runA.measurement(), "majorAxisMm", 13.1, 12.5, Instant.parse("2026-08-25T09:00:00Z"));
        correct(runA.run(), runA.measurement(), "majorAxisMm", 13.1, 12.9, Instant.parse("2026-08-25T10:00:00Z"));
        Study b = seedStudy("case-b", "PAT-84-4", LocalDate.of(2026, 9, 1));
        seedRunWithMeasurement(b, "completed", "L4-L5", "sagittal", "disc", 12.3, 13.0, 8.0);

        MeasurementSeriesDto series = service.series("PAT-84-4", "L4-L5", "sagittal", "disc", "majorAxisMm");
        assertEquals(12.9, series.points().get(0).effectiveValue());
    }

    // ---- 23: unit preserved ----
    @Test
    void unitIsPreservedOnTheSeriesKey() {
        Study a = seedStudy("case-a", "PAT-84-5", LocalDate.of(2026, 2, 1));
        Study b = seedStudy("case-b", "PAT-84-5", LocalDate.of(2026, 5, 10));
        seedRunWithMeasurement(a, "completed", "L4-L5", "sagittal", "disc", 12.3, 11.9, 8.0);
        seedRunWithMeasurement(b, "completed", "L4-L5", "sagittal", "disc", 12.9, 12.3, 8.0);

        MeasurementSeriesDto areaSeries = service.series("PAT-84-5", "L4-L5", "sagittal", "disc", "areaMm2");
        assertEquals("mm2", areaSeries.key().unit());
        MeasurementSeriesDto axisSeries = service.series("PAT-84-5", "L4-L5", "sagittal", "disc", "majorAxisMm");
        assertEquals("mm", axisSeries.key().unit());
    }

    // ---- 24/25: level mismatch / null level excluded ----
    @Test
    void differentLevelIsExcludedFromTheSeries() {
        Study a = seedStudy("case-a", "PAT-84-6", LocalDate.of(2026, 2, 1));
        Study b = seedStudy("case-b", "PAT-84-6", LocalDate.of(2026, 5, 10));
        seedRunWithMeasurement(a, "completed", "L4-L5", "sagittal", "disc", 12.3, 11.9, 8.0);
        seedRunWithMeasurement(b, "completed", "L3-L4", "sagittal", "disc", 12.3, 12.3, 8.0);

        MeasurementSeriesDto series = service.series("PAT-84-6", "L4-L5", "sagittal", "disc", "majorAxisMm");
        assertEquals(1, series.points().size());
        assertEquals("case-a", series.points().get(0).caseId());
    }

    @Test
    void nullLevelMeasurementsAreNeverIncludedInAnySeries() {
        Study a = seedStudy("case-a", "PAT-84-7", LocalDate.of(2026, 2, 1));
        seedRunWithMeasurement(a, "completed", null, "sagittal", "disc", 12.3, 11.9, 8.0);

        MeasurementSeriesDto series = service.series("PAT-84-7", null, "sagittal", "disc", "majorAxisMm");
        assertTrue(series.points().isEmpty());
        // Also never surfaces as an available key (84-K).
        assertTrue(service.availableSeriesKeys("PAT-84-7").isEmpty());
    }

    // ---- 26/28: metric mismatch / plane mismatch excluded ----
    @Test
    void differentMetricIsExcludedFromTheSeries() {
        Study a = seedStudy("case-a", "PAT-84-8", LocalDate.of(2026, 2, 1));
        seedRunWithMeasurement(a, "completed", "L4-L5", "sagittal", "disc", 12.3, 11.9, 8.0);

        MeasurementSeriesDto series = service.series("PAT-84-8", "L4-L5", "sagittal", "disc", "minorAxisMm");
        assertEquals(1, series.points().size());
        assertEquals(8.0, series.points().get(0).effectiveValue());
        // areaMm2/majorAxisMm never leak into the minorAxisMm series.
        assertFalse(series.points().stream().anyMatch(p -> p.effectiveValue() == 11.9));
    }

    @Test
    void differentPlaneIsExcludedFromTheSeries() {
        Study a = seedStudy("case-a", "PAT-84-9", LocalDate.of(2026, 2, 1));
        Study b = seedStudy("case-b", "PAT-84-9", LocalDate.of(2026, 5, 10));
        seedRunWithMeasurement(a, "completed", "L4-L5", "sagittal", "disc", 12.3, 11.9, 8.0);
        seedRunWithMeasurement(b, "completed", "L4-L5", "axial", "disc", 12.3, 12.3, 8.0);

        MeasurementSeriesDto series = service.series("PAT-84-9", "L4-L5", "sagittal", "disc", "majorAxisMm");
        assertEquals(1, series.points().size());
    }

    // ---- 28 (structure) ----
    @Test
    void differentStructureIsExcludedFromTheSeries() {
        Study a = seedStudy("case-a", "PAT-84-10", LocalDate.of(2026, 2, 1));
        Study b = seedStudy("case-b", "PAT-84-10", LocalDate.of(2026, 5, 10));
        seedRunWithMeasurement(a, "completed", "L4-L5", "sagittal", "disc", 12.3, 11.9, 8.0);
        seedRunWithMeasurement(b, "completed", "L4-L5", "sagittal", "canal", 12.3, 12.3, 8.0);

        MeasurementSeriesDto series = service.series("PAT-84-10", "L4-L5", "sagittal", "disc", "majorAxisMm");
        assertEquals(1, series.points().size());
    }

    // ---- 30/31: same-patient guard / different patient excluded ----
    @Test
    void differentSubjectRefIsExcludedFromTheSeries() {
        Study a = seedStudy("case-a", "PAT-84-11", LocalDate.of(2026, 2, 1));
        Study other = seedStudy("case-other", "PAT-84-OTHER", LocalDate.of(2026, 5, 10));
        seedRunWithMeasurement(a, "completed", "L4-L5", "sagittal", "disc", 12.3, 11.9, 8.0);
        seedRunWithMeasurement(other, "completed", "L4-L5", "sagittal", "disc", 12.3, 20.0, 8.0);

        MeasurementSeriesDto series = service.series("PAT-84-11", "L4-L5", "sagittal", "disc", "majorAxisMm");
        assertEquals(1, series.points().size());
        assertEquals("case-a", series.points().get(0).caseId());
    }

    @Test
    void nullSubjectRefStudyNeverFormsASeriesWithAnything() {
        Study noSubject1 = seedStudy("case-1", null, LocalDate.of(2026, 2, 1));
        Study noSubject2 = seedStudy("case-2", null, LocalDate.of(2026, 5, 10));
        seedRunWithMeasurement(noSubject1, "completed", "L4-L5", "sagittal", "disc", 12.3, 11.9, 8.0);
        seedRunWithMeasurement(noSubject2, "completed", "L4-L5", "sagittal", "disc", 12.3, 12.3, 8.0);

        assertThrows(StudyMetadataException.class, () -> service.series(null, "L4-L5", "sagittal", "disc", "majorAxisMm"));
    }

    // ---- 32: no diagnosis/trend output ----
    @Test
    void seriesDtoNeverExposesAClinicalTrendField() {
        for (var field : MeasurementSeriesDto.Point.class.getRecordComponents()) {
            String name = field.getName().toLowerCase();
            assertFalse(name.contains("trend"));
            assertFalse(name.contains("improv"));
            assertFalse(name.contains("worse"));
            assertFalse(name.contains("diagnos"));
        }
    }

    // ---- 33: stable ordering same date ----
    @Test
    void sameStudyDateOrdersDeterministicallyByCaseId() {
        LocalDate sameDate = LocalDate.of(2026, 6, 1);
        Study b = seedStudy("case-b", "PAT-84-12", sameDate);
        Study a = seedStudy("case-a", "PAT-84-12", sameDate);
        seedRunWithMeasurement(b, "completed", "L4-L5", "sagittal", "disc", 12.3, 10.0, 8.0);
        seedRunWithMeasurement(a, "completed", "L4-L5", "sagittal", "disc", 12.3, 20.0, 8.0);

        MeasurementSeriesDto series = service.series("PAT-84-12", "L4-L5", "sagittal", "disc", "majorAxisMm");
        assertEquals("case-a", series.points().get(0).caseId());
        assertEquals("case-b", series.points().get(1).caseId());
    }

    @Test
    void nullStudyDateSortsFirstDeterministically() {
        Study noDate = seedStudy("case-nodate", "PAT-84-13", null);
        Study withDate = seedStudy("case-withdate", "PAT-84-13", LocalDate.of(2026, 6, 1));
        seedRunWithMeasurement(noDate, "completed", "L4-L5", "sagittal", "disc", 12.3, 10.0, 8.0);
        seedRunWithMeasurement(withDate, "completed", "L4-L5", "sagittal", "disc", 12.3, 20.0, 8.0);

        MeasurementSeriesDto series = service.series("PAT-84-13", "L4-L5", "sagittal", "disc", "majorAxisMm");
        assertEquals("case-nodate", series.points().get(0).caseId());
        assertEquals("case-withdate", series.points().get(1).caseId());
    }

    // ---- 9/12: Study without analysis / failed run excluded ----
    @Test
    void studyWithoutAnAnalysisRunContributesNoPointButDoesNotError() {
        seedStudy("case-a", "PAT-84-14", LocalDate.of(2026, 2, 1));
        Study b = seedStudy("case-b", "PAT-84-14", LocalDate.of(2026, 5, 10));
        seedRunWithMeasurement(b, "completed", "L4-L5", "sagittal", "disc", 12.3, 12.3, 8.0);

        MeasurementSeriesDto series = service.series("PAT-84-14", "L4-L5", "sagittal", "disc", "majorAxisMm");
        assertEquals(1, series.points().size());
        assertEquals("case-b", series.points().get(0).caseId());
    }

    @Test
    void failedRunIsNeverSelectedAsEffectiveForASeriesPoint() {
        Study a = seedStudy("case-a", "PAT-84-15", LocalDate.of(2026, 2, 1));
        seedRunWithMeasurement(a, "failed", "L4-L5", "sagittal", "disc", 12.3, 99.0, 8.0);

        MeasurementSeriesDto series = service.series("PAT-84-15", "L4-L5", "sagittal", "disc", "majorAxisMm");
        assertTrue(series.points().isEmpty());
    }

    // ---- 10/11: completed / partial effective run both usable ----
    @Test
    void partialRunIsUsableAsAnEffectiveSeriesPoint() {
        Study a = seedStudy("case-a", "PAT-84-16", LocalDate.of(2026, 2, 1));
        seedRunWithMeasurement(a, "partial", "L4-L5", "sagittal", "disc", 12.3, 11.9, 8.0);

        MeasurementSeriesDto series = service.series("PAT-84-16", "L4-L5", "sagittal", "disc", "majorAxisMm");
        assertEquals(1, series.points().size());
    }

    // ---- 84-M: available series keys, >=2 points threshold ----
    @Test
    void availableSeriesKeysOnlyIncludesKeysWithAtLeastTwoComparablePoints() {
        Study a = seedStudy("case-a", "PAT-84-17", LocalDate.of(2026, 2, 1));
        Study b = seedStudy("case-b", "PAT-84-17", LocalDate.of(2026, 5, 10));
        // L4-L5/majorAxisMm has 2 points; L3-L4 only appears once.
        seedRunWithMeasurement(a, "completed", "L4-L5", "sagittal", "disc", 12.3, 11.9, 8.0);
        var runB = seedRunWithMeasurement(b, "completed", "L4-L5", "sagittal", "disc", 12.3, 12.3, 8.0);

        List<MeasurementSeriesDto.MeasurementSeriesKeyDto> keys = service.availableSeriesKeys("PAT-84-17");
        assertTrue(keys.stream().anyMatch(k -> "L4-L5".equals(k.level()) && "majorAxisMm".equals(k.metric())));
        assertTrue(keys.stream().anyMatch(k -> "areaMm2".equals(k.metric())));
    }

    @Test
    void availableSeriesKeysIsEmptyWithOnlyOneStudy() {
        Study a = seedStudy("case-a", "PAT-84-18", LocalDate.of(2026, 2, 1));
        seedRunWithMeasurement(a, "completed", "L4-L5", "sagittal", "disc", 12.3, 11.9, 8.0);

        assertTrue(service.availableSeriesKeys("PAT-84-18").isEmpty());
    }

    @Test
    void availableSeriesKeysCarriesTheCorrectUnitPerMetric() {
        Study a = seedStudy("case-a", "PAT-84-19", LocalDate.of(2026, 2, 1));
        Study b = seedStudy("case-b", "PAT-84-19", LocalDate.of(2026, 5, 10));
        seedRunWithMeasurement(a, "completed", "L4-L5", "sagittal", "disc", 12.3, 11.9, 8.0);
        seedRunWithMeasurement(b, "completed", "L4-L5", "sagittal", "disc", 12.9, 12.3, 8.0);

        var areaKey = service.availableSeriesKeys("PAT-84-19").stream().filter(k -> "areaMm2".equals(k.metric())).findFirst().orElseThrow();
        assertEquals("mm2", areaKey.unit());
    }

    // ---- fail-closed subjectRef validation ----
    @Test
    void invalidSubjectRefIsRejectedNotSilentlyEmpty() {
        assertThrows(StudyMetadataException.class, () -> service.series("../etc/passwd", "L4-L5", "sagittal", "disc", "majorAxisMm"));
        assertThrows(StudyMetadataException.class, () -> service.availableSeriesKeys(""));
    }

    @Test
    void subjectRefIsTrimmedBeforeLookup() {
        Study a = seedStudy("case-a", "PAT-84-20", LocalDate.of(2026, 2, 1));
        Study b = seedStudy("case-b", "PAT-84-20", LocalDate.of(2026, 5, 10));
        seedRunWithMeasurement(a, "completed", "L4-L5", "sagittal", "disc", 12.3, 11.9, 8.0);
        seedRunWithMeasurement(b, "completed", "L4-L5", "sagittal", "disc", 12.3, 12.3, 8.0);

        MeasurementSeriesDto series = service.series("  PAT-84-20  ", "L4-L5", "sagittal", "disc", "majorAxisMm");
        assertEquals(2, series.points().size());
    }

    @Test
    void subjectRefIsCaseInsensitive() {
        Study a = seedStudy("case-a", "PAT-84-21", LocalDate.of(2026, 2, 1));
        Study b = seedStudy("case-b", "pat-84-21", LocalDate.of(2026, 5, 10));
        seedRunWithMeasurement(a, "completed", "L4-L5", "sagittal", "disc", 12.3, 11.9, 8.0);
        seedRunWithMeasurement(b, "completed", "L4-L5", "sagittal", "disc", 12.3, 12.3, 8.0);

        MeasurementSeriesDto series = service.series("PAT-84-21", "L4-L5", "sagittal", "disc", "majorAxisMm");
        assertEquals(2, series.points().size());
    }
}
