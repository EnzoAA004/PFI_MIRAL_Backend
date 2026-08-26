package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiMeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeResponseDto;
import ar.edu.uade.pfi.backend.dto.ChatContextResult;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Stage 80-D: the structured chat context — 80-AI items 1-8/28-31. */
class StudyChatContextBuilderTest {
    private static class FakeAi implements AiServiceOperations {
        @Override public Map<String, Object> health() { return Map.of(); }
        @Override public Object models() { return Map.of(); }
        @Override public Map<String, Object> verifyModels() { return Map.of(); }
        @Override public Map<String, Object> warmup() { return Map.of(); }
        @Override public Map<String, Object> runPipeline(PipelineRunRequestDto request) { return Map.of(); }
        @Override public Map<String, Object> getAgentReport(String runId) { return Map.of(); }
        @Override public Map<String, Object> getAgentReportSummary(String runId) { return Map.of(); }
        @Override public Map<String, Object> getRecentAgentReports(int limit) { return Map.of(); }
        @Override public AiProductAnalyzeResponseDto analyzeProduct(AiProductAnalyzeRequestDto request) {
            throw new UnsupportedOperationException("StudyChatContextBuilder must never trigger a new AI call");
        }
    }

    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final EffectiveAnalysisRunSelector runSelector = new EffectiveAnalysisRunSelector(repository);
    private final LongitudinalComparisonService longitudinalComparisonService = new LongitudinalComparisonService(repository, runSelector);
    private final AiProductAnalysisPersistenceService persistenceService = new AiProductAnalysisPersistenceService(
        new AiAnalysisService(new FakeAi()), new AiStudyInputResolver(repository), repository, new AiProductAnalysisMapper()
    );
    private final StudyChatContextBuilder builder = new StudyChatContextBuilder(repository, persistenceService, runSelector, longitudinalComparisonService);

    private Study seedStudy(String caseId, String subjectRef, LocalDate studyDate) {
        Instant now = Instant.now();
        Study study = new Study(UUID.randomUUID().toString(), caseId, "registered", subjectRef, studyDate, "MRI", "desc", "medium", now, now);
        repository.saveStudy(study);
        return study;
    }

    private record SeededMeasurement(AiProductAnalysisRun run, AiDiscLevelResult discLevel, AiMeasurementResult measurement) {
    }

    private SeededMeasurement seedRunWithMeasurement(Study study, String level, Double areaMm2, String degenerativeFindingsStatus, String axialPairingStatus, boolean axialPairingValidated, boolean axialPairingExperimental) {
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        AiProductAnalysisRun run = new AiProductAnalysisRun(
            runId, study.id(), study.caseId(), "pfi.ai-product-result.v1", "post-e50-ai-product-freeze-v1",
            "completed", true, true, false, "trace-1", "sag-1", "ax-1", List.of("67B2_RUNTIME_PARITY_NOT_PROVEN"), null, now, now
        );
        AiDiscLevelResult discLevel = new AiDiscLevelResult(
            UUID.randomUUID().toString(), runId, 0, level, "localized", "67B2", true, false,
            axialPairingStatus, axialPairingValidated, axialPairingExperimental, true, degenerativeFindingsStatus, false
        );
        AiMeasurementResult measurement = new AiMeasurementResult(UUID.randomUUID().toString(), runId, discLevel.id(), "sagittal", level, "disc", areaMm2, null, null, true);
        repository.saveAiProductAnalysisRun(run, List.of(discLevel), List.of(measurement));
        return new SeededMeasurement(run, discLevel, measurement);
    }

