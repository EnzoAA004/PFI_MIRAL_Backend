package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Stage 79-C: picks ONE {@link AiProductAnalysisRun} per Study to use for longitudinal
 * comparison, deterministically. No existing selection convention was found in the domain for
 * this (stage 79-A recon: greenfield) — this is the explicit rule chosen, documented here rather
 * than left implicit:
 *
 * <ol>
 *   <li>Only {@code completed}/{@code partial} runs are ever eligible — {@code failed} is never
 *       usable, regardless of recency (stage 79-C point 3).</li>
 *   <li>Among eligible runs, if at least one has a run-level review of {@code accepted} or
 *       {@code observed} (stage 78), the most recent (by {@code createdAt}) SUCH run wins — a
 *       professionally reviewed run is preferred over a merely more recent unreviewed one.</li>
 *   <li>Otherwise, the most recent eligible run (by {@code createdAt}) wins, regardless of review
 *       state (including {@code pending} or {@code rejected}).</li>
 * </ol>
 *
 * A {@code rejected} review does not remove a run from eligibility — rejecting a review is a
 * professional judgment on the run's quality, not a statement that no analysis exists; stage 79
 * does not invent a stronger exclusion rule than the domain actually models.
 */
@Component
public class EffectiveAnalysisRunSelector {
    private static final Set<String> USABLE_STATUSES = Set.of("completed", "partial");
    private static final Set<String> PREFERRED_REVIEW_STATUSES = Set.of("accepted", "observed");

    private final StudyRepository repository;

    public EffectiveAnalysisRunSelector(StudyRepository repository) {
        this.repository = repository;
    }

    public Optional<AiProductAnalysisRun> selectEffectiveRun(String studyId) {
        List<AiProductAnalysisRun> usable = repository.findAiProductAnalysisRunsByStudyId(studyId).stream()
            .filter(run -> USABLE_STATUSES.contains(run.status()))
            .toList();
        if (usable.isEmpty()) return Optional.empty();

        Optional<AiProductAnalysisRun> preferredReviewed = usable.stream()
            .filter(this::hasPreferredReview)
            .max(Comparator.comparing(AiProductAnalysisRun::createdAt));
        return preferredReviewed.isPresent()
            ? preferredReviewed
            : usable.stream().max(Comparator.comparing(AiProductAnalysisRun::createdAt));
    }

    private boolean hasPreferredReview(AiProductAnalysisRun run) {
        return repository.findAiProductAnalysisRunReview(run.id())
            .map(review -> PREFERRED_REVIEW_STATUSES.contains(review.reviewStatus()))
            .orElse(false);
    }
}
