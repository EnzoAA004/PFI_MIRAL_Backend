package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.AiEvidenceRegion;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegionCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.EvidenceGeometry;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** EXT-02: numbers here are hand-computable — every test asserts the exact expected value, not just "some number". */
class ReviewAnalyticsServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");
    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final EffectiveAnnotationResolver annotationResolver = new EffectiveAnnotationResolver(repository);
    private final ReviewAnalyticsService service = new ReviewAnalyticsService(repository, annotationResolver, Clock.fixed(NOW, ZoneOffset.UTC));

    private Study seedStudy(String id, String caseId) {
        Study study = new Study(id, caseId, "registered", "SUBJ-" + id, LocalDate.of(2026, 9, 6), "MRI", "fixture", "medium", NOW, NOW);
        repository.saveStudy(study);
        return study;
    }

    private AiProductAnalysisRun seedRun(Study study, String runId) {
        return new AiProductAnalysisRun(runId, study.id(), study.caseId(), "pfi.ai-product-result.v1", "release-1", "completed", true, true, false, "trace-" + runId, "inp_" + runId, null, List.of(), Map.of(), NOW, NOW);
    }

    @Test
    void countsTotalAutomaticMeasurementsAcrossMetricFields() {
        Study study = seedStudy("study-1", "CASE-1");
        AiProductAnalysisRun run = seedRun(study, "run-1");
        // One row, two populated metrics (majorAxisMm and areaMm2) -> 2 instances.
        repository.saveAiProductAnalysisRun(run, List.of(), List.of(
            new AiMeasurementResult("m-1", run.id(), null, "sagittal", "L4-L5", "disc", 150.0, 12.1, null, true)
        ));

        ReviewAnalyticsReport report = service.computeReport("CASE-1");

        assertEquals(2, report.measurements().totalAutomaticMeasurements());
        assertEquals(0, report.measurements().correctedCount());
    }

    @Test
    void computesMaeMedianAndSignedMeanForACorrectedGroup() {
        Study study = seedStudy("study-2", "CASE-2");
        AiProductAnalysisRun run = seedRun(study, "run-2");
        repository.saveAiProductAnalysisRun(run, List.of(), List.of(
            new AiMeasurementResult("m-2a", run.id(), null, "sagittal", "L4-L5", "disc", null, 12.0, null, true),
            new AiMeasurementResult("m-2b", run.id(), null, "sagittal", "L4-L5", "disc", null, 10.0, null, true)
        ));
        // m-2a: automatic 12.0 -> corrected 12.5 (signed +0.5, abs 0.5)
        // m-2b: automatic 10.0 -> corrected 9.0 (signed -1.0, abs 1.0)
        repository.saveAiMeasurementCorrection(new AiMeasurementCorrection("c-2a", "m-2a", run.id(), "majorAxisMm", 12.0, 12.5, "mm", null, "dr@pfi.local", NOW));
        repository.saveAiMeasurementCorrection(new AiMeasurementCorrection("c-2b", "m-2b", run.id(), "majorAxisMm", 10.0, 9.0, "mm", null, "dr@pfi.local", NOW));

        ReviewAnalyticsReport report = service.computeReport("CASE-2");

        assertEquals(1, report.measurements().groups().size());
        var group = report.measurements().groups().get(0);
        assertEquals(2, group.automaticCount());
        assertEquals(2, group.correctedCount());
        assertEquals(1.0, group.correctionRate());
        assertEquals(0.75, group.meanAbsoluteError(), 1e-9);
        assertEquals(0.75, group.medianAbsoluteError(), 1e-9);
        assertEquals(-0.25, group.signedMeanError(), 1e-9);
    }

    @Test
    void latestCorrectionWinsWhenAMeasurementIsCorrectedTwice() {
        Study study = seedStudy("study-3", "CASE-3");
        AiProductAnalysisRun run = seedRun(study, "run-3");
        repository.saveAiProductAnalysisRun(run, List.of(), List.of(new AiMeasurementResult("m-3", run.id(), null, "sagittal", "L4-L5", "disc", null, 12.0, null, true)));
        repository.saveAiMeasurementCorrection(new AiMeasurementCorrection("c-3a", "m-3", run.id(), "majorAxisMm", 12.0, 12.5, "mm", null, "dr@pfi.local", NOW.minusSeconds(60)));
        repository.saveAiMeasurementCorrection(new AiMeasurementCorrection("c-3b", "m-3", run.id(), "majorAxisMm", 12.0, 13.0, "mm", null, "dr@pfi.local", NOW));

        ReviewAnalyticsReport report = service.computeReport("CASE-3");

        assertEquals(1.0, report.measurements().groups().get(0).signedMeanError(), 1e-9);
    }

    @Test
    void neverMixesAreaAndAxisMetricsInTheSameGroup() {
        Study study = seedStudy("study-4", "CASE-4");
        AiProductAnalysisRun run = seedRun(study, "run-4");
        repository.saveAiProductAnalysisRun(run, List.of(), List.of(new AiMeasurementResult("m-4", run.id(), null, "sagittal", "L4-L5", "disc", 150.0, 12.0, 8.0, true)));

        ReviewAnalyticsReport report = service.computeReport("CASE-4");

        assertEquals(3, report.measurements().groups().size());
        assertTrue(report.measurements().groups().stream().map(g -> g.metric()).toList().containsAll(List.of("areaMm2", "majorAxisMm", "minorAxisMm")));
        assertTrue(report.measurements().groups().stream().noneMatch(g -> g.metric().equals("areaMm2") && g.unit().equals("mm")));
    }

    @Test
    void evidenceRegionCountsAcceptedObservedRejectedAndCorrected() {
        Study study = seedStudy("study-5", "CASE-5");
        AiProductAnalysisRun run = seedRun(study, "run-5");
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());
        repository.saveAiEvidenceRegion(new AiEvidenceRegion("r-accepted", run.id(), null, null, "sagittal", "L4-L5", "disc", 6, "ai", "BBOX", Map.of("x", 10.0, "y", 10.0, "width", 20.0, "height", 20.0), null, "sagittal_spider", null, null, null, false, null, null, null, NOW));
        repository.saveAiEvidenceRegion(new AiEvidenceRegion("r-rejected", run.id(), null, null, "sagittal", "L3-L4", "disc", 4, "ai", "BBOX", Map.of("x", 10.0, "y", 10.0, "width", 20.0, "height", 20.0), null, "sagittal_spider", null, null, null, false, null, null, null, NOW));
        repository.saveAiEvidenceRegion(new AiEvidenceRegion("r-professional", run.id(), null, null, "sagittal", "L5-S1", "disc", 8, "professional", "BBOX", Map.of("x", 10.0, "y", 10.0, "width", 20.0, "height", 20.0), null, null, null, null, null, false, null, null, "dr@pfi.local", NOW));
        repository.upsertAiProductAnalysisRunReview(run.id(), "accepted", null, "dr@pfi.local", NOW);

        ReviewAnalyticsReport report = service.computeReport("CASE-5");

        assertEquals(2, report.evidenceRegions().totalAiRegions());
        assertEquals(2, report.evidenceRegions().accepted());
        assertEquals(1, report.evidenceRegions().professionalDrawnCount());
    }

    @Test
    void geometryDeltaComputesCentroidDisplacementAreaDeltaAndIouForBbox() {
        Study study = seedStudy("study-6", "CASE-6");
        AiProductAnalysisRun run = seedRun(study, "run-6");
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());
        repository.saveAiEvidenceRegion(new AiEvidenceRegion("r-6", run.id(), null, null, "sagittal", "L4-L5", "disc", 6, "ai", "BBOX", Map.of("x", 0.0, "y", 0.0, "width", 10.0, "height", 10.0), null, "sagittal_spider", null, null, null, false, null, null, null, NOW));
        // Original 0,0,10,10 (area 100, center 5,5) -> corrected 5,5,10,10 (area 100, center 10,10)
        repository.saveAiEvidenceRegionCorrection(new AiEvidenceRegionCorrection("corr-6", "r-6", run.id(), "BBOX", Map.of("x", 0.0, "y", 0.0, "width", 10.0, "height", 10.0), "BBOX", Map.of("x", 5.0, "y", 5.0, "width", 10.0, "height", 10.0), null, "dr@pfi.local", NOW));

        ReviewAnalyticsReport report = service.computeReport("CASE-6");

        assertEquals(1, report.evidenceRegions().geometryDeltas().size());
        var delta = report.evidenceRegions().geometryDeltas().get(0);
        assertEquals(Math.hypot(5, 5), delta.centroidDisplacement(), 1e-9);
        assertEquals(0.0, delta.areaDelta(), 1e-9);
        assertTrue(delta.intersectionOverUnion() > 0 && delta.intersectionOverUnion() < 1);
    }

    @Test
    void geometryDeltaIsSkippedWhenShapeChangesOrOriginalIsMissing() {
        Study study = seedStudy("study-7", "CASE-7");
        AiProductAnalysisRun run = seedRun(study, "run-7");
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());
        repository.saveAiEvidenceRegion(new AiEvidenceRegion("r-7", run.id(), null, null, "sagittal", "L4-L5", "disc", 6, "ai", "BBOX", Map.of("x", 0.0, "y", 0.0, "width", 10.0, "height", 10.0), null, "sagittal_spider", null, null, null, false, null, null, null, NOW));
        // Shape-changing correction: BBOX -> ELLIPSE, must be skipped, never forced into a number.
        repository.saveAiEvidenceRegionCorrection(new AiEvidenceRegionCorrection("corr-7", "r-7", run.id(), "BBOX", Map.of("x", 0.0, "y", 0.0, "width", 10.0, "height", 10.0), "ELLIPSE", Map.of("centerX", 5.0, "centerY", 5.0, "radiusX", 5.0, "radiusY", 5.0), null, "dr@pfi.local", NOW));

        ReviewAnalyticsReport report = service.computeReport("CASE-7");

        assertEquals(0, report.evidenceRegions().geometryDeltas().size());
        assertEquals(1, report.evidenceRegions().correctedCount());
    }

    @Test
    void reportsAggregateAcrossAllStudiesWhenCaseIdIsNull() {
        Study studyA = seedStudy("study-8a", "CASE-8A");
        Study studyB = seedStudy("study-8b", "CASE-8B");
        AiProductAnalysisRun runA = seedRun(studyA, "run-8a");
        AiProductAnalysisRun runB = seedRun(studyB, "run-8b");
        repository.saveAiProductAnalysisRun(runA, List.of(), List.of(new AiMeasurementResult("m-8a", runA.id(), null, "sagittal", "L4-L5", "disc", null, 12.0, null, true)));
        repository.saveAiProductAnalysisRun(runB, List.of(), List.of(new AiMeasurementResult("m-8b", runB.id(), null, "sagittal", "L4-L5", "disc", null, 11.0, null, true)));

        ReviewAnalyticsReport report = service.computeReport(null);

        assertEquals(2, report.measurements().totalAutomaticMeasurements());
    }

    @Test
    void limitationsExplicitlyNameTheClassificationGapAndTheIouGap() {
        seedStudy("study-9", "CASE-9");
        ReviewAnalyticsReport report = service.computeReport("CASE-9");
        assertTrue(report.limitations().stream().anyMatch(text -> text.toLowerCase().contains("clasificaci")));
        assertTrue(report.limitations().stream().anyMatch(text -> text.contains("Intersection-over-Union")));
    }

    @Test
    void emptyStudyProducesZeroedReportNotAnException() {
        seedStudy("study-10", "CASE-10");
        ReviewAnalyticsReport report = service.computeReport("CASE-10");
        assertEquals(0, report.measurements().totalAutomaticMeasurements());
        assertEquals(0.0, report.measurements().correctionRate());
        assertEquals(0, report.evidenceRegions().totalAiRegions());
        assertNull(report.measurements().groups().isEmpty() ? null : "should be empty");
    }
}
