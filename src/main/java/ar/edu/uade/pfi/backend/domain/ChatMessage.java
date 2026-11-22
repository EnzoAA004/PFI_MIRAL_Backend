package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;

/**
 * Etapa 80-S/80-T: un turno de una {@link ChatConversation}. Deliberadamente NO persiste el
 * contexto estructurado completo ni el texto del system prompt — solo {@code promptVersion} — ya que el
 * contexto siempre es reconstruible a partir de datos ya persistidos de las etapas 76-79 y volver a embeberlo
 * en cada mensaje duplicaría datos casi sensibles sin ningún beneficio (80-T: "El contexto se
 * puede reconstruir").
 *
 * <p>{@code model}/{@code provider} son {@code null} para un mensaje {@code USER} (quien llama
 * no eligió ninguno) y siempre están completos para un mensaje {@code ASSISTANT}.
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
    String contextVersion
) {
    public ChatMessage(String id, String conversationId, String role, String content, Instant createdAt, String model, String provider, String promptVersion, String analysisRunId, String baselineStudyId) {
        this(id, conversationId, role, content, createdAt, model, provider, promptVersion, analysisRunId, baselineStudyId, null);
    }
}
