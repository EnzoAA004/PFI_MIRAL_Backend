package ar.edu.uade.pfi.backend.client;

/**
 * Stage 80: provider-agnostic chat result. Deliberately carries no safety flags
 * ({@code humanReviewRequired}/{@code notClinicalDiagnosis}) — those are set by
 * {@code ChatService} itself, unconditionally, never derived from a provider response (stage
 * 80-L: "No permitir que provider cambie esos flags").
 */
public record LlmChatResult(String content, String model, String provider) {
}
