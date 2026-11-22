package ar.edu.uade.pfi.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Etapa 80: configuración del proveedor de LLM autoalojado (Qwen2.5-7B-Instruct detrás de una
 * API HTTP con forma de chat-completions de OpenAI — ver {@code SelfHostedLlmProvider}). Todo es
 * sobrescribible por variables de entorno, nada hardcodeado — siguiendo la convención de {@code AiServiceProperties}.
 *
 * <p>{@code enabled} defaults to {@code false} (stage 80-AF: "diseñar para poder apagar el LLM
 * runtime en desarrollo") — el chat es una capacidad complementaria, explícitamente opt-in, nunca una
 * dependencia que el resto del producto necesite para arrancar.
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
    @ConstructorBinding
    public LlmProperties {}

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

    /** Piso de 10s — un modelo autoalojado puede ser más lento que una API convencional, pero nunca debe quedar sin límite (80-Q). */
    public int resolvedTimeoutSeconds() {
        return timeoutSeconds == null ? 90 : Math.max(timeoutSeconds, 10);
    }

    /** Default 0.1 — se prioriza la consistencia/fidelidad sobre la creatividad (80-AH). */
    public double resolvedTemperature() {
        return temperature == null ? 0.1 : temperature;
    }

    public int resolvedMaxOutputTokens() {
        return maxOutputTokens == null ? 512 : maxOutputTokens;
    }

    /**
     * Etapa 81-C: ventana acotada de historial de conversación para chat multi-turno, en turnos (un turno =
     * un mensaje USER + su respuesta ASSISTANT). Por defecto 8, piso 1 — una conversación continuada
     * nunca reinyecta historial ilimitado (81 "NO mandar conversación ilimitada").
     */
    public int resolvedHistoryWindowTurns() {
        return historyWindowTurns == null ? 8 : Math.max(historyWindowTurns, 1);
    }
}
