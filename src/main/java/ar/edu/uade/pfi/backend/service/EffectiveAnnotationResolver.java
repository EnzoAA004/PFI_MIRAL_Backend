package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelReview;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegion;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegionCorrection;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRunReview;
import ar.edu.uade.pfi.backend.domain.DerivedEvidenceOrigin;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * EXT-04B2: resolves, for each {@link AiEvidenceRegion} of a run, which geometry is the
 * "effective" one to show — inspired by {@link EffectiveMeasurementResolver} but NOT a
 * mechanical copy, because a region's effectiveness also depends on whether it was rejected
 * (a scalar measurement has no such gate) and on a caller-controlled policy for still-pending
 * AI suggestions.
 *
 * <p>Policy (EXT-04B1 section 11, confirmed against the review system that actually exists):
 * <ol>
 *   <li>a correction exists → the corrected geometry, always effective;</li>
 *   <li>{@code origin="professional"} (hand-drawn, no AI original) → its own geometry, always
 *       effective;</li>
 *   <li>{@code origin="ai"}, review {@code accepted} or {@code observed} → the AI geometry,
 *       effective;</li>
 *   <li>{@code origin="ai"}, review {@code pending} → the AI geometry, effective only if the
 *       caller opts in ({@code includeUnreviewedSuggestions}) — hiding an unreviewed suggestion
 *       by default is a product/UI policy choice, not a data-resolution one, so this resolver
 *       exposes the flag instead of deciding unilaterally;</li>
 *   <li>{@code origin="ai"}, review {@code rejected}, no correction → never effective. A
 *       rejection that is later followed by a correction stops being "the AI's rejected
 *       suggestion" and becomes case 1 instead — a professional actively re-authoring the
 *       geometry is a stronger, more specific signal than a coarser prior rejection.</li>
 * </ol>
 *
 * <p>{@code observed} is treated exactly like {@code accepted} (see {@link DerivedEvidenceOrigin}
 * javadoc) — the same reading {@link EffectiveAnalysisRunSelector} already gives it for run
 * selection. Nothing here ever deletes or hides history: a rejected/pending region is still
 * returned (only {@link EffectiveAnnotation#effective()} is {@code false}) — resolving
 * "effective" is a projection, never a delete (EXT-04B1 principle: "no esconder evidencia
 * histórica").
 */
@Component
public class EffectiveAnnotationResolver {
    private static final String REVIEW_REJECTED = "rejected";
    private static final String REVIEW_PENDING = "pending";

    private final StudyRepository repository;

    public EffectiveAnnotationResolver(StudyRepository repository) {
        this.repository = repository;
    }

    public record EffectiveAnnotation(
        AiEvidenceRegion region,
        String effectiveGeometryType,
        Map<String, Object> effectiveGeometry,
        DerivedEvidenceOrigin derivedOrigin,
        String reviewStatus,
        boolean effective
    ) {
    }

    public List<EffectiveAnnotation> effectiveAnnotations(AiProductAnalysisRun run, boolean includeUnreviewedSuggestions) {
        List<AiEvidenceRegion> regions = repository.findAiEvidenceRegionsByAnalysisRunId(run.id());
        if (regions.isEmpty()) return List.of();

        Map<String, AiEvidenceRegionCorrection> latestCorrections = latestCorrectionsByRegion(
            repository.findAiEvidenceRegionCorrectionsByAnalysisRunId(run.id())
        );
        Map<String, AiDiscLevelReview> discLevelReviews = new HashMap<>();
        for (AiDiscLevelReview review : repository.findAiDiscLevelReviewsByAnalysisRunId(run.id())) {
            discLevelReviews.put(review.discLevelResultId(), review);
        }
        Optional<AiProductAnalysisRunReview> runReview = repository.findAiProductAnalysisRunReview(run.id());

        List<EffectiveAnnotation> results = new ArrayList<>();
        for (AiEvidenceRegion region : regions) {
            AiEvidenceRegionCorrection correction = latestCorrections.get(region.id());
            boolean hasCorrection = correction != null;
            String reviewStatus = resolveReviewStatus(region, discLevelReviews, runReview);
            DerivedEvidenceOrigin derived = DerivedEvidenceOrigin.derive(region.origin(), hasCorrection, reviewStatus);

            boolean rejectedAndUncorrected = !hasCorrection && "ai".equals(region.origin()) && REVIEW_REJECTED.equals(reviewStatus);
            boolean effective = switch (derived) {
                case PROFESSIONAL_CORRECTED, PROFESSIONAL_DRAWN, PROFESSIONAL_VALIDATED -> true;
                case AI_SUGGESTED -> !rejectedAndUncorrected && includeUnreviewedSuggestions;
            };

            String effectiveType = hasCorrection ? correction.correctedGeometryType() : region.geometryType();
            Map<String, Object> effectiveGeometry = hasCorrection ? correction.correctedGeometry() : region.geometry();

            results.add(new EffectiveAnnotation(region, effectiveType, effectiveGeometry, derived, reviewStatus, effective));
        }
        return results;
    }

    /**
     * Same fallback {@link ar.edu.uade.pfi.backend.service.StudyReportService#discLevels}
     * already applies: a disc-level review when the region names one, the run-level review
     * otherwise, {@code "pending"} when neither exists.
     */
    private String resolveReviewStatus(
        AiEvidenceRegion region,
        Map<String, AiDiscLevelReview> discLevelReviews,
        Optional<AiProductAnalysisRunReview> runReview
    ) {
        if (region.discLevelResultId() != null) {
            AiDiscLevelReview review = discLevelReviews.get(region.discLevelResultId());
            if (review != null) return review.reviewStatus();
        }
        return runReview.map(AiProductAnalysisRunReview::reviewStatus).orElse(REVIEW_PENDING);
    }

    private Map<String, AiEvidenceRegionCorrection> latestCorrectionsByRegion(List<AiEvidenceRegionCorrection> corrections) {
        Map<String, AiEvidenceRegionCorrection> latest = new HashMap<>();
        for (AiEvidenceRegionCorrection correction : corrections) {
            AiEvidenceRegionCorrection existing = latest.get(correction.evidenceRegionId());
            if (existing == null || correction.createdAt().isAfter(existing.createdAt())) {
                latest.put(correction.evidenceRegionId(), correction);
            }
        }
        return latest;
    }
}
