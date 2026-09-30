package ar.edu.uade.pfi.backend.client;

/**
 * Stage 80: one turn in an {@link LlmChatRequest} — {@code role} is {@code system|user|assistant},
 * plain domain shape, never the raw OpenAI-style JSON. Translating this to/from a specific
 * provider's wire format is exactly what {@link LlmProvider} implementations exist to do.
 */
public record LlmChatMessage(String role, String content) {
}
