package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRunReview;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.SubjectSummaryDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Stage 85: the "patient list" the Frontend needs is a projection/aggregate over
 * {@code Study.subjectRef} — never a persisted {@code Patient} entity (same closed architecture
 * decision as Stage 84). This is the Backend-side grouping that {@code /pacientes} needs instead
 * of the never-implemented {@code GET /api/patients} the old, now-retired {@code PatientDetailView}
 * called (Stage 84 finding).
 *
 * <p>85-S: implemented as a single {@code findAllStudies()} query followed by in-memory grouping,
 * not N+1 per-subject queries — deliberate, documented decision: this MVP's realistic dataset
 * (a handful of Studies across a handful of subjects) makes a single full scan cheaper and
 * simpler than a paginated/joined query, and {@code StudyRepository} already loads Studies this
 * way elsewhere (e.g. the legacy worklist). Only the *effective-analysis* lookup for each
 * subject's single latest Study is a per-subject query (via {@link EffectiveAnalysisRunSelector}
 * + a review lookup) — bounded by the number of *distinct subjects*, not the number of Studies.
 */
@Service
public class SubjectListService {
    private final StudyRepository repository;
    private final EffectiveAnalysisRunSelector runSelector;

    public SubjectListService(StudyRepository repository, EffectiveAnalysisRunSelector runSelector) {
        this.repository = repository;
        this.runSelector = runSelector;
    }

    /**
     * Stage 85-D: fail closed. A {@code null}/blank {@code subjectRef} never forms a "patient" row
     * — it is excluded entirely, never grouped into a placeholder "unknown patient" that would
     * silently merge unrelated Studies.
     *
     * <p>Stage 85-C: grouping key is trimmed + lowercased (the same case-insensitive rule Stage 84
     * already uses via {@code findStudiesBySubjectRef}'s SQL) — but the *displayed* {@code subjectRef}
     * is the latest Study's own raw value (not lowercased), so navigation back into
     * {@code GET /api/subjects/{subjectRef}/history} always uses a real, existing casing.
     */
    public List<SubjectSummaryDto> list() {
        Map<String, List<Study>> bySubject = new LinkedHashMap<>();
        for (Study study : repository.findAllStudies()) {
            String key = normalizeKey(study.subjectRef());
            if (key == null) continue;
            bySubject.computeIfAbsent(key, ignored -> new ArrayList<>()).add(study);
        }

        List<SubjectSummaryDto> result = new ArrayList<>();
        for (List<Study> studiesInSubject : bySubject.values()) {
            result.add(toSummary(studiesInSubject));
        }
        result.sort(latestStudyDateDescendingThenSubjectRef());
        return result;
    }

    private SubjectSummaryDto toSummary(List<Study> studies) {
        Study latest = studies.stream().max(studyDateAscendingThenCaseId()).orElseThrow();
        AiProductAnalysisRun effectiveRun = runSelector.selectEffectiveRun(latest.id()).orElse(null);
        String latestReviewStatus = effectiveRun == null ? null
            : repository.findAiProductAnalysisRunReview(effectiveRun.id())
                .map(AiProductAnalysisRunReview::reviewStatus)
                .orElse("pending");
        return new SubjectSummaryDto(
            latest.subjectRef().trim(),
            studies.size(),
            latest.studyDate(),
            latest.caseId(),
            effectiveRun == null ? null : effectiveRun.status(),
            latestReviewStatus
        );
    }

    /** Stage 85-F: same "latest Study" rule as Stage 84 -- studyDate wins, a null date is never "latest" over a real one, deterministic tie-break by caseId. */
    private Comparator<Study> studyDateAscendingThenCaseId() {
        return Comparator
            .comparing(Study::studyDate, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Study::caseId);
    }

    /** Stage 85-K: most recently active subjects first; a null latestStudyDate sorts last, deterministic tie-break by subjectRef. */
    private Comparator<SubjectSummaryDto> latestStudyDateDescendingThenSubjectRef() {
        return Comparator
            .comparing(SubjectSummaryDto::latestStudyDate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(SubjectSummaryDto::subjectRef);
    }

    private String normalizeKey(String subjectRef) {
        return subjectRef == null || subjectRef.isBlank() ? null : subjectRef.trim().toLowerCase(Locale.ROOT);
    }
}
