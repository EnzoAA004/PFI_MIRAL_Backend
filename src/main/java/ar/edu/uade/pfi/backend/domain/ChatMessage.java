package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;

/**
 * Un turno de una {@link ChatConversation}. No guarda el contexto completo ni el texto del prompt: solo la
 * version del prompt y la huella ({@code contextFingerprint}) del contexto con que se respondio, para poder
 * distinguir una respuesta basada en datos anteriores de una basada en los actuales.
 *
 * <p>{@code model}/{@code provider} son {@code null} en un mensaje USER y siempre estan en un ASSISTANT.
 * {@code referencesJson} (solo ASSISTANT) son las referencias que la respuesta cito y que se verificaron
 * contra el contexto.
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
    String baselineStudyId,
    String contextFingerprint,
    String referencesJson
) {
    public ChatMessage(
        String id, String conversationId, String role, String content, Instant createdAt,
        String model, String provider, String promptVersion, String analysisRunId, String baselineStudyId
    ) {
        this(id, conversationId, role, content, createdAt, model, provider, promptVersion, analysisRunId, baselineStudyId, null, null);
    }
}
