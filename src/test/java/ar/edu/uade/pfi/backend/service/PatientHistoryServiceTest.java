package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiMeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PatientHistoryServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void usesDomainStudiesRealMeasurementsCorrectionsAndOrderingOnly() {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        StudyRunService studyRunService = new StudyRunService(repository);
        Study older = studyRunService.upsertStudyMetadata("CASE-SPIDER-OLD", "created", new ar.edu.uade.pfi.backend.dto.StudyMetadataDto(
            "SPIDER-101", LocalDate.parse("2026-06-01"), "MRI", "Old study", "medium"
        ));
        Study newer = studyRunService.upsertStudyMetadata("CASE-SPIDER-NEW", "created", new ar.edu.uade.pfi.backend.dto.StudyMetadataDto(
            "SPIDER-101", LocalDate.parse("2026-07-26"), "MRI", "New study", "high"
        ));
        Study other = studyRunService.upsertStudyMetadata("CASE-OTHER", "created", new ar.edu.uade.pfi.backend.dto.StudyMetadataDto(
            "SPIDER-999", LocalDate.parse("2026-07-27"), "MRI", "Other study", "medium"
        ));

        var newerRun = studyRunService.createRunWithId(
            UUID.randomUUID().toString(),
            newer,
            "multi-new",
            "trace-new",
            "real_baseline",
            "real_baseline",
            "sagittal_spider",
            "",
            "sha256:sag",
            "",
            "run-sag-new",
            "",
            Map.of(),
            Map.of("planes", Map.of("sagittal", Map.of("measurements", List.of(
                Map.of("id", "disc-height-l45", "labelKey", "Disc Height", "value", 8.2, "unit", "mm")
            )))),
            List.of(),
            "completed",
            "accepted",
            "dra-demo",
            Instant.parse("2026-07-26T12:00:00Z"),
            "Aceptado"
        );
        studyRunService.createRunWithId(
            UUID.randomUUID().toString(),
            older,
            "multi-old",
            "trace-old",
            "real_baseline",
            "real_baseline",
            "sagittal_spider",
            "",
            "sha256:sag",
            "",
            "run-sag-old",
            "",
            Map.of(),
            Map.of(),
            List.of(),
            "completed",
            "pending",
            "",
            null,
            ""
        );
        studyRunService.createRunWithId(
            UUID.randomUUID().toString(),
            other,
            "multi-other",
            "trace-other",
            "real_baseline",
            "real_baseline",
            "sagittal_spider",
            "",
            "sha256:sag",
            "",
            "run-sag-other",
            "",
            Map.of(),
            Map.of(),
            List.of(),
            "completed",
            "pending",
            "",
            null,
            ""
        );
        repository.saveReview(
            "multi-new",
            "edited",
            "dra-demo",
            Instant.parse("2026-07-26T13:00:00Z"),
            "Correccion registrada",
            List.of(new MeasurementCorrection(
                UUID.randomUUID().toString(),
                newerRun.id(),
                "disc-height-l45",
                "Disc Height",
                Map.of("value", 8.2, "unit", "mm"),
                Map.of("value", 8.5, "unit", "mm"),
                "Ajuste profesional",
                Instant.parse("2026-07-26T13:00:00Z")
            ))
        );

        Map<String, Object> history = new PatientHistoryService(repository, new EffectiveAnalysisRunSelector(repository)).history("SPIDER-101");
        List<Map<String, Object>> studies = (List<Map<String, Object>>) history.get("studies");

        assertEquals("postgres-domain", history.get("source"));
        assertEquals(2, studies.size());
        assertEquals("CASE-SPIDER-NEW", studies.get(0).get("caseId"));
        assertEquals("CASE-SPIDER-OLD", studies.get(1).get("caseId"));
        assertFalse(studies.stream().anyMatch(item -> "CASE-OTHER".equals(item.get("caseId"))));

        Map<String, List<Map<String, Object>>> measurements = (Map<String, List<Map<String, Object>>>) studies.get(0).get("measurementsByPlane");
        assertEquals(8.2, measurements.get("sagittal").get(0).get("aiValue"));
        assertEquals(1, ((List<?>) studies.get(0).get("corrections")).size());
        // latestRun: resumen aditivo de la MISMA corrida que measurementsByPlane (no suma corridas distintas).
        Map<String, Object> latestRun = (Map<String, Object>) studies.get(0).get("latestRun");
        assertEquals("multi-new", latestRun.get("runId"));
        assertEquals("completed", latestRun.get("status"));
        assertEquals(measurements.get("sagittal").size(), latestRun.get("measurementCount"));
        assertEquals(1, latestRun.get("correctedMeasurementCount"));
        String serialized = history.toString();
        assertFalse(serialized.contains("lordosisAngle"));
        assertFalse(serialized.contains("canalDiameter"));
        assertTrue(((Map<String, Object>) history.get("summary")).containsKey("withStudyDate"));
    }

    @Test
    void missingSubjectRefReturnsEmptyDomainResponse() {
        InMemoryStudyRepository emptyRepository = new InMemoryStudyRepository();
        Map<String, Object> history = new PatientHistoryService(emptyRepository, new EffectiveAnalysisRunSelector(emptyRepository)).history("SPIDER-404");
        assertEquals("ok", history.get("status"));
        assertEquals("postgres-domain", history.get("source"));
        assertEquals(List.of(), history.get("studies"));
        assertEquals(0, ((Map<?, ?>) history.get("summary")).get("totalStudies"));
    }

    // ---- Stage 84-C through 84-H: post-e50 productAnalysis summary, reusing EffectiveAnalysisRunSelector ----

    private Study seedDomainStudy(InMemoryStudyRepository repository, String caseId, String subjectRef, LocalDate studyDate) {
        Instant now = Instant.now();
        Study study = new Study(UUID.randomUUID().toString(), caseId, "registered", subjectRef, studyDate, "MRI", "desc", "medium", now, now);
        repository.saveStudy(study);
        return study;
    }

    private record SeededProductRun(AiProductAnalysisRun run, AiMeasurementResult measurement) {
    }

    private SeededProductRun seedProductRun(InMemoryStudyRepository repository, Study study, String status, List<String> warnings) {
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        AiProductAnalysisRun run = new AiProductAnalysisRun(
            runId, study.id(), study.caseId(), "pfi.ai-product-result.v1", "post-e50-ai-product-freeze-v1",
            status, true, true, false, "trace-1", "sag-1", "ax-1", warnings, null, now, now
        );
        AiDiscLevelResult discLevel = new AiDiscLevelResult(
            UUID.randomUUID().toString(), runId, 0, "L4-L5", "localized", "67B2", true, false,
            "paired", true, false, false, null, false
        );
        AiMeasurementResult measurement = new AiMeasurementResult(
            UUID.randomUUID().toString(), runId, discLevel.id(), "sagittal", "L4-L5", "disc", 12.3, 12.3, 8.0, true
        );
        repository.saveAiProductAnalysisRun(run, List.of(discLevel), List.of(measurement));
        return new SeededProductRun(run, measurement);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> productAnalysisOf(Map<String, Object> history, String caseId) {
        List<Map<String, Object>> studies = (List<Map<String, Object>>) history.get("studies");
        return (Map<String, Object>) studies.stream()
            .filter(item -> caseId.equals(item.get("caseId")))
            .findFirst().orElseThrow()
            .get("productAnalysis");
    }

    @Test
    void studyWithoutAnyProductAnalysisRunReportsNullNotAnError() {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        seedDomainStudy(repository, "CASE-84-A", "PAT-84H-1", LocalDate.of(2026, 2, 1));

        Map<String, Object> history = new PatientHistoryService(repository, new EffectiveAnalysisRunSelector(repository)).history("PAT-84H-1");
        assertNull(productAnalysisOf(history, "CASE-84-A"));
    }

    @Test
    void completedEffectiveRunIsSummarizedWithItsStatus() {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        Study study = seedDomainStudy(repository, "CASE-84-B", "PAT-84H-2", LocalDate.of(2026, 2, 1));
        seedProductRun(repository, study, "completed", List.of());

        Map<String, Object> history = new PatientHistoryService(repository, new EffectiveAnalysisRunSelector(repository)).history("PAT-84H-2");
        Map<String, Object> productAnalysis = productAnalysisOf(history, "CASE-84-B");
        assertEquals("completed", productAnalysis.get("status"));
    }

    @Test
    void partialEffectiveRunIsSummarizedWithItsStatus() {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        Study study = seedDomainStudy(repository, "CASE-84-C", "PAT-84H-3", LocalDate.of(2026, 2, 1));
        seedProductRun(repository, study, "partial", List.of());

        Map<String, Object> history = new PatientHistoryService(repository, new EffectiveAnalysisRunSelector(repository)).history("PAT-84H-3");
        assertEquals("partial", productAnalysisOf(history, "CASE-84-C").get("status"));
    }

    @Test
    void failedRunIsNeverSelectedAsTheEffectiveSummary() {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        Study study = seedDomainStudy(repository, "CASE-84-D", "PAT-84H-4", LocalDate.of(2026, 2, 1));
        seedProductRun(repository, study, "failed", List.of());

        Map<String, Object> history = new PatientHistoryService(repository, new EffectiveAnalysisRunSelector(repository)).history("PAT-84H-4");
        assertNull(productAnalysisOf(history, "CASE-84-D"));
    }

    @Test
    void measurementCountReflectsThePersistedMeasurements() {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        Study study = seedDomainStudy(repository, "CASE-84-E", "PAT-84H-5", LocalDate.of(2026, 2, 1));
        seedProductRun(repository, study, "completed", List.of());

        Map<String, Object> history = new PatientHistoryService(repository, new EffectiveAnalysisRunSelector(repository)).history("PAT-84H-5");
        assertEquals(1, productAnalysisOf(history, "CASE-84-E").get("measurementCount"));
    }

    @Test
    void correctedMeasurementCountReflectsDistinctCorrectedMeasurements() {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        Study study = seedDomainStudy(repository, "CASE-84-F", "PAT-84H-6", LocalDate.of(2026, 2, 1));
        SeededProductRun seeded = seedProductRun(repository, study, "completed", List.of());
        repository.saveAiMeasurementCorrection(new AiMeasurementCorrection(
            UUID.randomUUID().toString(), seeded.measurement().id(), seeded.run().id(), "majorAxisMm",
            12.3, 12.9, "mm", null, "doctor-1", Instant.now()
        ));

        Map<String, Object> history = new PatientHistoryService(repository, new EffectiveAnalysisRunSelector(repository)).history("PAT-84H-6");
        Map<String, Object> productAnalysis = productAnalysisOf(history, "CASE-84-F");
        assertEquals(1, productAnalysis.get("measurementCount"));
        assertEquals(1, productAnalysis.get("correctedMeasurementCount"));
    }

    @Test
    void warningsArePreservedVerbatimNeverReinterpreted() {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        Study study = seedDomainStudy(repository, "CASE-84-G", "PAT-84H-7", LocalDate.of(2026, 2, 1));
        seedProductRun(repository, study, "partial", List.of("67B2_RUNTIME_PARITY_NOT_PROVEN", "67C_RUNTIME_PARITY_NOT_PROVEN"));

        Map<String, Object> history = new PatientHistoryService(repository, new EffectiveAnalysisRunSelector(repository)).history("PAT-84H-7");
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) productAnalysisOf(history, "CASE-84-G").get("warnings");
        assertTrue(warnings.contains("67B2_RUNTIME_PARITY_NOT_PROVEN"));
        assertTrue(warnings.contains("67C_RUNTIME_PARITY_NOT_PROVEN"));
    }

    @Test
    void reviewStatusDefaultsToPendingWhenNoReviewWasRecorded() {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        Study study = seedDomainStudy(repository, "CASE-84-H", "PAT-84H-8", LocalDate.of(2026, 2, 1));
        seedProductRun(repository, study, "completed", List.of());

        Map<String, Object> history = new PatientHistoryService(repository, new EffectiveAnalysisRunSelector(repository)).history("PAT-84H-8");
        assertEquals("pending", productAnalysisOf(history, "CASE-84-H").get("reviewStatus"));
    }

    @Test
    void reviewedRunReflectsItsReviewStatusInTheSummary() {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        Study study = seedDomainStudy(repository, "CASE-84-I", "PAT-84H-9", LocalDate.of(2026, 2, 1));
        SeededProductRun seeded = seedProductRun(repository, study, "completed", List.of());
        repository.upsertAiProductAnalysisRunReview(seeded.run().id(), "accepted", null, "doctor-1", Instant.now());

        Map<String, Object> history = new PatientHistoryService(repository, new EffectiveAnalysisRunSelector(repository)).history("PAT-84H-9");
        assertEquals("accepted", productAnalysisOf(history, "CASE-84-I").get("reviewStatus"));
    }

    @Test
    void productAnalysisSummaryNeverIncludesHumanReviewRequiredFalse() {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        Study study = seedDomainStudy(repository, "CASE-84-J", "PAT-84H-10", LocalDate.of(2026, 2, 1));
        seedProductRun(repository, study, "completed", List.of());

        Map<String, Object> history = new PatientHistoryService(repository, new EffectiveAnalysisRunSelector(repository)).history("PAT-84H-10");
        Map<String, Object> productAnalysis = productAnalysisOf(history, "CASE-84-J");
        assertEquals(true, productAnalysis.get("humanReviewRequired"));
        assertEquals(true, productAnalysis.get("notClinicalDiagnosis"));
    }

    @Test
    void multipleStudiesEachGetTheirOwnIndependentProductAnalysisSummary() {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        Study older = seedDomainStudy(repository, "CASE-84-K1", "PAT-84H-11", LocalDate.of(2026, 2, 1));
        Study newer = seedDomainStudy(repository, "CASE-84-K2", "PAT-84H-11", LocalDate.of(2026, 5, 10));
        seedProductRun(repository, older, "completed", List.of());
        seedProductRun(repository, newer, "partial", List.of("67B2_RUNTIME_PARITY_NOT_PROVEN"));

        Map<String, Object> history = new PatientHistoryService(repository, new EffectiveAnalysisRunSelector(repository)).history("PAT-84H-11");
        assertEquals("completed", productAnalysisOf(history, "CASE-84-K1").get("status"));
        assertEquals("partial", productAnalysisOf(history, "CASE-84-K2").get("status"));
    }
}
