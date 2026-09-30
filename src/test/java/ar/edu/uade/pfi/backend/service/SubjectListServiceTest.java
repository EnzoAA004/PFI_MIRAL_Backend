package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.SubjectSummaryDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Stage 85-V: the "patient list" projection grouped by Study.subjectRef. */
class SubjectListServiceTest {
    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final EffectiveAnalysisRunSelector runSelector = new EffectiveAnalysisRunSelector(repository);
    private final SubjectListService service = new SubjectListService(repository, runSelector);

    private Study seedStudy(String caseId, String subjectRef, LocalDate studyDate) {
        Instant now = Instant.now();
        Study study = new Study(UUID.randomUUID().toString(), caseId, "registered", subjectRef, studyDate, "MRI", "desc", "medium", now, now);
        repository.saveStudy(study);
        return study;
    }

    private AiProductAnalysisRun seedRun(Study study, String status, Instant createdAt) {
        String runId = UUID.randomUUID().toString();
        AiProductAnalysisRun run = new AiProductAnalysisRun(
            runId, study.id(), study.caseId(), "pfi.ai-product-result.v1", "post-e50-ai-product-freeze-v1",
            status, true, true, false, "trace-1", "sag-1", "ax-1", List.of(), null, createdAt, createdAt
        );
        AiDiscLevelResult discLevel = new AiDiscLevelResult(
            UUID.randomUUID().toString(), runId, 0, "L4-L5", "localized", "67B2", true, false,
            "paired", true, false, false, null, false
        );
        AiMeasurementResult measurement = new AiMeasurementResult(
            UUID.randomUUID().toString(), runId, discLevel.id(), "sagittal", "L4-L5", "disc", 12.3, 11.9, 8.0, true
        );
        repository.saveAiProductAnalysisRun(run, List.of(discLevel), List.of(measurement));
        return run;
    }

    @Test
    void zeroSubjectsReturnsEmptyList() {
        assertTrue(service.list().isEmpty());
    }

    @Test
    void oneSubjectOneStudy() {
        seedStudy("CASE-85-1", "PAT-85-1", LocalDate.of(2026, 1, 1));
        List<SubjectSummaryDto> list = service.list();
        assertEquals(1, list.size());
        assertEquals(1, list.get(0).studyCount());
    }

    @Test
    void oneSubjectMultipleStudiesCountsAll() {
        seedStudy("CASE-85-2A", "PAT-85-2", LocalDate.of(2026, 1, 1));
        seedStudy("CASE-85-2B", "PAT-85-2", LocalDate.of(2026, 3, 1));
        seedStudy("CASE-85-2C", "PAT-85-2", LocalDate.of(2026, 5, 1));
        List<SubjectSummaryDto> list = service.list();
        assertEquals(1, list.size());
        assertEquals(3, list.get(0).studyCount());
    }

    @Test
    void multipleDistinctSubjectsProduceMultipleRows() {
        seedStudy("CASE-85-3A", "PAT-85-3", LocalDate.of(2026, 1, 1));
        seedStudy("CASE-85-4A", "PAT-85-4", LocalDate.of(2026, 2, 1));
        assertEquals(2, service.list().size());
    }

    @Test
    void caseInsensitiveGroupingMergesSameSubject() {
        seedStudy("CASE-85-5A", "pat-85-5", LocalDate.of(2026, 1, 1));
        seedStudy("CASE-85-5B", "PAT-85-5", LocalDate.of(2026, 3, 1));
        List<SubjectSummaryDto> list = service.list();
        assertEquals(1, list.size());
        assertEquals(2, list.get(0).studyCount());
    }

    @Test
    void trimGroupingMergesSameSubject() {
        seedStudy("CASE-85-6A", "  PAT-85-6", LocalDate.of(2026, 1, 1));
        seedStudy("CASE-85-6B", "PAT-85-6  ", LocalDate.of(2026, 3, 1));
        List<SubjectSummaryDto> list = service.list();
        assertEquals(1, list.size());
        assertEquals(2, list.get(0).studyCount());
    }

    @Test
    void nullSubjectRefExcluded() {
        seedStudy("CASE-85-7A", null, LocalDate.of(2026, 1, 1));
        assertTrue(service.list().isEmpty());
    }

    @Test
    void blankSubjectRefExcluded() {
        seedStudy("CASE-85-8A", "   ", LocalDate.of(2026, 1, 1));
        assertTrue(service.list().isEmpty());
    }

    @Test
    void multipleNullSubjectRefStudiesNeverMergeIntoOneRow() {
        seedStudy("CASE-85-9A", null, LocalDate.of(2026, 1, 1));
        seedStudy("CASE-85-9B", null, LocalDate.of(2026, 2, 1));
        assertTrue(service.list().isEmpty());
    }

    @Test
    void latestStudySelectedByStudyDate() {
        seedStudy("CASE-85-10A", "PAT-85-10", LocalDate.of(2026, 1, 1));
        seedStudy("CASE-85-10B", "PAT-85-10", LocalDate.of(2026, 6, 1));
        seedStudy("CASE-85-10C", "PAT-85-10", LocalDate.of(2026, 3, 1));
        SubjectSummaryDto summary = service.list().get(0);
        assertEquals(LocalDate.of(2026, 6, 1), summary.latestStudyDate());
        assertEquals("CASE-85-10B", summary.latestCaseId());
    }

