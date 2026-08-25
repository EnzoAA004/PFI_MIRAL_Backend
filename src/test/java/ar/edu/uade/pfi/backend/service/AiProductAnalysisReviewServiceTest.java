package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.AiMeasurementCorrectionRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisResultDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeResponseDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisReviewRequestDto;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stage 78: professional review/correction workflow, unit-tested directly against
 * {@link InMemoryStudyRepository} — no DB/FastAPI needed. Covers the 78-P backend test list.
 */
class AiProductAnalysisReviewServiceTest {
    private static class FakeAi implements AiServiceOperations {
        private final String status;
        final AtomicInteger callCount = new AtomicInteger();

        FakeAi(String status) {
            this.status = status;
        }

        @Override
        public Map<String, Object> health() {
            return Map.of();
        }

        @Override
        public Object models() {
            return Map.of();
        }

        @Override
        public Map<String, Object> verifyModels() {
            return Map.of();
        }

        @Override
        public Map<String, Object> warmup() {
            return Map.of();
        }

        @Override
        public Map<String, Object> runPipeline(PipelineRunRequestDto request) {
            return Map.of();
        }

        @Override
        public Map<String, Object> getAgentReport(String runId) {
            return Map.of();
        }

        @Override
        public Map<String, Object> getAgentReportSummary(String runId) {
            return Map.of();
        }

        @Override
        public Map<String, Object> getRecentAgentReports(int limit) {
            return Map.of();
        }

        @Override
        public AiProductAnalyzeResponseDto analyzeProduct(AiProductAnalyzeRequestDto request) {
            callCount.incrementAndGet();
            AiProductAnalyzeResponseDto.Measurement measurement = new AiProductAnalyzeResponseDto.Measurement("sagittal", "L4-L5", "disc", 12.3, 5.0, 3.0, true);
            AiProductAnalyzeResponseDto.DiscLevel level = new AiProductAnalyzeResponseDto.DiscLevel(
                "L4-L5",
                new AiProductAnalyzeResponseDto.Localization("localized", true, "67B2", false, false, List.of()),
                new AiProductAnalyzeResponseDto.AxialPairing("paired", false, true, true, false, "67C", List.of()),
                List.of(measurement),
                new AiProductAnalyzeResponseDto.DegenerativeFindings("blocked_preprocessing_parity", false, "sha-1", true)
            );
            return new AiProductAnalyzeResponseDto(
                "pfi.ai-product-result.v1", "product-1", "trace-1", request.caseId(),
                new AiProductAnalyzeResponseDto.Release("post-e50-ai-product-freeze-v1"), status, null,
                List.of(level), List.of(), new AiProductAnalyzeResponseDto.Review(true, "pending", true), true, false, true
            );
        }
    }

    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-25T12:00:00Z"), ZoneOffset.UTC);

    private void seedStudy(String caseId) {
        Instant now = Instant.now();
        Study study = new Study(UUID.randomUUID().toString(), caseId, "registered", now, now);
        repository.saveStudy(study);
        repository.saveInput(new InputResource(
            UUID.randomUUID().toString(), study.id(), "sagittal", "input-sag-1", "dicom", 100L,
            "series", "t2", 20, false, false, true, now
        ));
    }

    private String runAnalysis(String caseId, FakeAi fakeAi) {
        AiAnalysisService analysisService = new AiAnalysisService(fakeAi);
        AiStudyInputResolver resolver = new AiStudyInputResolver(repository);
        AiProductAnalysisMapper mapper = new AiProductAnalysisMapper();
        AiProductAnalysisPersistenceService persistenceService = new AiProductAnalysisPersistenceService(analysisService, resolver, repository, mapper);
        return persistenceService.analyze(caseId, Map.of()).analysisRunId();
    }

    /** Seeds a fresh Study for {@code caseId} and runs one analysis against it. */
    private String seedStudyAndAnalysis(String caseId, FakeAi fakeAi) {
        seedStudy(caseId);
        return runAnalysis(caseId, fakeAi);
    }

    private AiProductAnalysisReviewService reviewService() {
        AiAnalysisService analysisService = new AiAnalysisService(new FakeAi("completed"));
        AiStudyInputResolver resolver = new AiStudyInputResolver(repository);
        AiProductAnalysisMapper mapper = new AiProductAnalysisMapper();
        AiProductAnalysisPersistenceService persistenceService = new AiProductAnalysisPersistenceService(analysisService, resolver, repository, mapper);
        return new AiProductAnalysisReviewService(repository, persistenceService, null, clock);
    }

