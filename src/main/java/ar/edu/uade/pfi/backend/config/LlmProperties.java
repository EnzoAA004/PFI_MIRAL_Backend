package ar.edu.uade.pfi.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stage 80: configuration for the self-hosted LLM provider (Qwen2.5-7B-Instruct behind an
 * OpenAI-chat-completions-shaped HTTP API — see {@code SelfHostedLlmProvider}). Everything is
 * env-var overridable, nothing hardcoded — matching {@code AiServiceProperties}'s convention.
 *
 * <p>{@code enabled} defaults to {@code false} (stage 80-AF: "diseñar para poder apagar el LLM
 * runtime en desarrollo") — chat is an explicitly opt-in, complementary capability, never a
 * dependency the rest of the product needs to boot.
 */
@ConfigurationProperties(prefix = "pfi.llm")
public record LlmProperties(
    Boolean enabled,
    String provider,
    String baseUrl,
    String model,
    Integer timeoutSeconds,
    Double temperature,
    Integer maxOutputTokens,
    Integer historyWindowTurns,
    String authMode,
    String audience,
    Boolean privateRequired
) {
    public LlmProperties(Boolean enabled, String provider, String baseUrl, String model,
            Integer timeoutSeconds, Double temperature, Integer maxOutputTokens, Integer historyWindowTurns) {
        this(enabled, provider, baseUrl, model, timeoutSeconds, temperature, maxOutputTokens,
            historyWindowTurns, null, null, false);
    }

    public boolean resolvedEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public String resolvedProvider() {
        return provider == null || provider.isBlank() ? "self-hosted" : provider;
    }

    public String resolvedBaseUrl() {
        return baseUrl == null || baseUrl.isBlank() ? "http://localhost:8001" : baseUrl;
    }

    public String resolvedModel() {
        return model == null || model.isBlank() ? "Qwen2.5-7B-Instruct" : model;
    }

    public AiServiceAuthMode resolvedAuthMode() {
        return AiServiceAuthMode.fromConfigValue(authMode);
    }

    public boolean resolvedPrivateRequired() {
        return Boolean.TRUE.equals(privateRequired);
    }

    public String resolvedAudience() {
        return new AiServiceProperties(null, null, null, null, "google-id-token", audience)
            .resolvedAudience();
    }

    /** Floor of 10s — a self-hosted model can be slower than a conventional API but must never be unbounded (80-Q). */
    public int resolvedTimeoutSeconds() {
        return timeoutSeconds == null ? 90 : Math.max(timeoutSeconds, 10);
    }

    /** Default 0.1 — consistency/fidelity prioritized over creativity (80-AH). */
    public double resolvedTemperature() {
        return temperature == null ? 0.1 : temperature;
    }

    public int resolvedMaxOutputTokens() {
        return maxOutputTokens == null ? 512 : maxOutputTokens;
    }

    /**
     * Stage 81-C: bounded conversation-history window for multi-turn chat, in turns (one turn =
     * one USER message + its ASSISTANT reply). Default 8, floor 1 — a continued conversation
     * never reinjects unlimited history (81 "NO mandar conversación ilimitada").
     */
    public int resolvedHistoryWindowTurns() {
        return historyWindowTurns == null ? 8 : Math.max(historyWindowTurns, 1);
    }
}
