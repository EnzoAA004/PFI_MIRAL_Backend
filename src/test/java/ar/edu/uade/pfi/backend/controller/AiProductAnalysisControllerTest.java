package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisApiRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisResultDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeResponseDto;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AiAnalysisService;
import ar.edu.uade.pfi.backend.service.AiProductAnalysisMapper;
import ar.edu.uade.pfi.backend.service.AiProductAnalysisPersistenceService;
import ar.edu.uade.pfi.backend.service.AiProductContractViolationException;
import ar.edu.uade.pfi.backend.service.AiStudyInputResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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

    /** Seeds a Study with one analyzable sagittal input so the resolver has something to find. */
    private AiProductAnalysisController buildController(FakeAi fakeAi, String caseId) {
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
        return new AiProductAnalysisController(persistenceService);
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
}
