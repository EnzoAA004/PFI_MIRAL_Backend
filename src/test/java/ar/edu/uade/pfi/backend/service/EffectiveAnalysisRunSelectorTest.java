package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Stage 79-C: the deterministic run-selection rule, in isolation from the comparison logic. */
class EffectiveAnalysisRunSelectorTest {
    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final EffectiveAnalysisRunSelector selector = new EffectiveAnalysisRunSelector(repository);

    private Study seedStudy() {
        Instant now = Instant.now();
        Study study = new Study(UUID.randomUUID().toString(), "case-" + UUID.randomUUID(), "registered", now, now);
        repository.saveStudy(study);
        return study;
    }

    private AiProductAnalysisRun seedRun(Study study, String status, Instant createdAt) {
        AiProductAnalysisRun run = new AiProductAnalysisRun(
            UUID.randomUUID().toString(), study.id(), study.caseId(), "pfi.ai-product-result.v1",
            "post-e50-ai-product-freeze-v1", status, true, true, false, "trace-1",
            "sag-1", "ax-1", List.of(), null, createdAt, createdAt
        );
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());
        return run;
    }

    @Test
    void noRunsMeansNoSelection() {
        Study study = seedStudy();
        assertEquals(Optional.empty(), selector.selectEffectiveRun(study.id()));
    }

    @Test
    void failedRunIsNeverSelected() {
        Study study = seedStudy();
        seedRun(study, "failed", Instant.parse("2026-08-25T10:00:00Z"));
        assertEquals(Optional.empty(), selector.selectEffectiveRun(study.id()));
    }

    @Test
    void mostRecentUsableRunIsSelectedWhenNoneReviewed() {
        Study study = seedStudy();
        seedRun(study, "completed", Instant.parse("2026-08-25T09:00:00Z"));
        AiProductAnalysisRun newest = seedRun(study, "partial", Instant.parse("2026-08-25T10:00:00Z"));

        assertEquals(newest.id(), selector.selectEffectiveRun(study.id()).orElseThrow().id());
    }

    @Test
    void acceptedReviewedRunPreferredOverNewerUnreviewedRun() {
        Study study = seedStudy();
        AiProductAnalysisRun reviewed = seedRun(study, "completed", Instant.parse("2026-08-25T09:00:00Z"));
        repository.upsertAiProductAnalysisRunReview(reviewed.id(), "accepted", null, "doctor-1", Instant.now());
        seedRun(study, "completed", Instant.parse("2026-08-25T11:00:00Z")); // newer, but never reviewed

        assertEquals(reviewed.id(), selector.selectEffectiveRun(study.id()).orElseThrow().id());
    }

    @Test
    void observedReviewedRunAlsoPreferred() {
        Study study = seedStudy();
        AiProductAnalysisRun reviewed = seedRun(study, "partial", Instant.parse("2026-08-25T09:00:00Z"));
        repository.upsertAiProductAnalysisRunReview(reviewed.id(), "observed", "seguimiento", "doctor-1", Instant.now());
        seedRun(study, "completed", Instant.parse("2026-08-25T11:00:00Z"));

        assertEquals(reviewed.id(), selector.selectEffectiveRun(study.id()).orElseThrow().id());
    }

    @Test
    void mostRecentAmongMultipleReviewedRunsWins() {
        Study study = seedStudy();
        AiProductAnalysisRun olderReviewed = seedRun(study, "completed", Instant.parse("2026-08-25T09:00:00Z"));
        repository.upsertAiProductAnalysisRunReview(olderReviewed.id(), "accepted", null, "doctor-1", Instant.now());
        AiProductAnalysisRun newerReviewed = seedRun(study, "completed", Instant.parse("2026-08-25T10:00:00Z"));
        repository.upsertAiProductAnalysisRunReview(newerReviewed.id(), "observed", null, "doctor-1", Instant.now());

        assertEquals(newerReviewed.id(), selector.selectEffectiveRun(study.id()).orElseThrow().id());
    }

    @Test
    void rejectedReviewDoesNotExcludeARunNorPreferItOverANewerOne() {
        Study study = seedStudy();
        AiProductAnalysisRun rejected = seedRun(study, "completed", Instant.parse("2026-08-25T09:00:00Z"));
        repository.upsertAiProductAnalysisRunReview(rejected.id(), "rejected", "no es confiable", "doctor-1", Instant.now());
        AiProductAnalysisRun newer = seedRun(study, "completed", Instant.parse("2026-08-25T11:00:00Z"));

        // "rejected" is not a preferred-review status, so plain recency applies -- the newer run wins.
        assertEquals(newer.id(), selector.selectEffectiveRun(study.id()).orElseThrow().id());
    }

    @Test
    void pendingReviewIsNotPreferred() {
        Study study = seedStudy();
        AiProductAnalysisRun pending = seedRun(study, "completed", Instant.parse("2026-08-25T09:00:00Z"));
        repository.upsertAiProductAnalysisRunReview(pending.id(), "pending", null, null, null);
        AiProductAnalysisRun newer = seedRun(study, "completed", Instant.parse("2026-08-25T11:00:00Z"));

        assertEquals(newer.id(), selector.selectEffectiveRun(study.id()).orElseThrow().id());
    }

    @Test
    void partialRunIsUsable() {
        Study study = seedStudy();
        AiProductAnalysisRun run = seedRun(study, "partial", Instant.now());
        assertTrue(selector.selectEffectiveRun(study.id()).isPresent());
        assertEquals(run.id(), selector.selectEffectiveRun(study.id()).orElseThrow().id());
    }

    @Test
    void completedRunIsUsable() {
        Study study = seedStudy();
        AiProductAnalysisRun run = seedRun(study, "completed", Instant.now());
        assertEquals(run.id(), selector.selectEffectiveRun(study.id()).orElseThrow().id());
    }

    @Test
    void onlyFailedRunsMeansNoUsableAnalysis() {
        Study study = seedStudy();
        seedRun(study, "failed", Instant.now());
        seedRun(study, "failed", Instant.now());
        assertEquals(Optional.empty(), selector.selectEffectiveRun(study.id()));
    }
}