    @Test
    void sameDateTieBreaksDeterministicallyByCaseId() {
        seedStudy("CASE-85-11-B", "PAT-85-11", LocalDate.of(2026, 4, 1));
        seedStudy("CASE-85-11-A", "PAT-85-11", LocalDate.of(2026, 4, 1));
        SubjectSummaryDto summary = service.list().get(0);
        assertEquals("CASE-85-11-B", summary.latestCaseId());
    }

    @Test
    void studyWithoutAnalysisIncludedWithNullStatuses() {
        seedStudy("CASE-85-12A", "PAT-85-12", LocalDate.of(2026, 1, 1));
        SubjectSummaryDto summary = service.list().get(0);
        assertNull(summary.latestAnalysisStatus());
        assertNull(summary.latestReviewStatus());
    }

    @Test
    void effectiveCompletedRunReflectedInStatus() {
        Study study = seedStudy("CASE-85-13A", "PAT-85-13", LocalDate.of(2026, 1, 1));
        seedRun(study, "completed", Instant.now());
        SubjectSummaryDto summary = service.list().get(0);
        assertEquals("completed", summary.latestAnalysisStatus());
    }

    @Test
    void effectivePartialRunReflectedInStatus() {
        Study study = seedStudy("CASE-85-14A", "PAT-85-14", LocalDate.of(2026, 1, 1));
        seedRun(study, "partial", Instant.now());
        SubjectSummaryDto summary = service.list().get(0);
        assertEquals("partial", summary.latestAnalysisStatus());
    }

    @Test
    void failedRunIgnoredByEffectiveSelection() {
        Study study = seedStudy("CASE-85-15A", "PAT-85-15", LocalDate.of(2026, 1, 1));
        seedRun(study, "failed", Instant.now());
        SubjectSummaryDto summary = service.list().get(0);
        assertNull(summary.latestAnalysisStatus());
    }

    @Test
    void reviewStatusReflectedFromEffectiveRun() {
        Study study = seedStudy("CASE-85-16A", "PAT-85-16", LocalDate.of(2026, 1, 1));
        AiProductAnalysisRun run = seedRun(study, "completed", Instant.now());
        repository.upsertAiProductAnalysisRunReview(run.id(), "accepted", null, "dr@example.com", Instant.now());
        SubjectSummaryDto summary = service.list().get(0);
        assertEquals("accepted", summary.latestReviewStatus());
    }

    @Test
    void reviewStatusDefaultsToPendingWhenAbsent() {
        Study study = seedStudy("CASE-85-17A", "PAT-85-17", LocalDate.of(2026, 1, 1));
        seedRun(study, "completed", Instant.now());
        SubjectSummaryDto summary = service.list().get(0);
        assertEquals("pending", summary.latestReviewStatus());
    }

    @Test
    void orderingIsLatestStudyDateDescending() {
        seedStudy("CASE-85-18A", "PAT-85-18", LocalDate.of(2026, 1, 1));
        seedStudy("CASE-85-19A", "PAT-85-19", LocalDate.of(2026, 6, 1));
        seedStudy("CASE-85-20A", "PAT-85-20", LocalDate.of(2026, 3, 1));
        List<SubjectSummaryDto> list = service.list();
        assertEquals("PAT-85-19", list.get(0).subjectRef());
        assertEquals("PAT-85-20", list.get(1).subjectRef());
        assertEquals("PAT-85-18", list.get(2).subjectRef());
    }

    @Test
    void noDuplicateSubjectRowsAcrossManyStudies() {
        seedStudy("CASE-85-21A", "PAT-85-21", LocalDate.of(2026, 1, 1));
        seedStudy("CASE-85-21B", "PAT-85-21", LocalDate.of(2026, 2, 1));
        seedStudy("CASE-85-21C", "PAT-85-21", LocalDate.of(2026, 3, 1));
        seedStudy("CASE-85-21D", "PAT-85-21", LocalDate.of(2026, 4, 1));
        assertEquals(1, service.list().size());
    }

    @Test
    void differentSubjectsRemainSeparateEvenWithSimilarRefs() {
        seedStudy("CASE-85-22A", "PAT-85-22", LocalDate.of(2026, 1, 1));
        seedStudy("CASE-85-23A", "PAT-85-22X", LocalDate.of(2026, 1, 1));
        assertEquals(2, service.list().size());
    }

    @Test
    void listNeverIncludesClinicalFields() {
        seedStudy("CASE-85-24A", "PAT-85-24", LocalDate.of(2026, 1, 1));
        SubjectSummaryDto summary = service.list().get(0);
        String fields = summary.getClass().toString();
        assertTrue(!fields.toLowerCase().contains("diagnos"));
    }

    @Test
    void studyCountReflectsOnlyStudiesOfThatSubject() {
        seedStudy("CASE-85-25A", "PAT-85-25", LocalDate.of(2026, 1, 1));
        seedStudy("CASE-85-25B", "PAT-85-25", LocalDate.of(2026, 2, 1));
        seedStudy("CASE-85-26A", "PAT-85-26", LocalDate.of(2026, 1, 1));
        List<SubjectSummaryDto> list = service.list();
        SubjectSummaryDto pat25 = list.stream().filter(s -> s.subjectRef().equals("PAT-85-25")).findFirst().orElseThrow();
        assertEquals(2, pat25.studyCount());
    }

    @Test
    void displayedSubjectRefUsesLatestStudyRawCasingNotLowercasedKey() {
        seedStudy("CASE-85-27A", "Pat-85-27", LocalDate.of(2026, 1, 1));
        SubjectSummaryDto summary = service.list().get(0);
        assertEquals("Pat-85-27", summary.subjectRef());
    }
}
