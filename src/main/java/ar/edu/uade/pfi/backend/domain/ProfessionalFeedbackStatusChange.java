package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * EXT-05: one append-only row per curation status change on a {@link ProfessionalFeedbackCandidate}.
 * The candidate row itself always reflects the *current* status (for cheap listing/filtering);
 * this table is the durable history — never updated or deleted, mirroring the same append-only
 * convention {@code AiEvidenceRegionCorrection} already established for geometry corrections.
 */
public record ProfessionalFeedbackStatusChange(
    String id,
    String candidateId,
    String fromStatus,
    String toStatus,
    /** Internal only — never serialized, same rule as {@code ProfessionalFeedbackCandidate.curatorId}. */
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
