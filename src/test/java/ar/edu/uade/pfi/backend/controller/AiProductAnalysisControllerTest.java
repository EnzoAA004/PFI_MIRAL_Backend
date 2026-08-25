package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.AiMeasurementCorrectionRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisApiRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisResultDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisReviewRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeResponseDto;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AiAnalysisService;
import ar.edu.uade.pfi.backend.service.AiProductAnalysisMapper;
import ar.edu.uade.pfi.backend.service.AiProductAnalysisPersistenceService;
import ar.edu.uade.pfi.backend.service.AiProductAnalysisReviewService;
import ar.edu.uade.pfi.backend.service.AiProductContractViolationException;
import ar.edu.uade.pfi.backend.service.AiStudyInputResolver;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.RunReviewException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stage 76-M — controller stays thin, delegates input resolution and persistence to
 * {@link AiProductAnalysisPersistenceService}; the Frontend only supplies {@code caseId}.
 */
class AiProductAnalysisControllerTest {
    private static class FakeAi implements AiServiceOperations {
        private final AiProductAnalyzeResponseDto response;
        private final RuntimeException toThrow;

        FakeAi(AiProductAnalyzeResponseDto response) {
            this.response = response;
            this.toThrow = null;
        }

        FakeAi(RuntimeException toThrow) {
            this.response = null;
            this.toThrow = toThrow;
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
            if (toThrow != null) throw toThrow;
            return response;
        }
    }

    private AiProductAnalyzeResponseDto sampleResponse(String status) {
        return new AiProductAnalyzeResponseDto(
            "pfi.ai-product-result.v1",
            "product-1",
            "trace-1",
            "case-1",
            new AiProductAnalyzeResponseDto.Release("post-e50-ai-product-freeze-v1"),
            status,
            null,
            List.of(),
            List.of("67B2_RUNTIME_PARITY_NOT_PROVEN"),
            new AiProductAnalyzeResponseDto.Review(true, "pending", true),
            true,
            false,
            true
        );
    }

    private record Harness(AiProductAnalysisController controller, InMemoryStudyRepository repository) {
    }

    /** Seeds a Study with one analyzable sagittal input so the resolver has something to find. */
    private AiProductAnalysisController buildController(FakeAi fakeAi, String caseId) {
        return buildHarness(fakeAi, caseId).controller();
    }

    private Harness buildHarness(FakeAi fakeAi, String caseId) {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        Instant now = Instant.now();
        Study study = new Study(UUID.randomUUID().toString(), caseId, "registered", now, now);
        repository.saveStudy(study);
        repository.saveInput(new InputResource(
            UUID.randomUUID().toString(), study.id(), "sagittal", "input-sagittal-1", "dicom", 100L,
            "t2_tse_sag", "t2", 20, false, false, true, now
        ));
        AiAnalysisService analysisService = new AiAnalysisService(fakeAi);
        AiStudyInputResolver resolver = new AiStudyInputResolver(repository);
        AiProductAnalysisMapper mapper = new AiProductAnalysisMapper();
        AiProductAnalysisPersistenceService persistenceService = new AiProductAnalysisPersistenceService(analysisService, resolver, repository, mapper);
        AiProductAnalysisReviewService reviewService = new AiProductAnalysisReviewService(repository, persistenceService, null);
        RoleAuthorizationService authorizationService = new RoleAuthorizationService(new AuditService(repository));
        AiProductAnalysisController controller = new AiProductAnalysisController(persistenceService, reviewService, authorizationService);
        return new Harness(controller, repository);
    }

