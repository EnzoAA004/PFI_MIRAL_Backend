package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.AiEvidenceRegion;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegionCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.EvidenceGeometry;
import ar.edu.uade.pfi.backend.domain.ProfessionalFeedbackCandidate;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** EXT-07D: capture only — never training. */
class ProfessionalFeedbackCaptureServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final ProfessionalFeedbackCaptureService service = new ProfessionalFeedbackCaptureService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void capturesAMeasurementCorrectionPreservingAiOriginalAndProfessionalValue() {
        Study study = seedStudy("study-1", "CASE-FB-1");
        AiProductAnalysisRun run = seedRun(study, "run-1");
        AiMeasurementResult measurement = new AiMeasurementResult("measurement-1", run.id(), null, "sagittal", "L4-L5", "disc", null, 12.1, null, true);
        repository.saveAiProductAnalysisRun(run, List.of(), List.of(measurement));
        repository.saveAiMeasurementCorrection(new AiMeasurementCorrection(
            "correction-1", "measurement-1", run.id(), "majorAxisMm", 12.1, 12.3, "mm",
            "ajuste fino", "dr.reviewer@pfi.local", Instant.parse("2026-09-04T11:00:00Z")
        ));

        List<ProfessionalFeedbackCandidate> captured = service.captureForStudy("CASE-FB-1");

