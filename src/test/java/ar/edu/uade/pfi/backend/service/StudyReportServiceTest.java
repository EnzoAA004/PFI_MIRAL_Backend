package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegion;
import ar.edu.uade.pfi.backend.domain.AiEvidenceRegionCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.EvidenceGeometry;
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
    private final EffectiveAnnotationResolver annotationResolver = new EffectiveAnnotationResolver(repository);
    private final LongitudinalComparisonService longitudinal = new LongitudinalComparisonService(repository, selector, resolver, FIXED_CLOCK);
    /** No base images available in this fixture repository — every evidence item falls back to text, same as a real 404/unavailable slice would (EXT-04B4 Phase 12). */
    private final EvidenceImageSource noImagesAvailable = new EvidenceImageSource() {
        @Override public java.util.Optional<byte[]> fetchSeriesSlice(String inputId, int sliceIndex) { return java.util.Optional.empty(); }
        @Override public java.util.Optional<byte[]> fetchAsset(String runId, String plane, String assetName) { return java.util.Optional.empty(); }
    };
    private final StudyReportService service = new StudyReportService(
        repository,
        selector,
        resolver,
        annotationResolver,
        longitudinal,
        new StudyReportPdfRenderer(noImagesAvailable, new EvidenceOverlayComposer()),
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
        // El mapper compartido JSON ya registra JavaTimeModule, por lo que `generatedAt` (Instant)
        // serializa igual que con el ObjectMapper autoconfigurado de la aplicación real.
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

    @Test
    void reportHasEmptyVisualEvidenceWhenNoRegionsExist() {
        Study study = seedStudy("study-no-evidence", "CASE-NO-EVIDENCE", "SUBJ-NE", LocalDate.of(2026, 8, 20));
        seedRunWithOneMeasurement(study, "run-no-evidence", 100.0);

        StudyReportDto report = service.report(study.caseId(), null);

        assertAll(
            () -> assertNotNull(report.visualEvidence()),
            () -> assertTrue(report.visualEvidence().isEmpty())
        );
    }

    @Test
    void oldReportShapeStillDeserializesWithoutTheVisualEvidenceField() throws Exception {
        // Backward compatibility (EXT-04B2): a pre-EXT-04B2 report JSON, with no visualEvidence
        // key at all, must still be a valid input for anything reading these reports today.
        String legacyJson = "{"
            + "\"schemaVersion\":\"pfi.study-report.v1\","
            + "\"reportStatus\":\"ready\","
            + "\"caseId\":\"CASE-LEGACY\""
            + "}";
        Map<String, Object> parsed = JSON.readValue(legacyJson, Map.class);
        assertEquals("CASE-LEGACY", parsed.get("caseId"));
        assertFalse(parsed.containsKey("visualEvidence"));
    }

    @Test
    void reportIncludesEffectiveVisualEvidenceAndExcludesRejectedOnes() throws Exception {
        Study study = seedStudy("study-evidence", "CASE-EVIDENCE", "SUBJ-EV", LocalDate.of(2026, 8, 20));
        AiProductAnalysisRun run = seedRun(study, "run-evidence", "completed", Instant.parse("2026-08-26T10:00:00Z"));
        AiDiscLevelResult level = level("level-evidence", run.id(), 0, "L4-L5", false, true, true);
        repository.saveAiProductAnalysisRun(run, List.of(level), List.of());

        Map<String, Object> aiGeometry = new EvidenceGeometry.Bbox(10, 10, 20, 20).toMap();
        Map<String, Object> correctedGeometry = new EvidenceGeometry.Bbox(11, 11, 18, 18).toMap();
        AiEvidenceRegion acceptedRegion = evidenceRegion("evidence-accepted", run.id(), level.id(), aiGeometry, "doctor@pfi.local");
        AiEvidenceRegion correctedRegion = evidenceRegion("evidence-corrected", run.id(), null, aiGeometry, "doctor@pfi.local");
        AiEvidenceRegion rejectedRegion = evidenceRegion("evidence-rejected", run.id(), null, aiGeometry, "doctor@pfi.local");
        repository.saveAiEvidenceRegion(acceptedRegion);
        repository.saveAiEvidenceRegion(correctedRegion);
        repository.saveAiEvidenceRegion(rejectedRegion);
        repository.upsertAiDiscLevelReview(level.id(), "accepted", "nivel confirmado", "doctor@pfi.local", Instant.parse("2026-08-26T11:00:00Z"));
        repository.upsertAiProductAnalysisRunReview(run.id(), "rejected", "el run en general no es confiable", "doctor@pfi.local", Instant.parse("2026-08-26T11:00:00Z"));
        repository.saveAiEvidenceRegionCorrection(new AiEvidenceRegionCorrection(
            "correction-evidence", correctedRegion.id(), run.id(),
            EvidenceGeometry.TYPE_BBOX, aiGeometry, EvidenceGeometry.TYPE_BBOX, correctedGeometry,
            "ajuste de bordes", "doctor@pfi.local", Instant.parse("2026-08-26T11:05:00Z")
        ));

        StudyReportDto report = service.report(study.caseId(), null);
        String json = JSON.writeValueAsString(report.visualEvidence());

        assertAll(
            () -> assertEquals(2, report.visualEvidence().size()),
            () -> assertTrue(report.visualEvidence().stream().anyMatch(item -> "professional_corrected".equals(item.source()) && correctedGeometry.equals(item.geometry()))),
            () -> assertTrue(report.visualEvidence().stream().anyMatch(item -> "ai_validated".equals(item.source()) && "L4-L5".equals(item.level()))),
            () -> assertTrue(report.visualEvidence().stream().noneMatch(item -> "evidence-rejected".equals(item.evidenceId()))),
            () -> assertTrue(report.visualEvidence().stream().allMatch(item -> "Región de interés".equals(item.label()))),
            () -> assertFalse(json.contains("doctor@pfi.local")),
            () -> assertFalse(json.contains("C:\\\\")),
            () -> assertFalse(json.contains("/var/")),
            () -> assertFalse(json.contains("StudyInstanceUID"))
        );
    }

    @Test
    void reportShowsUnreviewedAiSuggestionWithWarningAndNeutralLabel() {
        Study study = seedStudy("study-suggested", "CASE-SUGGESTED", "SUBJ-SUG", LocalDate.of(2026, 8, 20));
        AiProductAnalysisRun run = seedRun(study, "run-suggested", "completed", Instant.parse("2026-08-26T10:00:00Z"));
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());
        repository.saveAiEvidenceRegion(evidenceRegion("evidence-pending", run.id(), null, new EvidenceGeometry.Bbox(5, 5, 10, 10).toMap(), null));

        StudyReportDto report = service.report(study.caseId(), null);

        assertAll(
            () -> assertEquals(1, report.visualEvidence().size()),
            () -> assertEquals("ai_suggested", report.visualEvidence().get(0).source()),
            () -> assertEquals("pending", report.visualEvidence().get(0).reviewStatus()),
            () -> assertNotNull(report.visualEvidence().get(0).warning())
        );
    }

    @Test
    void visualEvidenceExposesBaseImageReferenceFromTheRunsOwnSagittalInputId() {
        // 1. visual evidence con base image reference; 3. run association correcta.
        Study study = seedStudy("study-baseimage", "CASE-BASEIMAGE", "SUBJ-BI", LocalDate.of(2026, 9, 3));
        AiProductAnalysisRun run = seedRun(study, "run-baseimage", "completed", Instant.parse("2026-09-03T10:00:00Z"));
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());
        repository.saveAiEvidenceRegion(evidenceRegion("evidence-baseimage", run.id(), null, new EvidenceGeometry.Bbox(5, 5, 10, 10).toMap(), null, "sagittal", 6));

        StudyReportDto report = service.report(study.caseId(), null);
        StudyReportDto.VisualEvidenceSummary item = report.visualEvidence().get(0);

        assertAll(
            () -> assertNotNull(item.baseImage()),
            () -> assertEquals("input-sagittal", item.baseImage().inputId()),
            () -> assertEquals(6, item.baseImage().sliceIndex())
        );
    }

    @Test
    void visualEvidenceBaseImageUsesTheRegionsOwnSliceIndexAndAxialPlane() {
        // 2. sliceIndex correcto; 3. run association correcta (rama axial).
        Study study = seedStudy("study-baseimage-axial", "CASE-BASEIMAGE-AXIAL", "SUBJ-BIA", LocalDate.of(2026, 9, 3));
        AiProductAnalysisRun run = seedRun(study, "run-baseimage-axial", "completed", Instant.parse("2026-09-03T10:00:00Z"));
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());
        repository.saveAiEvidenceRegion(evidenceRegion("evidence-baseimage-axial", run.id(), null, new EvidenceGeometry.Bbox(5, 5, 10, 10).toMap(), null, "axial", 42));

        StudyReportDto report = service.report(study.caseId(), null);
        StudyReportDto.VisualEvidenceSummary item = report.visualEvidence().get(0);

        assertAll(
            () -> assertNotNull(item.baseImage()),
            () -> assertEquals("input-axial", item.baseImage().inputId()),
            () -> assertEquals(42, item.baseImage().sliceIndex())
        );
    }

    @Test
    void visualEvidenceBaseImageIsNullWhenTheRegionHasNoSliceIndex() {
        // 5. región sin referencia válida => fail-safe (nunca fabrica un baseImage).
        Study study = seedStudy("study-baseimage-none", "CASE-BASEIMAGE-NONE", "SUBJ-BIN", LocalDate.of(2026, 9, 3));
        AiProductAnalysisRun run = seedRun(study, "run-baseimage-none", "completed", Instant.parse("2026-09-03T10:00:00Z"));
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());
        repository.saveAiEvidenceRegion(evidenceRegion("evidence-baseimage-none", run.id(), null, new EvidenceGeometry.Bbox(5, 5, 10, 10).toMap(), null, "sagittal", null));

        StudyReportDto report = service.report(study.caseId(), null);

        assertNull(report.visualEvidence().get(0).baseImage());
    }

    @Test
    void visualEvidenceJsonWithBaseImageStillLeaksNoReviewerOrPath() throws Exception {
        // 4. no leakage: el nuevo campo baseImage no reintroduce lo que el reporte ya prohíbe.
        Study study = seedStudy("study-baseimage-safe", "CASE-BASEIMAGE-SAFE", "SUBJ-BIS", LocalDate.of(2026, 9, 3));
        AiProductAnalysisRun run = seedRun(study, "run-baseimage-safe", "completed", Instant.parse("2026-09-03T10:00:00Z"));
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());
        repository.saveAiEvidenceRegion(evidenceRegion("evidence-baseimage-safe", run.id(), null, new EvidenceGeometry.Bbox(5, 5, 10, 10).toMap(), "doctor@pfi.local", "sagittal", 6));

        StudyReportDto report = service.report(study.caseId(), null);
        String json = JSON.writeValueAsString(report.visualEvidence());

        assertAll(
            () -> assertTrue(json.contains("\"inputId\":\"input-sagittal\"")),
            () -> assertFalse(json.contains("doctor@pfi.local")),
            () -> assertFalse(json.contains("C:\\\\")),
            () -> assertFalse(json.contains("/var/")),
            () -> assertFalse(json.contains("StudyInstanceUID"))
        );
    }

    @Test
    void visualEvidenceExperimentalRegionKeepsBaseImageAndExperimentalFlagTogether() {
        // 8. experimental sigue marcado (junto con el nuevo baseImage, sin interferir entre sí).
        Study study = seedStudy("study-baseimage-experimental", "CASE-BASEIMAGE-EXPERIMENTAL", "SUBJ-BIE", LocalDate.of(2026, 9, 3));
        AiProductAnalysisRun run = seedRun(study, "run-baseimage-experimental", "completed", Instant.parse("2026-09-03T10:00:00Z"));
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());
        AiEvidenceRegion experimental = new AiEvidenceRegion(
            "evidence-experimental", run.id(), null, null, "sagittal", "L4-L5", "disc", 6,
            "ai", EvidenceGeometry.TYPE_BBOX, new EvidenceGeometry.Bbox(5, 5, 10, 10).toMap(), null,
            "model-sagittal", "sha256:abc123", "pfi.multiplanar-run.v2", null,
            true, null, null, null, Instant.parse("2026-09-03T10:30:00Z")
        );
        repository.saveAiEvidenceRegion(experimental);

        StudyReportDto report = service.report(study.caseId(), null);
        StudyReportDto.VisualEvidenceSummary item = report.visualEvidence().get(0);

        assertAll(
            () -> assertTrue(item.experimental()),
            () -> assertNotNull(item.baseImage()),
            () -> assertEquals("input-sagittal", item.baseImage().inputId())
        );
    }

    @Test
    void oldReportShapeWithVisualEvidenceButNoBaseImageFieldStillDeserializes() throws Exception {
        // 6. backward compatibility del reporte: un visualEvidence[] emitido antes de EXT-04B3.5
        // (sin la clave baseImage) sigue siendo un valor de entrada válido.
        String legacyEvidenceItemJson = "{"
            + "\"evidenceId\":\"evidence-legacy\","
            + "\"plane\":\"sagittal\","
            + "\"geometryType\":\"BBOX\","
            + "\"geometry\":{\"x\":1,\"y\":1,\"width\":2,\"height\":2},"
            + "\"source\":\"ai_suggested\","
            + "\"label\":\"Región de interés\","
            + "\"experimental\":false"
            + "}";
        StudyReportDto.VisualEvidenceSummary parsed = JSON.readValue(legacyEvidenceItemJson, StudyReportDto.VisualEvidenceSummary.class);
        assertAll(
            () -> assertEquals("evidence-legacy", parsed.evidenceId()),
            () -> assertNull(parsed.baseImage())
        );
    }

    private AiEvidenceRegion evidenceRegion(String id, String runId, String discLevelResultId, Map<String, Object> geometry, String comment) {
        return evidenceRegion(id, runId, discLevelResultId, geometry, comment, "sagittal", 6);
    }

    private AiEvidenceRegion evidenceRegion(String id, String runId, String discLevelResultId, Map<String, Object> geometry, String comment, String plane, Integer sliceIndex) {
        return new AiEvidenceRegion(
            id, runId, discLevelResultId, null, plane, "L4-L5", "disc", sliceIndex,
            "ai", EvidenceGeometry.TYPE_BBOX, geometry, null,
            "model-sagittal", "sha256:abc123", "pfi.multiplanar-run.v2", null,
            false, null, comment, null, Instant.parse("2026-08-26T10:30:00Z")
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
