package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.client.LlmChatRequest;
import ar.edu.uade.pfi.backend.client.LlmChatResult;
import ar.edu.uade.pfi.backend.client.LlmProvider;
import ar.edu.uade.pfi.backend.config.LlmProperties;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeResponseDto;
import ar.edu.uade.pfi.backend.dto.ChatRequestDto;
import ar.edu.uade.pfi.backend.dto.ChatResponseDto;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AiAnalysisService;
import ar.edu.uade.pfi.backend.service.AiProductAnalysisMapper;
import ar.edu.uade.pfi.backend.service.AiProductAnalysisPersistenceService;
import ar.edu.uade.pfi.backend.service.AiStudyInputResolver;
import ar.edu.uade.pfi.backend.service.ChatService;
import ar.edu.uade.pfi.backend.service.EffectiveAnalysisRunSelector;
import ar.edu.uade.pfi.backend.service.LongitudinalComparisonService;
import ar.edu.uade.pfi.backend.service.RunReviewException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Stage 80: the controller stays thin — logic itself is covered by ChatServiceTest/StudyChatContextBuilderTest. */
class ChatControllerTest {
    private static class FakeAi implements AiServiceOperations {
        @Override public Map<String, Object> health() { return Map.of(); }
        @Override public Object models() { return Map.of(); }
        @Override public Map<String, Object> verifyModels() { return Map.of(); }
        @Override public Map<String, Object> warmup() { return Map.of(); }
        @Override public Map<String, Object> runPipeline(PipelineRunRequestDto request) { return Map.of(); }
        @Override public Map<String, Object> getAgentReport(String runId) { return Map.of(); }
        @Override public Map<String, Object> getAgentReportSummary(String runId) { return Map.of(); }
        @Override public Map<String, Object> getRecentAgentReports(int limit) { return Map.of(); }
        @Override public AiProductAnalyzeResponseDto analyzeProduct(AiProductAnalyzeRequestDto request) { throw new UnsupportedOperationException(); }
    }

    private static class FakeLlmProvider implements LlmProvider {
        @Override
        public LlmChatResult complete(LlmChatRequest request) {
            return new LlmChatResult("respuesta de prueba", "Qwen2.5-7B-Instruct", "self-hosted");
        }

        @Override
        public boolean checkAvailability() {
            return true;
        }
    }

    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();

    private ChatController buildController() {
        EffectiveAnalysisRunSelector runSelector = new EffectiveAnalysisRunSelector(repository);
        LongitudinalComparisonService longitudinalComparisonService = new LongitudinalComparisonService(repository, runSelector);
        AiProductAnalysisPersistenceService persistenceService = new AiProductAnalysisPersistenceService(
            new AiAnalysisService(new FakeAi()), new AiStudyInputResolver(repository), repository, new AiProductAnalysisMapper()
        );
        var contextBuilder = new ar.edu.uade.pfi.backend.service.StudyChatContextBuilder(repository, persistenceService, runSelector, longitudinalComparisonService);
        LlmProperties properties = new LlmProperties(true, "self-hosted", "http://localhost:8001", "Qwen2.5-7B-Instruct", 90, 0.1, 512, 8);
        ChatService service = new ChatService(properties, new FakeLlmProvider(), contextBuilder, repository, new ObjectMapper(), null);
        return new ChatController(service);
    }

    private Study seedStudyWithRun(String caseId) {
        Instant now = Instant.now();
        Study study = new Study(UUID.randomUUID().toString(), caseId, "registered", "PAT-1", LocalDate.of(2026, 8, 25), "MRI", "", "medium", now, now);
        repository.saveStudy(study);
        AiProductAnalysisRun run = new AiProductAnalysisRun(
            UUID.randomUUID().toString(), study.id(), study.caseId(), "pfi.ai-product-result.v1", "post-e50-ai-product-freeze-v1",
            "completed", true, true, false, "trace-1", "sag-1", "ax-1", List.of(), null, now, now
        );
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());
        return study;
    }

    @Test
    void chatEndpointReturnsAnAnswer() {
        seedStudyWithRun("case-1");
        ChatController controller = buildController();
        ChatResponseDto response = controller.chat("case-1", new ChatRequestDto("hola", null, null, null), fakeRequest());
        assertEquals("respuesta de prueba", response.answer());
    }

    @Test
    void historyAndDetailReflectThePersistedConversation() {
        seedStudyWithRun("case-1");
        ChatController controller = buildController();
        ChatResponseDto response = controller.chat("case-1", new ChatRequestDto("hola", null, null, null), fakeRequest());

        var history = controller.history("case-1");
        assertEquals(1, history.conversations().size());

        var detail = controller.detail("case-1", response.conversationId());
        assertEquals(2, detail.messages().size());
    }

    @Test
    void missingBodyDefaultsToEmptyRequestAndFailsValidationCleanly() {
        seedStudyWithRun("case-1");
        ChatController controller = buildController();
        assertThrows(RunReviewException.class, () -> controller.chat("case-1", null, fakeRequest()));
    }

    private jakarta.servlet.http.HttpServletRequest fakeRequest() {
        return mock(jakarta.servlet.http.HttpServletRequest.class);
    }
}
