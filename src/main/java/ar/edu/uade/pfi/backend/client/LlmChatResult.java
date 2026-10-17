package ar.edu.uade.pfi.backend.client;

/**
 * Stage 80: provider-agnostic chat result. Deliberately carries no safety flags
 * ({@code humanReviewRequired}/{@code notClinicalDiagnosis}) — those are set by
 * {@code ChatService} itself, unconditionally, never derived from a provider response (stage
 * 80-L: "No permitir que provider cambie esos flags").
 *
 * <p>{@code promptTokens}/{@code completionTokens} son los que el proveedor informa (si los informa): sirven para
 * medir, nunca para decidir nada. {@code truncated} es {@code true} cuando el proveedor cortó la respuesta por el límite de longitud
 * ({@code finish_reason = length}).
 */
public record LlmChatResult(String content, String model, String provider, Integer promptTokens, Integer completionTokens, boolean truncated) {
    public LlmChatResult(String content, String model, String provider) {
        this(content, model, provider, null, null, false);
    }

    public LlmChatResult(String content, String model, String provider, Integer promptTokens, Integer completionTokens) {
        this(content, model, provider, promptTokens, completionTokens, false);
    }
}
