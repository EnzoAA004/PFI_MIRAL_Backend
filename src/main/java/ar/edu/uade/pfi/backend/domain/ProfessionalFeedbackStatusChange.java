package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * EXT-05: una fila append-only por cada cambio de estado de curación de un {@link ProfessionalFeedbackCandidate}.
 * La fila del candidato siempre refleja el estado *actual* (para listar/filtrar barato);
 * esta tabla es el historial durable — nunca se actualiza ni se borra, replicando la misma convención
 * append-only que {@code AiEvidenceRegionCorrection} ya estableció para las correcciones de geometría.
 */
public record ProfessionalFeedbackStatusChange(
    String id,
    String candidateId,
    String fromStatus,
    String toStatus,
    /** Solo interno — nunca se serializa, misma regla que {@code ProfessionalFeedbackCandidate.curatorId}. */
    String curatorId,
    String reason,
    String note,
    Instant changedAt
) {
    public ProfessionalFeedbackStatusChange {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(fromStatus, "fromStatus");
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(changedAt, "changedAt");
        if (!ProfessionalFeedbackCandidate.ALLOWED_STATUSES.contains(toStatus)) {
            throw new IllegalArgumentException("Estado de feedback inválido: " + toStatus);
        }
    }
}
