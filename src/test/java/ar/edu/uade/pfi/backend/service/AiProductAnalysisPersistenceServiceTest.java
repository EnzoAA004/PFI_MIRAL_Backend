package ar.edu.uade.pfi.backend.service;
import ar.edu.uade.pfi.backend.service.exception.StudyNotFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisResultDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeResponseDto;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stage 76: full orchestration — resolve inputs, call the AI Module, persist, and read back —
 * using {@link InMemoryStudyRepository} (no real database or FastAPI needed).
 */
class AiProductAnalysisPersistenceServiceTest {
    private static class FakeAi implements AiServiceOperations {
        private final String status;

        FakeAi(String status) {
            this.status = status;
        }

        @Override
        public Map<String, Object> health() {
            return Map.of();
        }

        @Override
        public Object models() {
            return Map.of();
        }

        @Override
        public Map<String, Object> verifyModels() {
            return Map.of();
        }

        @Override
        public Map<String, Object> warmup() {
            return Map.of();
        }

        @Override
        public Map<String, Object> runPipeline(PipelineRunRequestDto request) {
            return Map.of();
        }

        @Override
        public Map<String, Object> getAgentReport(String runId) {
            return Map.of();
        }

        @Override
        public Map<String, Object> getAgentReportSummary(String runId) {
            return Map.of();
        }

        @Override
        public Map<String, Object> getRecentAgentReports(int limit) {
            return Map.of();
        }

        @Override
        public AiProductAnalyzeResponseDto analyzeProduct(AiProductAnalyzeRequestDto request) {
            return new AiProductAnalyzeResponseDto(
                "pfi.ai-product-result.v1",
                "product-1",
                "trace-1",
                request.caseId(),
                new AiProductAnalyzeResponseDto.Release("post-e50-ai-product-freeze-v1"),
                status,
                null,
                List.of(),
                List.of("67B2_RUNTIME_PARITY_NOT_PROVEN"),
                new AiProductAnalyzeResponseDto.Review(true, "pending", true),
                true,
                false,
                true
            );
        }
    }

    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final AiProductAnalysisMapper mapper = new AiProductAnalysisMapper();

    private Study seedStudyWithSagittalInput(String caseId) {
        Instant now = Instant.now();
        Study study = new Study(UUID.randomUUID().toString(), caseId, "registered", now, now);
        repository.saveStudy(study);
        repository.saveInput(new InputResource(
            UUID.randomUUID().toString(), study.id(), "sagittal", "input-sag-1", "dicom", 100L,
            "series", "t2", 20, false, false, true, now
        ));
        return study;
    }

    private AiProductAnalysisPersistenceService serviceWithStatus(String status) {
        AiAnalysisService analysisService = new AiAnalysisService(new FakeAi(status));
        AiStudyInputResolver resolver = new AiStudyInputResolver(repository);
        return new AiProductAnalysisPersistenceService(analysisService, resolver, repository, mapper);
    }

    @Test
    void analyzePersistsRunAndReturnsAnalysisRunId() {
        seedStudyWithSagittalInput("case-1");
        AiProductAnalysisResultDto result = serviceWithStatus("completed").analyze("case-1", Map.of());

        assertTrue(result.analysisRunId() != null && !result.analysisRunId().isBlank());
        assertEquals("completed", result.status());
    }

    @Test
    void completedStatusPersistedExactly() {
        seedStudyWithSagittalInput("case-1");
        AiProductAnalysisResultDto result = serviceWithStatus("completed").analyze("case-1", Map.of());

        assertEquals("completed", result.status());
    }

    @Test
    void partialStatusPersistedExactlyAndStaysHttpValid() {
        seedStudyWithSagittalInput("case-1");
        AiProductAnalysisResultDto result = serviceWithStatus("partial").analyze("case-1", Map.of());

        assertEquals("partial", result.status());
    }

    @Test
    void failedStatusPersistedExactly() {
        seedStudyWithSagittalInput("case-1");
        AiProductAnalysisResultDto result = serviceWithStatus("failed").analyze("case-1", Map.of());

        assertEquals("failed", result.status());
    }

    @Test
    void warningsPersistedAndReadableFromHistory() {
        seedStudyWithSagittalInput("case-1");
        serviceWithStatus("partial").analyze("case-1", Map.of());

        AiProductAnalysisResultDto fromHistory = serviceWithStatus("partial").history("case-1").get(0);
        assertEquals(List.of("67B2_RUNTIME_PARITY_NOT_PROVEN"), fromHistory.warnings());
    }

    @Test
    void multipleRunsAllowedForSameStudyNeverOverwritten() {
        seedStudyWithSagittalInput("case-1");
        AiProductAnalysisPersistenceService service = serviceWithStatus("completed");

        AiProductAnalysisResultDto first = service.analyze("case-1", Map.of());
        AiProductAnalysisResultDto second = service.analyze("case-1", Map.of());

        assertNotEquals(first.analysisRunId(), second.analysisRunId());
        assertEquals(2, service.history("case-1").size());
    }

    @Test
    void historyReturnsNewestRunFirst() throws InterruptedException {
        seedStudyWithSagittalInput("case-1");
        AiProductAnalysisPersistenceService service = serviceWithStatus("completed");
        service.analyze("case-1", Map.of());
        Thread.sleep(5);
        AiProductAnalysisResultDto latest = service.analyze("case-1", Map.of());

        assertEquals(latest.analysisRunId(), service.history("case-1").get(0).analysisRunId());
    }

    @Test
    void detailReturns404ForNonexistentAnalysisId() {
        seedStudyWithSagittalInput("case-1");
        AiProductAnalysisPersistenceService service = serviceWithStatus("completed");

        assertThrows(ResponseStatusException.class, () -> service.detail("case-1", "does-not-exist"));
    }

    @Test
    void detailReturns404ForAnalysisBelongingToADifferentStudy() {
        seedStudyWithSagittalInput("case-1");
        seedStudyWithSagittalInput("case-2");
        AiProductAnalysisPersistenceService service = serviceWithStatus("completed");
        AiProductAnalysisResultDto createdUnderCaseOne = service.analyze("case-1", Map.of());

        assertThrows(ResponseStatusException.class, () -> service.detail("case-2", createdUnderCaseOne.analysisRunId()));
    }

    @Test
    void analyzeThrowsFunctionalErrorWhenStudyHasNoAnalyzableInput() {
        Instant now = Instant.now();
        Study study = new Study(UUID.randomUUID().toString(), "case-empty", "registered", now, now);
        repository.saveStudy(study);
        AiProductAnalysisPersistenceService service = serviceWithStatus("completed");

        assertThrows(AiStudyInputUnresolvedException.class, () -> service.analyze("case-empty", Map.of()));
    }

    @Test
    void historyOnUnknownCaseThrowsStudyNotFound() {
        AiProductAnalysisPersistenceService service = serviceWithStatus("completed");

        assertThrows(StudyNotFoundException.class, () -> service.history("does-not-exist"));
    }

    @Test
    void humanReviewRequiredAndSafetyFlagsPersistedLiterally() {
        seedStudyWithSagittalInput("case-1");
        AiProductAnalysisResultDto result = serviceWithStatus("completed").analyze("case-1", Map.of());

        assertTrue(result.humanReviewRequired());
        assertTrue(result.notClinicalDiagnosis());
        assertEquals(false, result.autonomousDiagnosis());
        assertEquals("pending", result.review().status());
    }
}
