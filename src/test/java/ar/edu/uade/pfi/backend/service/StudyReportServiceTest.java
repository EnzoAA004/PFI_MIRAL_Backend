package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiMeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.StudyReportDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class StudyReportServiceTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-26T12:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
    private final EffectiveAnalysisRunSelector selector = new EffectiveAnalysisRunSelector(repository);
    private final EffectiveMeasurementResolver resolver = new EffectiveMeasurementResolver(repository);
    private final LongitudinalComparisonService longitudinal = new LongitudinalComparisonService(repository, selector, resolver, FIXED_CLOCK);
    private final StudyReportService service = new StudyReportService(
        repository,
        selector,
        resolver,
        longitudinal,
        new StudyReportPdfRenderer(),
        FIXED_CLOCK
    );

    @Test
    void buildsBackendAuthoritativeReportFromEffectiveReviewedRunAndCorrections() throws Exception {
        Study study = seedStudy("study-current", "CASE-88-CURRENT", "SUBJ-88", LocalDate.of(2026, 8, 15));
        AiProductAnalysisRun failed = seedRun(study, "run-failed", "failed", Instant.parse("2026-08-26T09:00:00Z"));
        AiProductAnalysisRun completed = seedRun(study, "run-completed", "completed", Instant.parse("2026-08-26T10:00:00Z"));
        AiProductAnalysisRun reviewed = seedRun(study, "run-reviewed", "completed", Instant.parse("2026-08-26T08:00:00Z"));
        AiDiscLevelResult level = level("level-1", reviewed.id(), 0, "L1-L2", true, false, true);
        AiMeasurementResult measurement = new AiMeasurementResult(
            "measurement-1", reviewed.id(), level.id(), "sagittal", "L1-L2", "disc",
            480.0, 31.2, 9.8, true
        );
        repository.saveAiProductAnalysisRun(reviewed, List.of(level), List.of(measurement));
        repository.upsertAiProductAnalysisRunReview(reviewed.id(), "accepted", "Revisión profesional completada.", "doctor@pfi.local", Instant.parse("2026-08-26T11:00:00Z"));
        repository.upsertAiDiscLevelReview(level.id(), "observed", "Emparejamiento axial revisado.", "doctor@pfi.local", Instant.parse("2026-08-26T11:05:00Z"));
        repository.saveAiMeasurementCorrection(new AiMeasurementCorrection(
            "correction-old", measurement.id(), reviewed.id(), "areaMm2", 480.0, 480.2,
            "mm2", "primer ajuste", "doctor@pfi.local", Instant.parse("2026-08-26T11:10:00Z")
        ));
        repository.saveAiMeasurementCorrection(new AiMeasurementCorrection(
            "correction-new", measurement.id(), reviewed.id(), "areaMm2", 480.0, 480.5,
            "mm2", "ajuste final", "doctor@pfi.local", Instant.parse("2026-08-26T11:15:00Z")
        ));

        StudyReportDto report = service.report(study.caseId(), null);
        String json = JSON.writeValueAsString(report);

        assertAll(
            () -> assertEquals(StudyReportService.SCHEMA_VERSION, report.schemaVersion()),
            () -> assertEquals(FIXED_CLOCK.instant(), report.generatedAt()),
            () -> assertEquals("ready", report.reportStatus()),
            () -> assertEquals(study.caseId(), report.caseId()),
            () -> assertEquals("MRI", report.modality()),
            () -> assertEquals(reviewed.id(), report.analysis().analysisRunId()),
            () -> assertEquals("completed", report.analysis().analysisStatus()),
            () -> assertEquals("post-e50-ai-product-freeze-v1", report.analysis().releaseId()),
            () -> assertTrue(report.analysis().humanReviewRequired()),
            () -> assertEquals("accepted", report.review().reviewStatus()),
            () -> assertEquals("Revisión profesional completada.", report.review().reviewComment()),
            () -> assertEquals(1, report.discLevels().size()),
            () -> assertEquals("L1-L2", report.discLevels().get(0).level()),
            () -> assertEquals("observed", report.discLevels().get(0).reviewStatus()),
            () -> assertTrue(report.discLevels().get(0).axialPairingExperimental()),
            () -> assertFalse(report.discLevels().get(0).automaticInferenceEnabled()),
            () -> assertEquals(3, report.measurements().size()),
            () -> assertEquals("areaMm2", report.measurements().get(0).metric()),
            () -> assertEquals("mm²", report.measurements().get(0).unit()),
            () -> assertEquals(480.0, report.measurements().get(0).automaticValue()),
            () -> assertEquals(480.5, report.measurements().get(0).correctedValue()),
            () -> assertEquals(480.5, report.measurements().get(0).effectiveValue()),
            () -> assertEquals("professional_correction", report.measurements().get(0).effectiveSource()),
            () -> assertTrue(report.warnings().contains("axial_candidate_below_quality_gate")),
            () -> assertTrue(report.limitations().stream().anyMatch(text -> text.contains("diagnóstico médico autónomo"))),
            () -> assertTrue(report.limitations().stream().anyMatch(text -> text.contains("emparejamiento axial experimental"))),
            () -> assertTrue(report.limitations().stream().anyMatch(text -> text.contains("inferencia automática complementaria"))),
            () -> assertTrue(report.safety().humanReviewRequired()),
            () -> assertTrue(report.safety().notClinicalDiagnosis()),
            () -> assertFalse(report.safety().autonomousDiagnosis()),
            () -> assertEquals(StudyReportService.DISCLAIMER, report.safety().disclaimer()),
            () -> assertEquals(study.caseId(), report.traceability().caseId()),
            () -> assertEquals(reviewed.id(), report.traceability().analysisRunId()),
            () -> assertEquals("pfi.ai-product-result.v1", report.traceability().analysisSchemaVersion()),
            () -> assertNull(report.longitudinalComparison()),
            () -> assertFalse(report.longitudinalHistory().available()),
            () -> assertTrue(report.longitudinalHistory().comparisons().isEmpty()),
            () -> assertFalse(json.contains("SUBJ-88")),
            () -> assertFalse(json.contains("PatientName")),
            () -> assertFalse(json.contains("PatientID")),
            () -> assertFalse(json.contains("1.2.840")),
            () -> assertFalse(json.contains("rawResponseSanitized")),
            () -> assertEquals(completed.id(), repository.findAiProductAnalysisRunsByStudyId(study.id()).get(0).id()),
            () -> assertEquals(failed.id(), repository.findAiProductAnalysisRunsByStudyId(study.id()).get(1).id())
        );
    }

    @Test
    void reportsNoEffectiveAnalysisWithoutInventingLevelsOrMeasurements() {
        Study study = seedStudy("study-failed", "CASE-88-FAILED", "SUBJ-FAILED", LocalDate.of(2026, 8, 16));
        seedRun(study, "run-only-failed", "failed", Instant.parse("2026-08-26T10:00:00Z"));

        StudyReportDto report = service.report(study.caseId(), null);

        assertAll(
            () -> assertEquals("no_effective_analysis", report.reportStatus()),
            () -> assertEquals("failed", report.analysis().analysisStatus()),
            () -> assertNull(report.analysis().analysisRunId()),
            () -> assertEquals("pending", report.review().reviewStatus()),
            () -> assertTrue(report.discLevels().isEmpty()),
            () -> assertTrue(report.measurements().isEmpty()),
            () -> assertTrue(report.warnings().isEmpty()),
            () -> assertTrue(report.limitations().stream().anyMatch(text -> text.contains("completed/partial"))),
            () -> assertFalse(report.longitudinalHistory().available()),
            () -> assertTrue(report.longitudinalHistory().message().contains("corrida efectiva actual")),
            () -> assertTrue(report.safety().humanReviewRequired()),
            () -> assertTrue(report.safety().notClinicalDiagnosis()),
            () -> assertFalse(report.safety().autonomousDiagnosis()),
            () -> assertNull(report.traceability().analysisRunId())
        );
    }

    @Test
    void includesOptionalLongitudinalComparisonOnlyForSamePatientStudies() {
        Study baseline = seedStudy("study-baseline", "CASE-88-BASELINE", "SUBJ-LONG", LocalDate.of(2026, 7, 15));
        Study current = seedStudy("study-long-current", "CASE-88-LONG-CURRENT", "SUBJ-LONG", LocalDate.of(2026, 8, 15));
        seedRunWithOneMeasurement(baseline, "run-baseline", 460.0);
        seedRunWithOneMeasurement(current, "run-current", 480.0);

        StudyReportDto report = service.report(current.caseId(), baseline.caseId());

        assertAll(
            () -> assertNotNull(report.longitudinalComparison()),
            () -> assertEquals(baseline.caseId(), report.longitudinalComparison().baselineCaseId()),
            () -> assertEquals(current.caseId(), report.longitudinalComparison().currentCaseId()),
            () -> assertEquals(1, report.longitudinalComparison().measurements().size()),
            () -> assertEquals(20.0, report.longitudinalComparison().measurements().get(0).deltaAbsolute()),
            () -> assertEquals("increased", report.longitudinalComparison().measurements().get(0).trend()),
            () -> assertEquals("comparable", report.longitudinalComparison().measurements().get(0).comparisonStatus()),
            () -> assertTrue(report.longitudinalHistory().available()),
            () -> assertEquals(1, report.longitudinalHistory().previousStudies().size()),
            () -> assertEquals(1, report.longitudinalHistory().comparisons().size()),
            () -> assertEquals(20.0, report.longitudinalHistory().comparisons().get(0).absoluteDelta())
        );
    }

    @Test
    void reportHistoryIncludesThreeStudySeriesWithProfessionalCorrectionAndPercentNull() throws Exception {
        Study oldest = seedStudy("study-a", "CASE-A", "SUBJ-HIST", LocalDate.of(2026, 6, 1));
        Study middle = seedStudy("study-b", "CASE-B", "subj-hist", LocalDate.of(2026, 7, 1));
        Study current = seedStudy("study-c", "CASE-C", " SUBJ-HIST ", LocalDate.of(2026, 8, 1));
        seedRunWithOneMeasurement(oldest, "run-a", 0.0);
        seedRunWithOneMeasurement(middle, "run-b", 10.0);
        AiProductAnalysisRun currentRun = seedRunWithOneMeasurement(current, "run-c", 12.0);
        AiMeasurementResult currentMeasurement = repository.findAiMeasurementResultsByAnalysisRunId(currentRun.id()).get(0);
        repository.saveAiMeasurementCorrection(new AiMeasurementCorrection(
            "correction-current", currentMeasurement.id(), currentRun.id(), "areaMm2", 12.0, 12.5,
            "mm2", "ajuste", "doctor@pfi.local", Instant.parse("2026-08-26T11:15:00Z")
        ));

        StudyReportDto report = service.report(current.caseId(), null);
        String historyJson = JSON.writeValueAsString(report.longitudinalHistory());

        assertAll(
            () -> assertTrue(report.longitudinalHistory().available()),
            () -> assertEquals(current.caseId(), report.longitudinalHistory().currentStudy().caseId()),
            () -> assertEquals(2, report.longitudinalHistory().previousStudies().size()),
            () -> assertEquals(2, report.longitudinalHistory().comparisons().size()),
            () -> assertEquals("professional_correction", report.longitudinalHistory().comparisons().get(0).currentValueSource()),
            () -> assertEquals(12.5, report.longitudinalHistory().comparisons().get(0).currentEffectiveValue()),
            () -> assertNull(report.longitudinalHistory().comparisons().stream()
                .filter(row -> "CASE-A".equals(row.previousCaseId()))
                .findFirst()
                .orElseThrow()
                .percentageDelta()),
            () -> assertFalse(historyJson.contains("SUBJ-HIST")),
            () -> assertFalse(historyJson.matches("(?s).*(?i)(mejor|empeor|progresi[oó]n|better|worse|progression).*"))
        );
    }

    @Test
    void reportHistoryDoesNotCrossPatientsOrUseNullSubjectRef() {
        Study otherPatient = seedStudy("study-other", "CASE-OTHER", "SUBJ-OTHER", LocalDate.of(2026, 7, 1));
        Study current = seedStudy("study-null-subject", "CASE-NULL-SUBJECT", null, LocalDate.of(2026, 8, 1));
        seedRunWithOneMeasurement(otherPatient, "run-other", 10.0);
        seedRunWithOneMeasurement(current, "run-null-subject", 12.0);

        StudyReportDto report = service.report(current.caseId(), null);

        assertAll(
            () -> assertFalse(report.longitudinalHistory().available()),
            () -> assertTrue(report.longitudinalHistory().previousStudies().isEmpty()),
            () -> assertTrue(report.longitudinalHistory().comparisons().isEmpty())
        );
    }

    @Test
    void reportHistoryListsPreviousStudyWithoutEligibleRunWithoutFailingReport() {
        Study previous = seedStudy("study-prev-failed", "CASE-PREV-FAILED", "SUBJ-FAILED-HIST", LocalDate.of(2026, 7, 1));
        Study current = seedStudy("study-current-ok", "CASE-CURRENT-OK", "SUBJ-FAILED-HIST", LocalDate.of(2026, 8, 1));
        seedRun(previous, "run-prev-failed", "failed", Instant.parse("2026-08-26T09:00:00Z"));
        seedRunWithOneMeasurement(current, "run-current-ok", 12.0);

        StudyReportDto report = service.report(current.caseId(), null);

        assertAll(
            () -> assertFalse(report.longitudinalHistory().available()),
            () -> assertEquals(1, report.longitudinalHistory().previousStudies().size()),
            () -> assertEquals("no_usable_analysis", report.longitudinalHistory().previousStudies().get(0).comparisonStatus()),
            () -> assertTrue(report.longitudinalHistory().comparisons().isEmpty())
        );
    }

    @Test
    void reportHistoryDoesNotPairNullLevelMeasurements() {
        Study previous = seedStudy("study-prev-null-level", "CASE-PREV-NULL", "SUBJ-NULL-LVL", LocalDate.of(2026, 7, 1));
        Study current = seedStudy("study-current-null-level", "CASE-CURRENT-NULL", "SUBJ-NULL-LVL", LocalDate.of(2026, 8, 1));
        seedRunWithMeasurement(previous, "run-prev-null", null, "sagittal", "disc", 10.0, null, null);
        seedRunWithMeasurement(current, "run-current-null", null, "sagittal", "disc", 12.0, null, null);

        StudyReportDto report = service.report(current.caseId(), null);

        assertAll(
            () -> assertFalse(report.longitudinalHistory().available()),
            () -> assertEquals("no_comparable_measurements", report.longitudinalHistory().previousStudies().get(0).comparisonStatus()),
            () -> assertTrue(report.longitudinalHistory().comparisons().isEmpty())
        );
    }

    @Test
    void rendersPdfWithTitleSafetyTextAndStructuredSections() throws Exception {
        Study study = seedStudy("study-pdf", "CASE-88-PDF", "SUBJ-PDF", LocalDate.of(2026, 8, 15));
        seedRunWithOneMeasurement(study, "run-pdf", 480.0);

        byte[] pdf = service.reportPdf(study.caseId(), null);
        String text;
        try (var document = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(document);
        }

        assertAll(
            () -> assertTrue(pdf.length > 1000),
            () -> assertTrue(new String(pdf, 0, 5).startsWith("%PDF-")),
            () -> assertTrue(text.contains("Informe técnico de análisis de RM lumbar")),
            () -> assertTrue(text.contains("no constituye un diagnóstico médico autónomo")),
            () -> assertTrue(text.contains("Estudio: CASE-88-PDF")),
            () -> assertTrue(text.contains("Resumen del análisis")),
            () -> assertTrue(text.contains("Mediciones efectivas")),
            () -> assertTrue(text.contains("Warnings / limitations"))
        );
    }

    private Study seedStudy(String id, String caseId, String subjectRef, LocalDate studyDate) {
        Study study = new Study(
            id, caseId, "registered", subjectRef, studyDate, "MRI",
            "Lumbar MRI de-identified fixture", "medium",
            Instant.parse("2026-08-26T08:00:00Z"), Instant.parse("2026-08-26T08:00:00Z")
        );
        repository.saveStudy(study);
        return study;
    }

    private AiProductAnalysisRun seedRun(Study study, String runId, String status, Instant createdAt) {
        AiProductAnalysisRun run = new AiProductAnalysisRun(
            runId, study.id(), study.caseId(), "pfi.ai-product-result.v1",
            "post-e50-ai-product-freeze-v1", status, true, true, false, "trace-88",
            "input-sagittal", "input-axial", List.of("axial_candidate_below_quality_gate"),
            Map.of("PatientName", "SHOULD_NOT_LEAK", "StudyInstanceUID", "1.2.840.999"),
            createdAt, createdAt.plusSeconds(2)
        );
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());
        return run;
    }

    private AiProductAnalysisRun seedRunWithOneMeasurement(Study study, String runId, double areaMm2) {
        return seedRunWithMeasurement(study, runId, "L1-L2", "sagittal", "disc", areaMm2, null, null);
    }

    private AiProductAnalysisRun seedRunWithMeasurement(Study study, String runId, String levelName, String plane, String structure, Double areaMm2, Double majorAxisMm, Double minorAxisMm) {
        AiProductAnalysisRun run = seedRun(study, runId, "completed", Instant.parse("2026-08-26T10:00:00Z"));
        AiDiscLevelResult level = level("level-" + runId, run.id(), 0, "L1-L2", false, true, true);
        AiMeasurementResult measurement = new AiMeasurementResult(
            "measurement-" + runId, run.id(), level.id(), plane, levelName, structure,
            areaMm2, majorAxisMm, minorAxisMm, true
        );
        repository.saveAiProductAnalysisRun(run, List.of(level), List.of(measurement));
        return run;
    }

    private AiDiscLevelResult level(String id, String runId, int rankIndex, String level, boolean experimental, boolean automaticInferenceEnabled, boolean localizationValidated) {
        return new AiDiscLevelResult(
            id, runId, rankIndex, level, "localized", "ai", localizationValidated, false,
            experimental ? "candidate" : "matched", !experimental, experimental, experimental,
            automaticInferenceEnabled ? "available" : "blocked", automaticInferenceEnabled
        );
    }
}