    private HttpServletRequest professionalRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE))
            .thenReturn(new TokenService.Claims("doctor-1", "doctor@example.test", "Doctor Uno", List.of("DOCTOR")));
        return request;
    }

    private HttpServletRequest anonymousRequest() {
        return mock(HttpServletRequest.class);
    }

    @Test
    void completedResponseIsReturnedAsIs() {
        AiProductAnalysisController controller = buildController(new FakeAi(sampleResponse("completed")), "case-1");

        AiProductAnalysisResultDto result = controller.analyze("case-1", new AiProductAnalysisApiRequestDto(null, null, null));

        assertEquals("completed", result.status());
        assertTrue(result.analysisRunId() != null && !result.analysisRunId().isBlank());
    }

    /** Test 2 at the controller layer: partial never becomes a generic 500/error response. */
    @Test
    void partialResponseIsReturnedAsIsNotAsError() {
        AiProductAnalysisController controller = buildController(new FakeAi(sampleResponse("partial")), "case-1");

        AiProductAnalysisResultDto result = controller.analyze("case-1", new AiProductAnalysisApiRequestDto(null, null, null));

        assertEquals("partial", result.status());
        assertEquals(1, result.warnings().size());
    }

    @Test
    void missingBodyDefaultsToEmptyRequest() {
        AiProductAnalysisController controller = buildController(
            new FakeAi(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST)),
            "case-1"
        );

        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.analyze("case-1", null));
    }

    @Test
    void contractViolationPropagatesToBeHandledGlobally() {
        AiProductAnalysisController controller = buildController(
            new FakeAi(new AiProductContractViolationException("ai_product_release_id_mismatch")),
            "case-1"
        );

        assertThrows(AiProductContractViolationException.class, () -> controller.analyze("case-1", new AiProductAnalysisApiRequestDto(null, null, null)));
    }

    @Test
    void nullLevelInDiscLevelsNeverThrowsAtControllerLayer() {
        AiProductAnalyzeResponseDto.DiscLevel abstainedLevel = new AiProductAnalyzeResponseDto.DiscLevel(
            null,
            new AiProductAnalyzeResponseDto.Localization("abstained", true, "67B2", true, false, List.of()),
            new AiProductAnalyzeResponseDto.AxialPairing("not_requested", false, true, true, false, "67C", List.of()),
            List.of(),
            new AiProductAnalyzeResponseDto.DegenerativeFindings("blocked_preprocessing_parity", false, "abc", true)
        );
        AiProductAnalyzeResponseDto response = new AiProductAnalyzeResponseDto(
            "pfi.ai-product-result.v1", "product-1", "trace-1", "case-1",
            new AiProductAnalyzeResponseDto.Release("post-e50-ai-product-freeze-v1"), "partial", null,
            List.of(abstainedLevel), List.of("67B2_localization_abstained"),
            new AiProductAnalyzeResponseDto.Review(true, "pending", true), true, false, true
        );
        AiProductAnalysisController controller = buildController(new FakeAi(response), "case-1");

        AiProductAnalysisResultDto result = controller.analyze("case-1", new AiProductAnalysisApiRequestDto(null, null, null));

        assertNull(result.discLevels().get(0).level());
    }

    @Test
    void historyAndDetailReflectPersistedRun() {
        AiProductAnalysisController controller = buildController(new FakeAi(sampleResponse("completed")), "case-1");

        AiProductAnalysisResultDto created = controller.analyze("case-1", new AiProductAnalysisApiRequestDto(null, null, null));

        var history = controller.history("case-1");
        assertEquals(1, history.analyses().size());
        assertEquals(created.analysisRunId(), history.analyses().get(0).analysisRunId());

        AiProductAnalysisResultDto detail = controller.detail("case-1", created.analysisRunId());
        assertEquals(created.analysisRunId(), detail.analysisRunId());
    }

    @Test
    void reviewRunRequiresProfessionalRole() {
        Harness harness = buildHarness(new FakeAi(sampleResponse("completed")), "case-1");
        AiProductAnalysisResultDto created = harness.controller().analyze("case-1", new AiProductAnalysisApiRequestDto(null, null, null));

        assertThrows(ResponseStatusException.class, () -> harness.controller().reviewRun(
            "case-1", created.analysisRunId(), new AiProductAnalysisReviewRequestDto("accepted", null), anonymousRequest()
        ));
    }

    @Test
    void reviewRunAcceptedIsVisibleInResponse() {
        Harness harness = buildHarness(new FakeAi(sampleResponse("completed")), "case-1");
        AiProductAnalysisResultDto created = harness.controller().analyze("case-1", new AiProductAnalysisApiRequestDto(null, null, null));

        AiProductAnalysisResultDto reviewed = harness.controller().reviewRun(
            "case-1", created.analysisRunId(), new AiProductAnalysisReviewRequestDto("accepted", null), professionalRequest()
        );

        assertEquals("accepted", reviewed.review().status());
        assertEquals("doctor@example.test", reviewed.review().reviewedBy());
    }

    @Test
    void reviewRunInvalidStatusThrows() {
        Harness harness = buildHarness(new FakeAi(sampleResponse("completed")), "case-1");
        AiProductAnalysisResultDto created = harness.controller().analyze("case-1", new AiProductAnalysisApiRequestDto(null, null, null));

        assertThrows(RunReviewException.class, () -> harness.controller().reviewRun(
            "case-1", created.analysisRunId(), new AiProductAnalysisReviewRequestDto("not_a_status", null), professionalRequest()
        ));
    }

    @Test
    void reviewDiscLevelDoesNotAffectRunReview() {
        AiProductAnalyzeResponseDto.DiscLevel level = new AiProductAnalyzeResponseDto.DiscLevel(
            "L4-L5",
            new AiProductAnalyzeResponseDto.Localization("localized", true, "67B2", false, false, List.of()),
            new AiProductAnalyzeResponseDto.AxialPairing("paired", false, true, true, false, "67C", List.of()),
            List.of(),
            null
        );
        AiProductAnalyzeResponseDto response = new AiProductAnalyzeResponseDto(
            "pfi.ai-product-result.v1", "product-1", "trace-1", "case-1",
            new AiProductAnalyzeResponseDto.Release("post-e50-ai-product-freeze-v1"), "completed", null,
            List.of(level), List.of(), new AiProductAnalyzeResponseDto.Review(true, "pending", true), true, false, true
        );
        Harness harness = buildHarness(new FakeAi(response), "case-1");
        AiProductAnalysisResultDto created = harness.controller().analyze("case-1", new AiProductAnalysisApiRequestDto(null, null, null));
        String discLevelId = created.discLevels().get(0).discLevelId();

        AiProductAnalysisResultDto reviewed = harness.controller().reviewDiscLevel(
            "case-1", created.analysisRunId(), discLevelId,
            new AiProductAnalysisReviewRequestDto("accepted", "revisado"), professionalRequest()
        );

        assertEquals("accepted", reviewed.discLevels().get(0).review().status());
        assertEquals("pending", reviewed.review().status());
        // 67C's own AI-output fields are untouched by the professional review.
        assertEquals(false, reviewed.discLevels().get(0).axialPairingValidated());
        assertEquals(true, reviewed.discLevels().get(0).axialPairingExperimental());
    }

    @Test
    void correctMeasurementPreservesOriginalAlongsideCorrected() {
        AiProductAnalyzeResponseDto.Measurement measurement = new AiProductAnalyzeResponseDto.Measurement("sagittal", "L4-L5", "disc", 12.3, 5.0, 3.0, true);
        AiProductAnalyzeResponseDto.DiscLevel level = new AiProductAnalyzeResponseDto.DiscLevel(
            "L4-L5", null, null, List.of(measurement), null
        );
        AiProductAnalyzeResponseDto response = new AiProductAnalyzeResponseDto(
            "pfi.ai-product-result.v1", "product-1", "trace-1", "case-1",
            new AiProductAnalyzeResponseDto.Release("post-e50-ai-product-freeze-v1"), "completed", null,
            List.of(level), List.of(), new AiProductAnalyzeResponseDto.Review(true, "pending", true), true, false, true
        );
        Harness harness = buildHarness(new FakeAi(response), "case-1");
        AiProductAnalysisResultDto created = harness.controller().analyze("case-1", new AiProductAnalysisApiRequestDto(null, null, null));
        String measurementId = created.discLevels().get(0).measurements().get(0).measurementId();

        AiProductAnalysisResultDto corrected = harness.controller().correctMeasurement(
            "case-1", created.analysisRunId(), measurementId,
            new AiMeasurementCorrectionRequestDto("areaMm2", 11.8, "ajuste manual"), professionalRequest()
        );

        AiMeasurementResult stillOriginal = harness.repository().findAiMeasurementResultsByAnalysisRunId(created.analysisRunId()).get(0);
        assertEquals(12.3, stillOriginal.areaMm2());

        var correctionView = corrected.discLevels().get(0).measurements().get(0).corrections().get(0);
        assertEquals(12.3, correctionView.originalValue());
        assertEquals(11.8, correctionView.correctedValue());
        assertEquals("mm2", correctionView.unit());
        // The raw AI value itself must remain visible, not hidden by the correction.
        assertEquals(12.3, corrected.discLevels().get(0).measurements().get(0).areaMm2());
    }

    @Test
    void correctMeasurementRejectsNonFiniteValue() {
        AiProductAnalyzeResponseDto.Measurement measurement = new AiProductAnalyzeResponseDto.Measurement("sagittal", "L4-L5", "disc", 12.3, 5.0, 3.0, true);
        AiProductAnalyzeResponseDto.DiscLevel level = new AiProductAnalyzeResponseDto.DiscLevel("L4-L5", null, null, List.of(measurement), null);
        AiProductAnalyzeResponseDto response = new AiProductAnalyzeResponseDto(
            "pfi.ai-product-result.v1", "product-1", "trace-1", "case-1",
            new AiProductAnalyzeResponseDto.Release("post-e50-ai-product-freeze-v1"), "completed", null,
            List.of(level), List.of(), new AiProductAnalyzeResponseDto.Review(true, "pending", true), true, false, true
        );
        Harness harness = buildHarness(new FakeAi(response), "case-1");
        AiProductAnalysisResultDto created = harness.controller().analyze("case-1", new AiProductAnalysisApiRequestDto(null, null, null));
        String measurementId = created.discLevels().get(0).measurements().get(0).measurementId();

        assertThrows(RunReviewException.class, () -> harness.controller().correctMeasurement(
            "case-1", created.analysisRunId(), measurementId,
            new AiMeasurementCorrectionRequestDto("areaMm2", Double.NaN, null), professionalRequest()
        ));
        assertThrows(RunReviewException.class, () -> harness.controller().correctMeasurement(
            "case-1", created.analysisRunId(), measurementId,
            new AiMeasurementCorrectionRequestDto("areaMm2", Double.POSITIVE_INFINITY, null), professionalRequest()
        ));
    }

    @Test
    void reviewOfAnalysisFromAnotherStudy404s() {
        Harness harness = buildHarness(new FakeAi(sampleResponse("completed")), "case-1");
        AiProductAnalysisResultDto created = harness.controller().analyze("case-1", new AiProductAnalysisApiRequestDto(null, null, null));

        Instant now = Instant.now();
        Study other = new Study(UUID.randomUUID().toString(), "case-2", "registered", now, now);
        harness.repository().saveStudy(other);

        assertThrows(ResponseStatusException.class, () -> harness.controller().reviewRun(
            "case-2", created.analysisRunId(), new AiProductAnalysisReviewRequestDto("accepted", null), professionalRequest()
        ));
    }
}