    @Test
    void buildsContextForCurrentStudy() {
        Study study = seedStudy("case-1", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(study, "L4-L5", 12.3, null, "paired", true, false);

        ChatContextResult context = builder.build(study, null, null);
        assertEquals("case-1", context.study().caseId());
        assertEquals("2026-08-25", context.study().studyDate());
    }

    @Test
    void includesAnalysisWhenAUsableRunExists() {
        Study study = seedStudy("case-1", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(study, "L4-L5", 12.3, null, "paired", true, false);

        ChatContextResult context = builder.build(study, null, null);
        assertEquals("completed", context.analysis().status());
        assertTrue(context.contextSources().contains("current_analysis"));
    }

    @Test
    void includesCorrectedMeasurements() {
        Study study = seedStudy("case-1", "PAT-1", LocalDate.of(2026, 8, 25));
        SeededMeasurement seed = seedRunWithMeasurement(study, "L4-L5", 12.3, null, "paired", true, false);
        repository.saveAiMeasurementCorrection(new AiMeasurementCorrection(
            UUID.randomUUID().toString(), seed.measurement().id(), seed.run().id(), "areaMm2", 12.3, 11.8, "mm2", null, "doctor-1", Instant.now()
        ));

        ChatContextResult context = builder.build(study, null, null);
        var measurement = context.discLevels().get(0).measurements().get(0);
        assertEquals(11.8, measurement.effectiveValue());
        assertEquals(12.3, measurement.automaticValue(), "el valor automatico original nunca se pierde");
        assertEquals("professional_correction", measurement.effectiveSource());
        assertTrue(context.contextSources().contains("professional_correction"));
    }

    @Test
    void includesProfessionalReview() {
        Study study = seedStudy("case-1", "PAT-1", LocalDate.of(2026, 8, 25));
        SeededMeasurement seed = seedRunWithMeasurement(study, "L4-L5", 12.3, null, "paired", true, false);
        repository.upsertAiProductAnalysisRunReview(seed.run().id(), "accepted", "revisado ok", "doctor-1", Instant.now());

        ChatContextResult context = builder.build(study, null, null);
        assertEquals("accepted", context.analysis().reviewStatus());
        assertEquals("revisado ok", context.analysis().reviewComment());
        assertTrue(context.contextSources().contains("professional_review"));
    }

    @Test
    void includesLongitudinalComparisonWhenRequested() {
        Study baseline = seedStudy("case-baseline", "PAT-1", LocalDate.of(2026, 5, 10));
        Study current = seedStudy("case-current", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(baseline, "L4-L5", 12.3, null, "paired", true, false);
        seedRunWithMeasurement(current, "L4-L5", 13.1, null, "paired", true, false);

        ChatContextResult context = builder.build(current, null, "case-baseline");
        assertEquals("case-baseline", context.longitudinalComparison().baselineCaseId());
        assertTrue(context.contextSources().contains("longitudinal_comparison"));
    }

    @Test
    void excludesRawPatientIdentity() {
        // Structural guarantee: ChatContextStudy has no field carrying subjectRef/patientId at all.
        var fields = ChatContextResult.ChatContextStudy.class.getRecordComponents();
        for (var field : fields) {
            String name = field.getName().toLowerCase();
            assertFalse(name.contains("subject"), "no debe existir un campo de identidad del paciente: " + name);
            assertFalse(name.contains("patient"), "no debe existir un campo de identidad del paciente: " + name);
        }
    }

    @Test
    void excludesUidsAndInternalIds() {
        // No analysisRunId/discLevelId/measurementId/studyId/UID field exists on any context type.
        for (Class<?> type : List.of(
            ChatContextResult.class, ChatContextResult.ChatContextStudy.class, ChatContextResult.ChatContextAnalysis.class,
            ChatContextResult.ChatContextDiscLevel.class, ChatContextResult.ChatContextMeasurement.class
        )) {
            for (var field : type.getRecordComponents()) {
                String name = field.getName().toLowerCase();
                assertFalse(name.contains("uid"), type.getSimpleName() + "." + name);
                assertFalse(name.equals("studyid"), type.getSimpleName() + "." + name);
                assertFalse(name.equals("analysisrunid"), type.getSimpleName() + "." + name);
                assertFalse(name.equals("measurementid"), type.getSimpleName() + "." + name);
                assertFalse(name.equals("disclevelid"), type.getSimpleName() + "." + name);
            }
        }
    }

    @Test
    void excludesFilesystemPathsAndTokens() {
        // Structural: no field named path/token/hostname/checkpoint exists anywhere in the context tree.
        for (var field : ChatContextResult.ChatContextAnalysis.class.getRecordComponents()) {
            String name = field.getName().toLowerCase();
            assertFalse(name.contains("path"));
            assertFalse(name.contains("token"));
            assertFalse(name.contains("host"));
        }
    }

    @Test
    void noLongitudinalContextWhenNotRequested() {
        Study study = seedStudy("case-1", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(study, "L4-L5", 12.3, null, "paired", true, false);

        ChatContextResult context = builder.build(study, null, null);
        assertNull(context.longitudinalComparison());
        assertFalse(context.contextSources().contains("longitudinal_comparison"));
    }

    @Test
    void nullLevelsAreHandledWithoutError() {
        Study study = seedStudy("case-1", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(study, null, 12.3, "blocked_preprocessing_parity", "not_requested", false, false);

        ChatContextResult context = builder.build(study, null, null);
        assertNull(context.discLevels().get(0).level());
    }

    @Test
    void blockedP10_7IsRepresentedVerbatimAsUnavailableNeverAsANegativeFinding() {
        Study study = seedStudy("case-1", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(study, "L4-L5", 12.3, "blocked_preprocessing_parity", "paired", true, false);

        ChatContextResult context = builder.build(study, null, null);
        assertEquals("blocked_preprocessing_parity", context.discLevels().get(0).degenerativeFindingsStatus());
    }

    @Test
    void sixtySevenCExperimentalStatusIsPreservedInContext() {
        Study study = seedStudy("case-1", "PAT-1", LocalDate.of(2026, 8, 25));
        seedRunWithMeasurement(study, "L4-L5", 12.3, null, "experimental", false, true);

        ChatContextResult context = builder.build(study, null, null);
        assertEquals("experimental", context.discLevels().get(0).axialPairingStatus());
        assertFalse(context.discLevels().get(0).axialPairingValidated());
        assertTrue(context.discLevels().get(0).axialPairingExperimental());
    }
}
