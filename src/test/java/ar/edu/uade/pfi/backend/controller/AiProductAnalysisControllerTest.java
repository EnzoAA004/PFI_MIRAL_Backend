package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisApiRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeResponseDto;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import ar.edu.uade.pfi.backend.service.AiAnalysisService;
import ar.edu.uade.pfi.backend.service.AiProductContractViolationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Stage 75-H — controller stays thin, delegates all AI-response interpretation to {@link AiAnalysisService}. */
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

    @Test
    void completedResponseIsReturnedAsIs() {
        AiProductAnalysisController controller = new AiProductAnalysisController(new AiAnalysisService(new FakeAi(sampleResponse("completed"))));

        AiProductAnalyzeResponseDto response = controller.analyze("case-1", new AiProductAnalysisApiRequestDto("input-sagittal-1", null, null));

        assertEquals("completed", response.status());
    }

    /** Test 2 at the controller layer: partial never becomes a generic 500/error response. */
    @Test
    void partialResponseIsReturnedAsIsNotAsError() {
        AiProductAnalysisController controller = new AiProductAnalysisController(new AiAnalysisService(new FakeAi(sampleResponse("partial"))));

        AiProductAnalyzeResponseDto response = controller.analyze("case-1", new AiProductAnalysisApiRequestDto("input-sagittal-1", null, null));

        assertEquals("partial", response.status());
        assertEquals(1, response.warnings().size());
    }

    @Test
    void missingBodyDefaultsToEmptyRequest() {
        AiProductAnalysisController controller = new AiProductAnalysisController(new AiAnalysisService(new FakeAi(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST))));

        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.analyze("case-1", null));
    }

    @Test
    void contractViolationPropagatesToBeHandledGlobally() {
        AiProductAnalysisController controller = new AiProductAnalysisController(
            new AiAnalysisService(new FakeAi(new AiProductContractViolationException("ai_product_release_id_mismatch")))
        );

        assertThrows(AiProductContractViolationException.class, () -> controller.analyze("case-1", new AiProductAnalysisApiRequestDto("input-sagittal-1", null, null)));
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
        AiProductAnalysisController controller = new AiProductAnalysisController(new AiAnalysisService(new FakeAi(response)));

        AiProductAnalyzeResponseDto result = controller.analyze("case-1", new AiProductAnalysisApiRequestDto("input-sagittal-1", null, null));

        assertNull(result.discLevels().get(0).level());
    }
}
