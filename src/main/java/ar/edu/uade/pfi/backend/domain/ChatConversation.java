package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;

/**
 * Un hilo de conversacion del asistente. Pertenece a un ambito: una corrida de un estudio
 * ({@code scopeKey = "run:<runId>"}) o el par de corridas del visor dual
 * ({@code "pair:<runId>|<referenceRunId>"}). {@code scopeKey == null} marca las conversaciones del
 * circuito de demostracion anterior: se conservan, pero no pertenecen a ningun ambito real.
 */
public record ChatConversation(
    String id,
    String studyId,
    Instant createdAt,
    String scopeKey,
    String runId,
    String referenceStudyId,
    String referenceRunId
) {
    public ChatConversation(String id, String studyId, Instant createdAt) {
        this(id, studyId, createdAt, null, null, null, null);
    }
}
