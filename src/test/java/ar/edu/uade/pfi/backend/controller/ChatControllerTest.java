package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.client.LlmChatRequest;
import ar.edu.uade.pfi.backend.client.LlmChatResult;
import ar.edu.uade.pfi.backend.client.LlmProvider;
import ar.edu.uade.pfi.backend.config.LlmProperties;
import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.ChatRequestDto;
import ar.edu.uade.pfi.backend.dto.ChatResponseDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AssistantContextBuilder;
import ar.edu.uade.pfi.backend.service.ChatService;
import ar.edu.uade.pfi.backend.service.InitialAnalysisComparisonService;
import ar.edu.uade.pfi.backend.service.InitialAnalysisMeasurementReader;
import ar.edu.uade.pfi.backend.service.exception.RunReviewException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/** El controlador exige el rol profesional en todos los endpoints del asistente; la lógica está en ChatServiceTest. */
class ChatControllerTest {
    private static class FakeLlmProvider implements LlmProvider {
        int calls;

        @Override
        public LlmChatResult complete(LlmChatRequest request) {
            calls += 1;
            return new LlmChatResult("respuesta de prueba [M1]", "modelo-de-prueba", "self-hosted");
        }

        @Override
        public boolean checkAvailability() {
            return true;
        }
    }

    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final FakeLlmProvider llm = new FakeLlmProvider();

    private ChatController controller() {
        InitialAnalysisMeasurementReader reader = new InitialAnalysisMeasurementReader(repository);
        AssistantContextBuilder builder = new AssistantContextBuilder(repository, reader, new InitialAnalysisComparisonService(repository, reader));
        LlmProperties properties = new LlmProperties(true, "self-hosted", "http://localhost:8001", "modelo-de-prueba", 90, 0.1, 512, 8);
        return new ChatController(new ChatService(properties, llm, builder, repository, new ObjectMapper(), null), new RoleAuthorizationService(null));
    }

    private void seed() {
        Instant now = Instant.now();
        Study study = new Study(UUID.randomUUID().toString(), "case-1", "registered", "PAT-1", "S", LocalDate.of(2026, 8, 25), "MRI", "", "medium", now, now);
        repository.saveStudy(study);
        repository.saveInput(new InputResource(UUID.randomUUID().toString(), study.id(), "sagittal", "inp-1", "dcm", 10, "t2", "t2", 15, false, false, true, now));
        Map<String, Object> measurement = Map.of("id", "m1", "labelKey", "disc height", "level", "L4-L5", "levelScope", "level", "unit", "mm",
            "value", 5.79, "aiValue", 5.79, "plane", "sagittal");
        Map<String, Object> sagittal = Map.of("input", Map.of("inputId", "inp-1"), "measurements", List.of(measurement));
        repository.saveRun(new StudyRun(
            UUID.randomUUID().toString(), study.id(), "run-1", "trace", "real_baseline", "real_baseline", "sagittal_spider", "axial", "h", "", "s", "a",
            Map.of(), Map.of("planes", Map.of("sagittal", sagittal)), List.of(), "completed", "pending", "", null, "", now, now));
    }

    private static MockHttpServletRequest requestWithRoles(List<String> roles) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (roles != null) request.setAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE, new TokenService.Claims("user-1", "u@x", "User", roles));
        return request;
    }

    @Test
    void professionalRolesCanUseTheAssistant() {
        seed();
        ChatController controller = controller();
        for (String role : List.of("REVIEWER", "DOCTOR", "ADMIN")) {
            ChatResponseDto response = controller.chat("case-1", new ChatRequestDto("hola", "run-1", null, null, null), requestWithRoles(List.of(role)));
            assertEquals("respuesta de prueba [M1]", response.answer());
        }
        assertTrue(controller.history("case-1", "run-1", null, requestWithRoles(List.of("DOCTOR"))).conversations().size() >= 1);
        assertEquals("study", controller.context("case-1", "run-1", null, null, requestWithRoles(List.of("DOCTOR"))).scope().kind());
    }

    @Test
    void withoutAProfessionalRoleEveryEndpointIsForbiddenAndTheModelIsNeverCalled() {
        seed();
        ChatController controller = controller();
        for (List<String> denied : java.util.Arrays.asList(List.of("USER"), List.<String>of(), null)) {
            MockHttpServletRequest request = requestWithRoles(denied);
            assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.chat("case-1", new ChatRequestDto("hola", "run-1", null, null, null), request)).getStatusCode().value());
            assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.context("case-1", "run-1", null, null, request)).getStatusCode().value());
            assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.history("case-1", "run-1", null, request)).getStatusCode().value());
            assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.detail("case-1", UUID.randomUUID().toString(), request)).getStatusCode().value());
        }
        assertEquals(0, llm.calls);
    }

    @Test
    void missingBodyFailsValidationCleanly() {
        seed();
        assertThrows(RunReviewException.class, () -> controller().chat("case-1", null, requestWithRoles(List.of("DOCTOR"))));
    }
}
