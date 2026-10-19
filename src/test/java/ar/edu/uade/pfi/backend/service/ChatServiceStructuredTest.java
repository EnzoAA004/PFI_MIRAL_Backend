package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.client.LlmChatRequest;
import ar.edu.uade.pfi.backend.client.LlmChatResult;
import ar.edu.uade.pfi.backend.client.LlmProvider;
import ar.edu.uade.pfi.backend.config.LlmProperties;
import ar.edu.uade.pfi.backend.domain.ChatMessage;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.ChatContextInfoDto;
import ar.edu.uade.pfi.backend.dto.ChatRequestDto;
import ar.edu.uade.pfi.backend.dto.ChatResponseDto;
import ar.edu.uade.pfi.backend.service.exception.RunReviewException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Orquestación del modo acotado: el modelo (doble de prueba) solo elige una intención y filtros; la respuesta sale del
 * servidor. Estas pruebas verifican el contrato del servidor, no la calidad de ningún modelo.
 */
class ChatServiceStructuredTest {
    private static class FakeRouter implements LlmProvider {
        final AtomicInteger calls = new AtomicInteger();
        LlmChatRequest lastRequest;
        RuntimeException toThrow;
        String content = "{\"intent\":\"LIST_MEASUREMENTS\",\"levels\":[],\"metrics\":[],\"classifications\":[],\"only_present\":false}";

        @Override
        public LlmChatResult complete(LlmChatRequest request) {
            calls.incrementAndGet();
            lastRequest = request;
            if (toThrow != null) throw toThrow;
            return new LlmChatResult(content, "modelo-de-prueba", "self-hosted", 300, 30, false);
        }

        @Override
        public boolean checkAvailability() {
            return toThrow == null;
        }
    }

