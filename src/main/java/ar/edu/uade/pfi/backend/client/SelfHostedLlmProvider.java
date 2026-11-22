package ar.edu.uade.pfi.backend.client;

import ar.edu.uade.pfi.backend.config.LlmProperties;
import ar.edu.uade.pfi.backend.web.error.ApiErrorCode;
import ar.edu.uade.pfi.backend.service.exception.RunReviewException;
import io.netty.handler.timeout.ReadTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/**
 * Etapa 80-C: la única implementación de {@link LlmProvider} para esta etapa — un modelo autoalojado
 * (Qwen2.5-7B-Instruct por defecto) servido detrás de una API HTTP con forma de chat-completions de OpenAI
 * (típicamente el servidor compatible propio de vLLM). Esta clase es el ÚNICO lugar que conoce esa forma
 * wire — {@code ChatService}/{@code StudyChatContextBuilder} nunca la ven.
 *
 * <p>Replica exactamente el patrón de dispatch de {@code AiServiceClient}: un bean {@code WebClient} dedicado
 * ({@code llmWebClient}), {@code .block(timeout)} con un piso de timeout por proveedor, y un
 * paso de traducción en el catch que nunca deja escapar un body upstream crudo, un stack trace ni un hostname
 * hacia la excepción que se expone al resto del Backend.
 */
@Component
public class SelfHostedLlmProvider implements LlmProvider {
    private final WebClient llmWebClient;
    private final LlmProperties properties;

    public SelfHostedLlmProvider(WebClient llmWebClient, LlmProperties properties) {
        this.llmWebClient = llmWebClient;
        this.properties = properties;
    }

    @Override
    public LlmChatResult complete(LlmChatRequest request) {
        Duration timeout = Duration.ofSeconds(properties.resolvedTimeoutSeconds());
        Map<String, Object> raw;
        try {
            raw = llmWebClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(toProviderRequest(request))
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() { });
                    }
                    return response.releaseBody().then(Mono.error(upstreamError()));
                })
                .block(timeout);
        } catch (RunReviewException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw translate(ex);
        }
        return parseProviderResponse(raw);
    }

    private Map<String, Object> toProviderRequest(LlmChatRequest request) {
        List<Map<String, String>> messages = request.messages().stream()
            .map(message -> Map.of("role", message.role(), "content", message.content()))
            .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.resolvedModel());
        body.put("messages", messages);
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxOutputTokens());
        return body;
    }

    /**
     * Parseo fail-closed de la respuesta propia del proveedor, en línea con la disciplina de
     * {@code AiProductContractValidator}: el primer campo faltante/malformado lanza excepción
     * de inmediato en lugar de taparse con un valor por defecto. Una respuesta malformada del proveedor
     * nunca debe aparecer como si fuera una respuesta real (aunque vacía).
     */
    private LlmChatResult parseProviderResponse(Map<String, Object> raw) {
        if (raw == null) throw contractViolation();
        Object choicesValue = raw.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()) throw contractViolation();
        if (!(choices.get(0) instanceof Map<?, ?> choice)) throw contractViolation();
        if (!(choice.get("message") instanceof Map<?, ?> message)) throw contractViolation();
        if (!(message.get("content") instanceof String content) || content.isBlank()) throw contractViolation();
        return new LlmChatResult(content.trim(), properties.resolvedModel(), properties.resolvedProvider());
    }

    private RunReviewException translate(RuntimeException ex) {
        Throwable unwrapped = Exceptions.unwrap(ex);
        if (unwrapped instanceof RunReviewException upstream) return upstream;
        if (isTimeout(unwrapped)) {
            return new RunReviewException(HttpStatus.GATEWAY_TIMEOUT, "LLM_PROVIDER_TIMEOUT", ApiErrorCode.LLM_PROVIDER_TIMEOUT.publicMessage());
        }
        return upstreamError();
    }

    /** Recorre toda la cadena de causas — un timeout a nivel de conector suele llegar envuelto en una {@code WebClientRequestException}. */
    private boolean isTimeout(Throwable unwrapped) {
        Throwable current = unwrapped;
        while (current != null) {
            if (current instanceof TimeoutException || current instanceof ReadTimeoutException) return true;
            current = current.getCause();
        }
        return false;
    }

    /**
     * Un GET al endpoint {@code /v1/models} compatible con OpenAI — todo servidor compatible con vLLM/OpenAI
     * lo expone, no genera tokens y es lo bastante barato como para llamarlo desde un
     * endpoint de diagnóstico. Cualquier falla (timeout, conexión rechazada, no-2xx) se traga y
     * se informa como no disponible en lugar de propagarse — este método nunca debe lanzar excepción.
     */
    @Override
    public boolean checkAvailability() {
        try {
            return Boolean.TRUE.equals(
                llmWebClient.get()
                    .uri("/v1/models")
                    .exchangeToMono(response -> Mono.just(response.statusCode().is2xxSuccessful()))
                    .block(Duration.ofSeconds(3))
            );
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private RunReviewException upstreamError() {
        return new RunReviewException(HttpStatus.BAD_GATEWAY, "LLM_PROVIDER_UNAVAILABLE", ApiErrorCode.LLM_PROVIDER_UNAVAILABLE.publicMessage());
    }

    private RunReviewException contractViolation() {
        return new RunReviewException(HttpStatus.BAD_GATEWAY, "LLM_CONTRACT_VIOLATION", ApiErrorCode.LLM_CONTRACT_VIOLATION.publicMessage());
    }
}