    @Test
    void reviewIsPendingInitially() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        AiProductAnalysisPersistenceService persistence = persistenceServiceFor();

        assertEquals("pending", persistence.detail("case-1", analysisId).review().status());
    }

    @Test
    void acceptAnalysisPersistsStatus() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        AiProductAnalysisResultDto result = reviewService().reviewRun("case-1", analysisId, new AiProductAnalysisReviewRequestDto("accepted", null), "doctor-1", "trace-1");
        assertEquals("accepted", result.review().status());
        assertEquals(clock.instant(), result.review().reviewedAt());
    }

    @Test
    void observeAnalysisPersistsStatus() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        AiProductAnalysisResultDto result = reviewService().reviewRun("case-1", analysisId, new AiProductAnalysisReviewRequestDto("observed", "revisar de nuevo"), "doctor-1", "trace-1");
        assertEquals("observed", result.review().status());
    }

    @Test
    void rejectAnalysisPersistsStatus() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        AiProductAnalysisResultDto result = reviewService().reviewRun("case-1", analysisId, new AiProductAnalysisReviewRequestDto("rejected", "no es confiable"), "doctor-1", "trace-1");
        assertEquals("rejected", result.review().status());
    }

    @Test
    void commentIsPersisted() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        AiProductAnalysisResultDto result = reviewService().reviewRun("case-1", analysisId, new AiProductAnalysisReviewRequestDto("observed", "comentario de prueba"), "doctor-1", "trace-1");
        assertEquals("comentario de prueba", result.review().comment());
    }

    @Test
    void invalidStatusIsRejected() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        assertThrows(RunReviewException.class, () -> reviewService().reviewRun("case-1", analysisId, new AiProductAnalysisReviewRequestDto("diagnosed", null), "doctor-1", "trace-1"));
    }

    @Test
    void analysisFromAnotherStudyIsNotFound() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        Instant now = Instant.now();
        repository.saveStudy(new Study(UUID.randomUUID().toString(), "case-2", "registered", now, now));

        assertThrows(ResponseStatusException.class, () -> reviewService().reviewRun("case-2", analysisId, new AiProductAnalysisReviewRequestDto("accepted", null), "doctor-1", "trace-1"));
    }

    @Test
    void nonexistentAnalysisIsNotFound() {
        Instant now = Instant.now();
        repository.saveStudy(new Study(UUID.randomUUID().toString(), "case-1", "registered", now, now));

        assertThrows(ResponseStatusException.class, () -> reviewService().reviewRun("case-1", "does-not-exist", new AiProductAnalysisReviewRequestDto("accepted", null), "doctor-1", "trace-1"));
    }

    @Test
    void discLevelReviewIsIndependentOfRunReview() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        AiProductAnalysisPersistenceService persistence = persistenceServiceFor();
        String discLevelId = persistence.detail("case-1", analysisId).discLevels().get(0).discLevelId();

        AiProductAnalysisResultDto result = reviewService().reviewDiscLevel("case-1", analysisId, discLevelId, new AiProductAnalysisReviewRequestDto("accepted", null), "doctor-1", "trace-1");

        assertEquals("accepted", result.discLevels().get(0).review().status());
        assertEquals("pending", result.review().status());
    }

    @Test
    void discLevelFromAnotherAnalysisIsNotFound() {
        String analysisIdOne = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        String analysisIdTwo = runAnalysis("case-1", new FakeAi("completed"));
        AiProductAnalysisPersistenceService persistence = persistenceServiceFor();
        String discLevelFromOne = persistence.detail("case-1", analysisIdOne).discLevels().get(0).discLevelId();

        assertThrows(ResponseStatusException.class, () -> reviewService().reviewDiscLevel("case-1", analysisIdTwo, discLevelFromOne, new AiProductAnalysisReviewRequestDto("accepted", null), "doctor-1", "trace-1"));
    }

    @Test
    void correctionIsPersisted() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        AiProductAnalysisPersistenceService persistence = persistenceServiceFor();
        String measurementId = persistence.detail("case-1", analysisId).discLevels().get(0).measurements().get(0).measurementId();

        AiProductAnalysisResultDto result = reviewService().correctMeasurement("case-1", analysisId, measurementId, new AiMeasurementCorrectionRequestDto("areaMm2", 11.8, "ajuste"), "doctor-1", "trace-1");

        var correction = result.discLevels().get(0).measurements().get(0).corrections().get(0);
        assertEquals(11.8, correction.correctedValue());
    }

    @Test
    void originalValueIsPreservedAfterCorrection() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        AiProductAnalysisPersistenceService persistence = persistenceServiceFor();
        String measurementId = persistence.detail("case-1", analysisId).discLevels().get(0).measurements().get(0).measurementId();

        reviewService().correctMeasurement("case-1", analysisId, measurementId, new AiMeasurementCorrectionRequestDto("areaMm2", 11.8, null), "doctor-1", "trace-1");

        AiMeasurementResult stillOriginal = repository.findAiMeasurementResultsByAnalysisRunId(analysisId).get(0);
        assertEquals(12.3, stillOriginal.areaMm2());
    }

    @Test
    void correctedValueIsVisibleAlongsideOriginal() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        AiProductAnalysisPersistenceService persistence = persistenceServiceFor();
        String measurementId = persistence.detail("case-1", analysisId).discLevels().get(0).measurements().get(0).measurementId();

        AiProductAnalysisResultDto result = reviewService().correctMeasurement("case-1", analysisId, measurementId, new AiMeasurementCorrectionRequestDto("areaMm2", 11.8, null), "doctor-1", "trace-1");

        var view = result.discLevels().get(0).measurements().get(0);
        assertEquals(12.3, view.areaMm2());
        assertEquals(12.3, view.corrections().get(0).originalValue());
        assertEquals(11.8, view.corrections().get(0).correctedValue());
    }

    @Test
    void nanValueIsRejected() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        AiProductAnalysisPersistenceService persistence = persistenceServiceFor();
        String measurementId = persistence.detail("case-1", analysisId).discLevels().get(0).measurements().get(0).measurementId();

        assertThrows(RunReviewException.class, () -> reviewService().correctMeasurement("case-1", analysisId, measurementId, new AiMeasurementCorrectionRequestDto("areaMm2", Double.NaN, null), "doctor-1", "trace-1"));
        assertTrue(repository.findAiMeasurementCorrectionsByAnalysisRunId(analysisId).isEmpty());
    }

    @Test
    void infinityValueIsRejected() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        AiProductAnalysisPersistenceService persistence = persistenceServiceFor();
        String measurementId = persistence.detail("case-1", analysisId).discLevels().get(0).measurements().get(0).measurementId();

        assertThrows(RunReviewException.class, () -> reviewService().correctMeasurement("case-1", analysisId, measurementId, new AiMeasurementCorrectionRequestDto("areaMm2", Double.POSITIVE_INFINITY, null), "doctor-1", "trace-1"));
    }

    @Test
    void unitIsAlwaysDerivedServerSide() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        AiProductAnalysisPersistenceService persistence = persistenceServiceFor();
        String measurementId = persistence.detail("case-1", analysisId).discLevels().get(0).measurements().get(0).measurementId();

        AiProductAnalysisResultDto areaResult = reviewService().correctMeasurement("case-1", analysisId, measurementId, new AiMeasurementCorrectionRequestDto("areaMm2", 11.8, null), "doctor-1", "trace-1");
        assertEquals("mm2", areaResult.discLevels().get(0).measurements().get(0).corrections().get(0).unit());

        AiProductAnalysisResultDto axisResult = reviewService().correctMeasurement("case-1", analysisId, measurementId, new AiMeasurementCorrectionRequestDto("majorAxisMm", 4.2, null), "doctor-1", "trace-1");
        var majorCorrection = axisResult.discLevels().get(0).measurements().get(0).corrections().stream()
            .filter(c -> c.field().equals("majorAxisMm")).findFirst().orElseThrow();
        assertEquals("mm", majorCorrection.unit());
    }

    @Test
    void acceptingSixtySevenCPairingDoesNotSetValidatedTrue() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        AiProductAnalysisPersistenceService persistence = persistenceServiceFor();
        String discLevelId = persistence.detail("case-1", analysisId).discLevels().get(0).discLevelId();

        AiProductAnalysisResultDto result = reviewService().reviewDiscLevel("case-1", analysisId, discLevelId, new AiProductAnalysisReviewRequestDto("accepted", null), "doctor-1", "trace-1");

        assertFalse(result.discLevels().get(0).axialPairingValidated());
        assertTrue(result.discLevels().get(0).axialPairingExperimental());
        assertEquals("accepted", result.discLevels().get(0).review().status());
    }

    @Test
    void blockedP10_7NeverBecomesANegativeFindingAfterReview() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        AiProductAnalysisPersistenceService persistence = persistenceServiceFor();
        String discLevelId = persistence.detail("case-1", analysisId).discLevels().get(0).discLevelId();

        AiProductAnalysisResultDto result = reviewService().reviewDiscLevel("case-1", analysisId, discLevelId, new AiProductAnalysisReviewRequestDto("accepted", null), "doctor-1", "trace-1");

        assertEquals("blocked_preprocessing_parity", result.discLevels().get(0).degenerativeFindingsStatus());
    }

    @Test
    void reviewSurvivesRepeatedReads() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        reviewService().reviewRun("case-1", analysisId, new AiProductAnalysisReviewRequestDto("accepted", null), "doctor-1", "trace-1");

        AiProductAnalysisPersistenceService persistence = persistenceServiceFor();
        assertEquals("accepted", persistence.detail("case-1", analysisId).review().status());
        assertEquals("accepted", persistence.detail("case-1", analysisId).review().status());
    }

    @Test
    void multipleRunsHaveIndependentReviews() {
        String analysisIdOne = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        String analysisIdTwo = runAnalysis("case-1", new FakeAi("completed"));

        reviewService().reviewRun("case-1", analysisIdOne, new AiProductAnalysisReviewRequestDto("accepted", null), "doctor-1", "trace-1");

        AiProductAnalysisPersistenceService persistence = persistenceServiceFor();
        assertEquals("accepted", persistence.detail("case-1", analysisIdOne).review().status());
        assertEquals("pending", persistence.detail("case-1", analysisIdTwo).review().status());
    }

    @Test
    void xssLikeCommentIsRejectedNotPersisted() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        assertThrows(RunReviewException.class, () -> reviewService().reviewRun(
            "case-1", analysisId, new AiProductAnalysisReviewRequestDto("observed", "<script>alert(1)</script>"), "doctor-1", "trace-1"
        ));
        AiProductAnalysisPersistenceService persistence = persistenceServiceFor();
        assertEquals("pending", persistence.detail("case-1", analysisId).review().status());
    }

    @Test
    void invalidCorrectionLeavesNoPartialState() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        AiProductAnalysisPersistenceService persistence = persistenceServiceFor();
        String measurementId = persistence.detail("case-1", analysisId).discLevels().get(0).measurements().get(0).measurementId();

        assertThrows(RunReviewException.class, () -> reviewService().correctMeasurement("case-1", analysisId, measurementId, new AiMeasurementCorrectionRequestDto("not_a_field", 1.0, null), "doctor-1", "trace-1"));

        assertTrue(repository.findAiMeasurementCorrectionsByAnalysisRunId(analysisId).isEmpty());
    }

    @Test
    void noAiCallHappensDuringReviewOrCorrection() {
        FakeAi fakeAi = new FakeAi("completed");
        String analysisId = seedStudyAndAnalysis("case-1", fakeAi);
        assertEquals(1, fakeAi.callCount.get());

        AiProductAnalysisPersistenceService persistence = persistenceServiceFor();
        String measurementId = persistence.detail("case-1", analysisId).discLevels().get(0).measurements().get(0).measurementId();
        reviewService().reviewRun("case-1", analysisId, new AiProductAnalysisReviewRequestDto("accepted", null), "doctor-1", "trace-1");
        reviewService().correctMeasurement("case-1", analysisId, measurementId, new AiMeasurementCorrectionRequestDto("areaMm2", 11.8, null), "doctor-1", "trace-1");

        assertEquals(1, fakeAi.callCount.get());
    }

    @Test
    void reviewerIsNullableWhenNoAuthenticatedIdentity() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        AiProductAnalysisResultDto result = reviewService().reviewRun("case-1", analysisId, new AiProductAnalysisReviewRequestDto("accepted", null), null, "trace-1");
        assertNull(result.review().reviewedBy());
    }

    @Test
    void getReturnsTheReview() {
        String analysisId = seedStudyAndAnalysis("case-1", new FakeAi("completed"));
        reviewService().reviewRun("case-1", analysisId, new AiProductAnalysisReviewRequestDto("observed", "seguimiento"), "doctor-1", "trace-1");

        AiProductAnalysisPersistenceService persistence = persistenceServiceFor();
        AiProductAnalysisResultDto fetched = persistence.detail("case-1", analysisId);
        assertEquals("observed", fetched.review().status());
        assertEquals("seguimiento", fetched.review().comment());
        assertEquals("doctor-1", fetched.review().reviewedBy());
    }

    private AiProductAnalysisPersistenceService persistenceServiceFor() {
        AiAnalysisService analysisService = new AiAnalysisService(new FakeAi("completed"));
        AiStudyInputResolver resolver = new AiStudyInputResolver(repository);
        AiProductAnalysisMapper mapper = new AiProductAnalysisMapper();
        return new AiProductAnalysisPersistenceService(analysisService, resolver, repository, mapper);
    }
}