    private final AssistantTestFixtures fx = new AssistantTestFixtures();
    private final FakeRouter llm = new FakeRouter();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);

    private ChatService service(boolean enabled) {
        LlmProperties properties = new LlmProperties(enabled, "self-hosted", "http://localhost:8001", "modelo-de-prueba", 90, 0.1, 512, 8);
        return new ChatService(properties, llm, fx.builder(), fx.repository, new ObjectMapper(), null, clock, false);
    }

    private Study seed() {
        Study study = fx.studyWithContext("CASO-1", "PAT-1", LocalDate.of(2026, 10, 7), null, null, null, null, null, null);
        fx.run(study, "run-1", AssistantTestFixtures.T0, List.of(
            AssistantTestFixtures.disc("L3-L4", "width", 34.24), AssistantTestFixtures.disc("L4-L5", "height", 5.79),
            AssistantTestFixtures.measurement("sagittal-canal-ap-l5-s1", "canal ap", "L5-S1", "mm", 7.66)),
            List.of(AssistantTestFixtures.bulging("L5-S1", true, 0.704), AssistantTestFixtures.bulging("L4-L5", false, 0.9)));
        return study;
    }

    private static ChatRequestDto ask(String question, String runId, String conversationId) {
        return new ChatRequestDto(question, runId, null, null, conversationId);
    }

    @Test
    void aSuggestedQuestionIsAnsweredWithoutTheModelAndTheAnswerIsMarkedAsBackedByRegisteredData() {
        seed();
        ChatResponseDto response = service(true).chat("CASO-1", ask("¿Qué clasificaciones predijo el modelo como presentes?", "run-1", null), "t1");
        assertEquals(0, llm.calls.get(), "una pregunta sugerida no necesita al modelo");
        assertEquals("structured", response.mode());
        assertEquals("LIST_FINDINGS", response.intent());
        assertTrue(response.answerSupported());
        assertEquals(StructuredAssistant.VERSION, response.promptVersion());
        assertTrue(response.answer().contains("L5-S1 · Abombamiento discal: predicción del modelo «presente» (70,4 %)"), response.answer());
        assertFalse(response.answer().contains("L4-L5"));
        assertTrue(response.references().stream().anyMatch(r -> r.ref().equals("F1") && r.label().contains("Abombamiento discal")));
        assertTrue(response.humanReviewRequired() && response.notClinicalDiagnosis());
        assertEquals(0, response.removedReferences());
        assertNull(response.promptTokens());
        // Persistencia: el intercambio queda guardado con la versión del modo acotado.
        List<ChatMessage> saved = fx.repository.findChatMessagesByConversationId(response.conversationId());
        assertEquals(2, saved.size());
        assertTrue(saved.stream().allMatch(m -> StructuredAssistant.VERSION.equals(m.promptVersion())));
    }

    @Test
    void aFreeQuestionAsksTheModelOnlyToChooseAndTheServerBuildsTheAnswer() {
        seed();
        llm.content = "{\"intent\":\"LIST_MEASUREMENTS\",\"levels\":[\"L3-L4\"],\"metrics\":[\"ancho del disco\"],\"classifications\":[],\"only_present\":false}";
        ChatResponseDto response = service(true).chat("CASO-1", ask("¿Cuánto mide el ancho del disco en L3-L4?", "run-1", null), "t1");
        assertEquals(1, llm.calls.get());
        assertNotNull(llm.lastRequest.responseFormat(), "el pedido al modelo exige un esquema JSON estricto");
        assertEquals(0.0, llm.lastRequest.temperature());
        assertEquals(2, llm.lastRequest.messages().size(), "sistema + pregunta: el pedido no lleva el contexto ni los valores");
        assertFalse(llm.lastRequest.messages().stream().anyMatch(m -> m.content().contains("34.24") || m.content().contains("34,24")));
        assertEquals("modelo-de-prueba", response.model());
        assertTrue(response.answer().contains("L3-L4 · ancho del disco: 34,24 mm"), response.answer());
        assertFalse(response.answer().contains("5,79"), "el filtro de nivel y medición se aplicó");
        assertEquals("LIST_MEASUREMENTS", response.intent());
        assertTrue(response.shownCount() <= response.totalCount());
    }

    @Test
    void theProblematicFreeTextOfTheModelNeverReachesTheUser() {
        seed();
        llm.content = "En L3-L4 se detectan valores elevados de ancho del disco; el canal es reducido, lo que podría indicar estrechamiento. [M1]";
        ChatResponseDto response = service(true).chat("CASO-1", ask("Resumí los resultados", "run-1", null), "t1");
        assertFalse(response.answerSupported(), "una salida que no cumple el contrato no respalda ninguna respuesta");
        assertEquals("UNSUPPORTED", response.intent());
        String lower = response.answer().toLowerCase();
        assertFalse(lower.contains("elevad") || lower.contains("reducid") || lower.contains("podría indicar"), response.answer());
        assertTrue(response.answer().contains("Consultas disponibles:"));
        assertTrue(response.references().isEmpty());
        assertFalse(response.warnings().isEmpty());
    }

    @Test
    void anOutOfScopeIntentExplainsTheLimitInsteadOfInterpreting() {
        seed();
        llm.content = "{\"intent\":\"UNSUPPORTED\",\"levels\":[],\"metrics\":[],\"classifications\":[],\"only_present\":false}";
        ChatResponseDto response = service(true).chat("CASO-1", ask("¿Es grave? ¿Hay que operar?", "run-1", null), "t1");
        assertFalse(response.answerSupported());
        assertTrue(response.answer().contains("fuera de lo que el asistente puede responder"));
        assertTrue(response.references().isEmpty());
    }

    @Test
    void aLevelWithoutDataIsAnsweredWithoutTheModelAndNeverReplaced() {
        seed();
        ChatResponseDto response = service(true).chat("CASO-1", ask("¿Qué mediciones tiene L6-S1?", "run-1", null), "t1");
        assertEquals(0, llm.calls.get());
        assertFalse(response.answerSupported());
        assertTrue(response.answer().contains("No hay datos registrados para el nivel L6-S1"), response.answer());
        assertTrue(response.answer().contains("L5-S1"), "ofrece los niveles que sí tienen datos");
    }

    @Test
    void providerFailuresStillSurfaceForFreeQuestionsButNotForSuggestedOnes() {
        seed();
        llm.toThrow = new RunReviewException(HttpStatus.GATEWAY_TIMEOUT, "LLM_PROVIDER_TIMEOUT", "timeout");
        RunReviewException timeout = assertThrows(RunReviewException.class, () -> service(true).chat("CASO-1", ask("¿Cuánto mide el ancho del disco en L3-L4?", "run-1", null), "t1"));
        assertEquals("LLM_PROVIDER_TIMEOUT", timeout.code());
        // Las preguntas sugeridas no dependen del modelo: siguen funcionando con el proveedor caído.
        ChatResponseDto response = service(true).chat("CASO-1", ask("¿Qué datos faltan?", "run-1", null), "t1");
        assertTrue(response.answerSupported());
        assertEquals(1, llm.calls.get());
    }

    @Test
    void withTheProviderDisabledOnlyTheSuggestedQuestionsAnswer() {
        seed();
        RunReviewException disabled = assertThrows(RunReviewException.class, () -> service(false).chat("CASO-1", ask("¿Cuánto mide el ancho del disco en L3-L4?", "run-1", null), "t1"));
        assertEquals("LLM_PROVIDER_DISABLED", disabled.code());
        assertTrue(service(false).chat("CASO-1", ask("¿Qué datos faltan?", "run-1", null), "t1").answerSupported());
        assertEquals(0, llm.calls.get());
    }

    @Test
    void aConversationKeepsItsScopeAndFollowUpsAreAnsweredTheSameWay() {
        seed();
        ChatResponseDto first = service(true).chat("CASO-1", ask("¿Qué datos faltan?", "run-1", null), "t1");
        ChatResponseDto second = service(true).chat("CASO-1", ask("¿Qué clasificaciones predijo el modelo como presentes?", "run-1", first.conversationId()), "t2");
        assertEquals(first.conversationId(), second.conversationId());
        assertEquals(4, fx.repository.findChatMessagesByConversationId(first.conversationId()).size());
        // Otra corrida u otro estudio no reutilizan la conversación.
        Study other = fx.study("CASO-2", "PAT-2", "S2", LocalDate.of(2026, 10, 7));
        fx.run(other, "run-2", AssistantTestFixtures.T0, List.of(AssistantTestFixtures.disc("L4-L5", "height", 6.0)), null);
        assertThrows(RunReviewException.class, () -> service(true).chat("CASO-2", ask("¿Qué datos faltan?", "run-2", first.conversationId()), "t3"));
    }

    @Test
    void theContextInfoOffersTheSupportedQuestionsAndSaysTheModeAndTheProviderApart() {
        seed();
        ChatContextInfoDto info = service(true).context("CASO-1", "run-1", null, null);
        assertEquals("structured", info.mode());
        assertEquals(StructuredAssistant.VERSION, info.promptVersion());
        assertEquals(List.of("SUMMARY", "LIST_FINDINGS", "LIST_CORRECTIONS", "LIST_MISSING_DATA"), info.suggestedQuestions().stream().map(ChatContextInfoDto.SuggestedQuestion::intent).toList());
        assertTrue(info.provider().enabled());
        assertTrue(info.provider().available(), "modelo conectado");
        assertTrue(info.elementCount() > 0, "fuente disponible");
    }

    @Test
    void aPairAnswersTheDifferencesWithSidesAndHowManyAreShown() {
        Study reference = fx.studyWithContext("REF-1", "P-1", LocalDate.of(2026, 4, 1), 50, Study.AGE_CALCULATED, null, null, null, null);
        Study current = fx.studyWithContext("CUR-1", "P-1", LocalDate.of(2026, 10, 7), 50, Study.AGE_CALCULATED, null, null, null, null);
        fx.run(reference, "run-a", AssistantTestFixtures.T0, List.of(AssistantTestFixtures.disc("L4-L5", "height", 5.79), AssistantTestFixtures.disc("L4-L5", "area", 100.0)), null);
        fx.run(current, "run-b", AssistantTestFixtures.T0.plusSeconds(100), List.of(AssistantTestFixtures.disc("L4-L5", "height", 7.3), AssistantTestFixtures.disc("L4-L5", "area", 100.0)), null);
        ChatResponseDto response = service(true).chat("CUR-1", new ChatRequestDto("¿Qué diferencias se registraron entre A y B?", "run-b", "REF-1", "run-a", null), "t1");
        assertEquals(0, llm.calls.get());
        assertEquals("pair", response.scope().kind());
        assertEquals("LIST_DIFFERENCES", response.intent());
        assertTrue(response.answer().contains("A 5,79 mm → B 7,3 mm · B − A +1,51 mm"), response.answer());
        assertEquals(response.totalCount(), response.shownCount());
        assertTrue(response.answer().contains("Mostrando " + response.shownCount() + " de " + response.totalCount()));
        List<String> refs = new ArrayList<>();
        response.references().forEach(r -> refs.add(r.ref()));
        assertTrue(refs.contains("S") && refs.stream().anyMatch(r -> r.startsWith("D")), refs.toString());
        // Sin hallazgos en los dos lados, la pregunta sobre cambios de clasificación no es una de las sugeridas.
        assertFalse(service(true).context("CUR-1", "run-b", "REF-1", "run-a").suggestedQuestions().stream().anyMatch(q -> q.intent().equals("LIST_FINDING_CHANGES")));
    }
}