        assertEquals(1, captured.size());
        ProfessionalFeedbackCandidate candidate = captured.get(0);
        assertAll(
            () -> assertEquals("measurement", candidate.source()),
            () -> assertEquals("correction-1", candidate.correctionId()),
            () -> assertEquals("L4-L5", candidate.level()),
            () -> assertEquals("sagittal", candidate.plane()),
            () -> assertEquals("disc", candidate.structure()),
            () -> assertEquals("majorAxisMm", candidate.metric()),
            () -> assertEquals(12.1, candidate.aiOriginalValue()),
            () -> assertEquals(12.3, candidate.professionalValue()),
            () -> assertEquals("dr.reviewer@pfi.local", candidate.reviewedBy()),
            () -> assertEquals("captured", candidate.status()),
            () -> assertEquals(Instant.parse("2026-09-04T11:00:00Z"), candidate.correctedAt())
        );
    }

    @Test
    void captureIsIdempotentAcrossRepeatedCalls() {
        Study study = seedStudy("study-2", "CASE-FB-2");
        AiProductAnalysisRun run = seedRun(study, "run-2");
        repository.saveAiProductAnalysisRun(run, List.of(), List.of(new AiMeasurementResult("measurement-2", run.id(), null, "sagittal", "L4-L5", "disc", null, 12.1, null, true)));
        repository.saveAiMeasurementCorrection(new AiMeasurementCorrection("correction-2", "measurement-2", run.id(), "majorAxisMm", 12.1, 12.3, "mm", null, "dr.reviewer@pfi.local", NOW));

        List<ProfessionalFeedbackCandidate> first = service.captureForStudy("CASE-FB-2");
        List<ProfessionalFeedbackCandidate> second = service.captureForStudy("CASE-FB-2");

        assertEquals(1, first.size());
        assertEquals(0, second.size(), "re-running capture must not duplicate an already-captured correction");
        assertEquals(1, service.findByCase("CASE-FB-2").size());
    }

    @Test
    void capturesAnEvidenceRegionCorrectionPreservingOriginalAndCorrectedGeometryAndModelProvenance() {
        Study study = seedStudy("study-3", "CASE-FB-3");
        AiProductAnalysisRun run = seedRun(study, "run-3");
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());
        Map<String, Object> originalGeometry = new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap();
        Map<String, Object> correctedGeometry = new EvidenceGeometry.Ellipse(20, 20, 8, 6).toMap();
        AiEvidenceRegion region = new AiEvidenceRegion(
            "region-3", run.id(), null, null, "sagittal", "L4-L5", "disc", 10,
            "ai", EvidenceGeometry.TYPE_BBOX, originalGeometry, null,
            "sagittal_spider", "sha256:abc", "pfi.multiplanar-run.v2", null,
            false, null, null, null, Instant.parse("2026-09-04T09:00:00Z")
        );
        repository.saveAiEvidenceRegion(region);
        repository.saveAiEvidenceRegionCorrection(new AiEvidenceRegionCorrection(
            "region-correction-3", "region-3", run.id(), EvidenceGeometry.TYPE_BBOX, originalGeometry,
            EvidenceGeometry.TYPE_ELLIPSE, correctedGeometry, "geometria ajustada", "dr.reviewer@pfi.local",
            Instant.parse("2026-09-04T11:30:00Z")
        ));

        List<ProfessionalFeedbackCandidate> captured = service.captureForStudy("CASE-FB-3");

        assertEquals(1, captured.size());
        ProfessionalFeedbackCandidate candidate = captured.get(0);
        assertAll(
            () -> assertEquals("evidence_region", candidate.source()),
            () -> assertEquals("ELLIPSE", candidate.geometryType()),
            () -> assertEquals(originalGeometry, candidate.aiOriginalGeometry()),
            () -> assertEquals(correctedGeometry, candidate.professionalGeometry()),
            () -> assertEquals("sagittal_spider", candidate.modelId()),
            () -> assertEquals("sha256:abc", candidate.modelSha256()),
            () -> assertEquals("pfi.multiplanar-run.v2", candidate.pipelineVersion())
        );
    }

    @Test
    void capturesAProfessionalAuthoredFindingTheAiNeverSuggested() {
        Study study = seedStudy("study-4", "CASE-FB-4");
        AiProductAnalysisRun run = seedRun(study, "run-4");
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());
        Map<String, Object> geometry = new EvidenceGeometry.Polygon(List.of(
            new EvidenceGeometry.Polygon.Point(0, 0),
            new EvidenceGeometry.Polygon.Point(10, 0),
            new EvidenceGeometry.Polygon.Point(5, 10)
        )).toMap();
        AiEvidenceRegion region = new AiEvidenceRegion(
            "region-4", run.id(), null, null, "sagittal", "L5-S1", "disc", 20,
            "professional", EvidenceGeometry.TYPE_POLYGON, geometry, null,
            null, null, null, null,
            false, null, "hallazgo nuevo no sugerido por IA", "dr.reviewer@pfi.local", Instant.parse("2026-09-04T10:00:00Z")
        );
        repository.saveAiEvidenceRegion(region);

        List<ProfessionalFeedbackCandidate> captured = service.captureForStudy("CASE-FB-4");

        assertEquals(1, captured.size());
        ProfessionalFeedbackCandidate candidate = captured.get(0);
        assertAll(
            () -> assertEquals("professional_new_finding", candidate.source()),
            () -> assertEquals("region-4", candidate.correctionId()),
            () -> assertEquals(geometry, candidate.professionalGeometry()),
            () -> assertEquals(null, candidate.aiOriginalGeometry()),
            () -> assertEquals(null, candidate.modelId(), "no AI was involved in a professional_new_finding"),
            () -> assertEquals("dr.reviewer@pfi.local", candidate.reviewedBy())
        );
    }

    @Test
    void statusStartsAsCapturedAndNeverAutoElevates() {
        Study study = seedStudy("study-5", "CASE-FB-5");
        AiProductAnalysisRun run = seedRun(study, "run-5");
        repository.saveAiProductAnalysisRun(run, List.of(), List.of(new AiMeasurementResult("measurement-5", run.id(), null, "sagittal", "L4-L5", "disc", null, 12.1, null, true)));
        repository.saveAiMeasurementCorrection(new AiMeasurementCorrection("correction-5", "measurement-5", run.id(), "majorAxisMm", 12.1, 12.3, "mm", null, "dr.reviewer@pfi.local", NOW));

        ProfessionalFeedbackCandidate candidate = service.captureForStudy("CASE-FB-5").get(0);

        assertEquals("captured", candidate.status());
    }

    @Test
    void updateStatusRejectsReadyForTrainingAndAnyOtherUnknownValue() {
        Study study = seedStudy("study-6", "CASE-FB-6");
        AiProductAnalysisRun run = seedRun(study, "run-6");
        repository.saveAiProductAnalysisRun(run, List.of(), List.of(new AiMeasurementResult("measurement-6", run.id(), null, "sagittal", "L4-L5", "disc", null, 12.1, null, true)));
        repository.saveAiMeasurementCorrection(new AiMeasurementCorrection("correction-6", "measurement-6", run.id(), "majorAxisMm", 12.1, 12.3, "mm", null, "dr.reviewer@pfi.local", NOW));
        String id = service.captureForStudy("CASE-FB-6").get(0).id();

        assertThrows(IllegalArgumentException.class, () -> service.updateStatus(id, "ready_for_training"));
        assertThrows(IllegalArgumentException.class, () -> service.updateStatus(id, "not_a_real_status"));
    }

    @Test
    void updateStatusAllowsAnExplicitHumanIssuedTransitionToEligibleForCuration() {
        Study study = seedStudy("study-7", "CASE-FB-7");
        AiProductAnalysisRun run = seedRun(study, "run-7");
        repository.saveAiProductAnalysisRun(run, List.of(), List.of(new AiMeasurementResult("measurement-7", run.id(), null, "sagittal", "L4-L5", "disc", null, 12.1, null, true)));
        repository.saveAiMeasurementCorrection(new AiMeasurementCorrection("correction-7", "measurement-7", run.id(), "majorAxisMm", 12.1, 12.3, "mm", null, "dr.reviewer@pfi.local", NOW));
        String id = service.captureForStudy("CASE-FB-7").get(0).id();

        ProfessionalFeedbackCandidate updated = service.updateStatus(id, "eligible_for_curation");

        assertEquals("eligible_for_curation", updated.status());
        assertTrue(service.findByCase("CASE-FB-7").stream().allMatch(c -> "eligible_for_curation".equals(c.status())));
    }

    private Study seedStudy(String id, String caseId) {
        Study study = new Study(id, caseId, "registered", "SUBJ-" + caseId, java.time.LocalDate.of(2026, 9, 4), "MRI", "fixture", "medium", NOW, NOW);
        repository.saveStudy(study);
        return study;
    }

    private AiProductAnalysisRun seedRun(Study study, String runId) {
        AiProductAnalysisRun run = new AiProductAnalysisRun(
            runId, study.id(), study.caseId(), "pfi.ai-product-result.v1", "ext07-fixture", "completed",
            true, true, false, "trace-" + runId, "inp_fixture", null, List.of(), Map.of(), NOW, NOW
        );
        return run;
    }
}
