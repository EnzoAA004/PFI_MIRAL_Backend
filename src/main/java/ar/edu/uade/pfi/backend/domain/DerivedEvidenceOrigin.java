package ar.edu.uade.pfi.backend.domain;

/**
 * The four EXT-04B1 conceptual labels for an {@link AiEvidenceRegion} — always DERIVED, never
 * persisted as their own column (EXT-04B1 principle 1: no second, independent state machine).
 *
 * <p>Computed purely from {@code origin} (ai|professional), whether an
 * {@link AiEvidenceRegionCorrection} exists for the region, and the review vocabulary already
 * modeled by {@code AiDiscLevelReview}/{@code AiProductAnalysisRunReview}
 * ({@code pending|accepted|observed|rejected}) — reused as-is, never re-encoded.
 *
 * <p>{@code observed} is treated exactly like {@code accepted} here (both count as
 * "validated"), the same precedent {@code ar.edu.uade.pfi.backend.service.EffectiveAnalysisRunSelector}
 * already set by grouping them into a single {@code PREFERRED_REVIEW_STATUSES} set for run selection.
 * Inventing a different reading of "observed" for evidence regions than the one already used
 * for run selection would recreate the "two divergent interpretations of the same vocabulary"
 * risk this design explicitly avoids.
 */
public enum DerivedEvidenceOrigin {
    AI_SUGGESTED,
    PROFESSIONAL_VALIDATED,
    PROFESSIONAL_CORRECTED,
    PROFESSIONAL_DRAWN;

    private static final String ORIGIN_PROFESSIONAL = "professional";
    private static final String REVIEW_ACCEPTED = "accepted";
    private static final String REVIEW_OBSERVED = "observed";

    public static DerivedEvidenceOrigin derive(String origin, boolean hasCorrection, String reviewStatus) {
        if (hasCorrection) return PROFESSIONAL_CORRECTED;
        if (ORIGIN_PROFESSIONAL.equals(origin)) return PROFESSIONAL_DRAWN;
        boolean validated = REVIEW_ACCEPTED.equals(reviewStatus) || REVIEW_OBSERVED.equals(reviewStatus);
        return validated ? PROFESSIONAL_VALIDATED : AI_SUGGESTED;
    }
}
