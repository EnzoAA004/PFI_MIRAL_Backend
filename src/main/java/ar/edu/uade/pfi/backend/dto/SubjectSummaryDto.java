package ar.edu.uade.pfi.backend.dto;

import java.time.LocalDate;

/**
 * Stage 85-B/85-T: one row of the patient-list projection — a lightweight aggregate over
 * {@code Study.subjectRef}, never a persisted entity. {@code latestAnalysisStatus}/
 * {@code latestReviewStatus} describe the {@code latestCaseId} Study's own effective analysis
 * (via {@link ar.edu.uade.pfi.backend.service.EffectiveAnalysisRunSelector}, Stage 79) — the same
 * selection rule Stage 84's history/series and Stage 80's chat already use, so this list is never
 * a second, divergent source of truth about "which run represents a Study."
 *
 * <p>Deliberately does not carry any Study/measurement/correction detail — that is Stage 84's
 * job ({@code GET /api/subjects/{subjectRef}/history}), not this list's.
 */
public record SubjectSummaryDto(
    String subjectRef,
    int studyCount,
    LocalDate latestStudyDate,
    String latestCaseId,
    /** {@code null} when the latest Study has no effective analysis run yet (85: a normal state, not an error). */
    String latestAnalysisStatus,
    /** {@code null} under the same condition as {@code latestAnalysisStatus}; otherwise pending|accepted|observed|rejected. */
    String latestReviewStatus
) {
}
