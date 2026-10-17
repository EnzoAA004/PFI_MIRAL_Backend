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
 * Stage 80-C: the only {@link LlmProvider} implementation for this stage — a self-hosted model
 * (Qwen2.5-7B-Instruct by default) served behind an OpenAI-chat-completions-shaped HTTP API
 * (typically vLLM's own compatible server). This class is the ONLY place that knows that wire
 * shape — {@code ChatService}/{@code StudyChatContextBuilder} never see it.
 *
 * <p>Mirrors {@code AiServiceClient}'s dispatch pattern exactly: a dedicated {@code WebClient}
 * bean ({@code llmWebClient}), {@code .block(timeout)} with a per-provider timeout floor, and a
 * translate-on-catch step that never lets a raw upstream body, stack trace, or hostname escape
 * into the exception surfaced to the rest of the Backend.
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
     * Fail-closed parsing of the provider's own response, mirroring
     * {@code AiProductContractValidator}'s discipline: the first missing/malformed field throws
     * immediately rather than being papered over with a default. A malformed provider response
     * must never surface as if it were a real (if empty) answer.
     */
    private LlmChatResult parseProviderResponse(Map<String, Object> raw) {
        if (raw == null) throw contractViolation();
        Object choicesValue = raw.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()) throw contractViolation();
        if (!(choices.get(0) instanceof Map<?, ?> choice)) throw contractViolation();
        if (!(choice.get("message") instanceof Map<?, ?> message)) throw contractViolation();
        if (!(message.get("content") instanceof String content) || content.isBlank()) throw contractViolation();
        Integer promptTokens = null;
        Integer completionTokens = null;
        if (raw.get("usage") instanceof Map<?, ?> usage) {
            if (usage.get("prompt_tokens") instanceof Number n) promptTokens = n.intValue();
            if (usage.get("completion_tokens") instanceof Number n) completionTokens = n.intValue();
        }
        return new LlmChatResult(content.trim(), properties.resolvedModel(), properties.resolvedProvider(), promptTokens, completionTokens);
    }

    private RunReviewException translate(RuntimeException ex) {
        Throwable unwrapped = Exceptions.unwrap(ex);
        if (unwrapped instanceof RunReviewException upstream) return upstream;
        if (isTimeout(unwrapped)) {
            return new RunReviewException(HttpStatus.GATEWAY_TIMEOUT, "LLM_PROVIDER_TIMEOUT", ApiErrorCode.LLM_PROVIDER_TIMEOUT.publicMessage());
        }
        return upstreamError();
    }

    /** Walks the full cause chain — a connector-level timeout usually arrives wrapped in a {@code WebClientRequestException}. */
    private boolean isTimeout(Throwable unwrapped) {
        Throwable current = unwrapped;
        while (current != null) {
            if (current instanceof TimeoutException || current instanceof ReadTimeoutException) return true;
            current = current.getCause();
        }
        return false;
    }

    /**
     * A GET to the OpenAI-compatible {@code /v1/models} endpoint — every vLLM/OpenAI-compatible
     * server exposes it, it generates no tokens, and it is cheap enough to call from a
     * diagnostics endpoint. Any failure (timeout, connection refused, non-2xx) is swallowed and
     * reported as unavailable rather than propagated — this method must never throw.
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
