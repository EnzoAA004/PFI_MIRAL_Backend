package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;

/**
 * Professional review state for one {@link AiDiscLevelResult} — stage 78-C, the minimal granular
 * layer defensible beyond run-level review. One row per disc-level result (upserted).
 *
 * <p>This NEVER changes {@link AiDiscLevelResult}'s own fields (level, localizationStatus,
 * axialPairingValidated, degenerativeFindingsStatus, ...) — those remain the AI's original,
 * immutable output. Accepting a 67C pairing here does not and cannot set
 * {@code axialPairingValidated=true}; that is a professional-review decision layered on top of,
 * never a substitute for, the AI method's own validation status.
 */
public record AiDiscLevelReview(
    String id,
    String discLevelResultId,
    String reviewStatus,
    String reviewComment,
    String reviewedBy,
    Instant reviewedAt,
    Instant updatedAt
) {
}
