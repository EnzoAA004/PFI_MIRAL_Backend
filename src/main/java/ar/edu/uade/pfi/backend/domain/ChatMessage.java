package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;

/**
 * Stage 80-S/80-T: one turn of a {@link ChatConversation}. Deliberately does NOT persist the
 * full structured context or the system prompt text — only {@code promptVersion} — since the
 * context is always reconstructible from already-persisted stage 76-79 data and re-embedding it
 * per message would duplicate sensitive-adjacent data for no benefit (80-T: "El contexto se
 * puede reconstruir").
 *
 * <p>{@code model}/{@code provider} are {@code null} for a {@code USER} message (the caller
 * didn't choose either) and always populated for an {@code ASSISTANT} message.
 */
public record ChatMessage(
    String id,
    String conversationId,
    String role,
    String content,
    Instant createdAt,
    String model,
    String provider,
    String promptVersion,
    String analysisRunId,
    String baselineStudyId
) {
}
