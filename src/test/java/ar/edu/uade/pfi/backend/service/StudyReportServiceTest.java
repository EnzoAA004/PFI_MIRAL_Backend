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
        // findAndRegisterModules() picks up jackson-datatype-jsr310 (already on the classpath
        // transitively via spring-boot-starter-json, which is what the app's own auto-configured
        // ObjectMapper bean uses) -- a plain `new ObjectMapper()` has no Instant support and would
        // throw InvalidDefinitionException on `generatedAt`, unlike the real, running application.
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(report);

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
            () -> assertEquals("comparable", report.longitudinalComparison().measurements().get(0).comparisonStatus())
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

    private void seedRunWithOneMeasurement(Study study, String runId, double areaMm2) {
        AiProductAnalysisRun run = seedRun(study, runId, "completed", Instant.parse("2026-08-26T10:00:00Z"));
        AiDiscLevelResult level = level("level-" + runId, run.id(), 0, "L1-L2", false, true, true);
        AiMeasurementResult measurement = new AiMeasurementResult(
            "measurement-" + runId, run.id(), level.id(), "sagittal", "L1-L2", "disc",
            areaMm2, null, null, true
        );
        repository.saveAiProductAnalysisRun(run, List.of(level), List.of(measurement));
    }

    private AiDiscLevelResult level(String id, String runId, int rankIndex, String level, boolean experimental, boolean automaticInferenceEnabled, boolean localizationValidated) {
        return new AiDiscLevelResult(
            id, runId, rankIndex, level, "localized", "ai", localizationValidated, false,
            experimental ? "candidate" : "matched", !experimental, experimental, experimental,
            automaticInferenceEnabled ? "available" : "blocked", automaticInferenceEnabled
        );
    }
}
