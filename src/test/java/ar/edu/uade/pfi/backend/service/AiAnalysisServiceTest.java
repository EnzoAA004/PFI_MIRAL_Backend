package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeResponseDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AiAnalysisServiceTest {
    private static class FakeAi implements AiServiceOperations {
        final String status;
        AiProductAnalyzeRequestDto capturedRequest;

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
        public Map<String, Object> runPipeline(ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto request) {
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
            this.capturedRequest = request;
            return new AiProductAnalyzeResponseDto(
                "pfi.ai-product-result.v1",
                "product-1",
                "trace-1",
                request.caseId(),
                new AiProductAnalyzeResponseDto.Release("post-e50-ai-product-freeze-v1"),
                status,
                null,
                List.of(),
                List.of(),
                new AiProductAnalyzeResponseDto.Review(true, "pending", true),
                true,
                false,
                true
            );
        }
    }

    /** Test 2: partial is returned as-is, never converted to a generic error. */
    @Test
    void partialIsReturnedNotThrown() {
        AiAnalysisService service = new AiAnalysisService(new FakeAi("partial"));

        AiProductAnalyzeResponseDto response = service.analyze("case-1", "input-sagittal-1", null, Map.of());

        assertEquals("partial", response.status());
    }

    /** Test 3: failed is represented in the response, not thrown as an exception (only real upstream/contract failures throw). */
    @Test
    void failedStatusIsRepresentedNotThrown() {
        AiAnalysisService service = new AiAnalysisService(new FakeAi("failed"));

        AiProductAnalyzeResponseDto response = service.analyze("case-1", "input-sagittal-1", null, Map.of());

        assertEquals("failed", response.status());
    }

    @Test
    void completedIsReturnedAsIs() {
        AiAnalysisService service = new AiAnalysisService(new FakeAi("completed"));

        AiProductAnalyzeResponseDto response = service.analyze("case-1", "input-sagittal-1", "input-axial-1", Map.of());

        assertEquals("completed", response.status());
    }

    @Test
    void requiresAtLeastOneInputId() {
        AiAnalysisService service = new AiAnalysisService(new FakeAi("completed"));

        assertThrows(ResponseStatusException.class, () -> service.analyze("case-1", null, "  ", Map.of()));
    }

    @Test
    void blankInputIdsAreNormalizedToNullBeforeCallingTheClient() {
        FakeAi fakeAi = new FakeAi("completed");
        AiAnalysisService service = new AiAnalysisService(fakeAi);

        service.analyze("case-1", "  input-sagittal-1  ", "   ", Map.of());

        assertEquals("input-sagittal-1", fakeAi.capturedRequest.sagittalInputId());
        assertEquals(null, fakeAi.capturedRequest.axialInputId());
    }
}
