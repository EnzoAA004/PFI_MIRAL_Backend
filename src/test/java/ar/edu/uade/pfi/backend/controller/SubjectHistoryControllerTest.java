package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.MeasurementSeriesDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.EffectiveAnalysisRunSelector;
import ar.edu.uade.pfi.backend.service.EffectiveMeasurementResolver;
import ar.edu.uade.pfi.backend.service.PatientHistoryService;
import ar.edu.uade.pfi.backend.service.PatientMeasurementSeriesService;
import ar.edu.uade.pfi.backend.service.ProfessionalAccessAuditService;
import ar.edu.uade.pfi.backend.service.ReviewStoreService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Stage 84: wiring test for the new N-Study endpoints alongside the pre-existing /history one. */
class SubjectHistoryControllerTest {
    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final EffectiveAnalysisRunSelector runSelector = new EffectiveAnalysisRunSelector(repository);
    private final EffectiveMeasurementResolver resolver = new EffectiveMeasurementResolver(repository);

    private SubjectHistoryController buildController() {
        ProfessionalAccessAuditService auditService = new ProfessionalAccessAuditService(mock(ReviewStoreService.class));
        return new SubjectHistoryController(
            new PatientHistoryService(repository, runSelector),
            new PatientMeasurementSeriesService(repository, runSelector, resolver),
            auditService
        );
    }

    private Study seedStudy(String caseId, String subjectRef, LocalDate studyDate) {
        Instant now = Instant.now();
        Study study = new Study(UUID.randomUUID().toString(), caseId, "registered", subjectRef, studyDate, "MRI", "desc", "medium", now, now);
        repository.saveStudy(study);
        return study;
    }

    private void seedRun(Study study, String level, Double majorAxisMm) {
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        AiProductAnalysisRun run = new AiProductAnalysisRun(
            runId, study.id(), study.caseId(), "pfi.ai-product-result.v1", "post-e50-ai-product-freeze-v1",
            "completed", true, true, false, "trace-1", "sag-1", "ax-1", List.of(), null, now, now
        );
        AiDiscLevelResult discLevel = new AiDiscLevelResult(
            UUID.randomUUID().toString(), runId, 0, level, "localized", "67B2", true, false,
            "paired", true, false, false, null, false
        );
        AiMeasurementResult measurement = new AiMeasurementResult(
            UUID.randomUUID().toString(), runId, discLevel.id(), "sagittal", level, "disc", 12.3, majorAxisMm, 8.0, true
        );
        repository.saveAiProductAnalysisRun(run, List.of(discLevel), List.of(measurement));
    }

    private jakarta.servlet.http.HttpServletRequest fakeRequest() {
        return mock(jakarta.servlet.http.HttpServletRequest.class);
    }

    @Test
    void historyEndpointStillReturnsTheExistingShape() {
        seedStudy("CASE-84-CTRL-1", "PAT-84CTRL-1", LocalDate.of(2026, 2, 1));
        Map<String, Object> history = buildController().history("PAT-84CTRL-1", fakeRequest());
        assertEquals("ok", history.get("status"));
    }

    @Test
    void availableSeriesKeysEndpointReturnsKeysWithEnoughPoints() {
        Study a = seedStudy("CASE-84-CTRL-2", "PAT-84CTRL-2", LocalDate.of(2026, 2, 1));
        Study b = seedStudy("CASE-84-CTRL-3", "PAT-84CTRL-2", LocalDate.of(2026, 5, 10));
        seedRun(a, "L4-L5", 11.9);
        seedRun(b, "L4-L5", 12.3);

        List<MeasurementSeriesDto.MeasurementSeriesKeyDto> keys = buildController().availableSeriesKeys("PAT-84CTRL-2", fakeRequest());
        assertTrue(keys.stream().anyMatch(k -> "L4-L5".equals(k.level()) && "majorAxisMm".equals(k.metric())));
    }

    @Test
    void measurementSeriesEndpointReturnsPointsAcrossStudies() {
        Study a = seedStudy("CASE-84-CTRL-4", "PAT-84CTRL-3", LocalDate.of(2026, 2, 1));
        Study b = seedStudy("CASE-84-CTRL-5", "PAT-84CTRL-3", LocalDate.of(2026, 5, 10));
        seedRun(a, "L4-L5", 11.9);
        seedRun(b, "L4-L5", 12.3);

        MeasurementSeriesDto series = buildController().measurementSeries("PAT-84CTRL-3", "L4-L5", "sagittal", "disc", "majorAxisMm", fakeRequest());
        assertEquals(2, series.points().size());
    }

    @Test
    void measurementSeriesEndpointExcludesADifferentSubjectRef() {
        Study a = seedStudy("CASE-84-CTRL-6", "PAT-84CTRL-4", LocalDate.of(2026, 2, 1));
        Study other = seedStudy("CASE-84-CTRL-7", "PAT-84CTRL-OTHER", LocalDate.of(2026, 5, 10));
        seedRun(a, "L4-L5", 11.9);
        seedRun(other, "L4-L5", 99.0);

        MeasurementSeriesDto series = buildController().measurementSeries("PAT-84CTRL-4", "L4-L5", "sagittal", "disc", "majorAxisMm", fakeRequest());
        assertEquals(1, series.points().size());
        assertEquals("CASE-84-CTRL-6", series.points().get(0).caseId());
    }

    @Test
    void historyReflectsSubjectRefTrimAndCase() {
        seedStudy("CASE-84-CTRL-8", "PAT-84CTRL-5", LocalDate.of(2026, 2, 1));
        Map<String, Object> history = buildController().history("  pat-84ctrl-5  ", fakeRequest());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> studies = (List<Map<String, Object>>) history.get("studies");
        assertEquals(1, studies.size());
    }
}
