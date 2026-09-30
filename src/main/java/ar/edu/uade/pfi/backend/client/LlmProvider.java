package ar.edu.uade.pfi.backend.client;

/**
 * Stage 80: the one seam {@code ChatService} depends on. Swapping the underlying model/runtime —
 * self-hosted Qwen today, potentially {@code OpenAiLlmProvider}/{@code GeminiLlmProvider}/
 * {@code AnthropicLlmProvider} later — means adding a new implementation of this interface and
 * flipping {@code pfi.llm.provider}, never touching {@code ChatService} or the context-building
 * logic. No implementation may call FastAPI, the database, or the filesystem — a provider only
 * ever turns a domain request into a wire request and a wire response back into a domain result.
 */
public interface LlmProvider {
    LlmChatResult complete(LlmChatRequest request);

    /**
     * Stage 81-T: a cheap, side-effect-free liveness probe — never generates tokens, never
     * throws. Used only by {@code SystemDiagnosticsService} to distinguish
     * enabled+reachable/enabled+unreachable in {@code /api/system/diagnostics}, so an operator
     * can tell "LLM disabled" apart from "LLM enabled but the runtime is down" without waiting
     * for a real chat request to fail first.
     */
    boolean checkAvailability();
}
