package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;

/**
 * Professional review state for one {@link AiProductAnalysisRun} — stage 78-B. Exactly one row
 * per run (upserted, not appended): the profession's current decision on this run, not an
 * append-only audit trail. Auditability of *changes* to this row is provided separately via
 * {@link DomainAuditEvent} ({@code action = "ai_product_review.run_status_changed"}), matching
 * the existing convention rather than inventing new event sourcing.
 *
 * <p>{@code reviewStatus} is one of {@code pending|accepted|observed|rejected} — deliberately not
 * a clinical vocabulary ("diagnosed"/"confirmed"/"normal"); this is a professional-review
 * decision on the AI run, not a clinical finding.
 */
public record AiProductAnalysisRunReview(
    String id,
    String analysisRunId,
    String reviewStatus,
    String reviewComment,
    String reviewedBy,
    Instant reviewedAt,
    Instant updatedAt
) {
}
