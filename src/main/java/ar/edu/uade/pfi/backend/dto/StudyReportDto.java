package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Stage 88: backend-authoritative, on-demand report/export contract. */
public record StudyReportDto(
    String schemaVersion,
    Instant generatedAt,
    String reportStatus,
    String caseId,
    LocalDate studyDate,
    String modality,
    AnalysisSummary analysis,
    ReviewSummary review,
    List<DiscLevelSummary> discLevels,
    List<MeasurementSummary> measurements,
    List<String> warnings,
    List<String> limitations,
    LongitudinalComparisonResultDto longitudinalComparison,
    LongitudinalHistorySummary longitudinalHistory,
    SafetySummary safety,
    TraceabilitySummary traceability
) {
    public record AnalysisSummary(
        String analysisRunId,
        String analysisStatus,
        String releaseId,
        Instant createdAt,
        Instant completedAt,
        boolean humanReviewRequired
    ) {
    }

    public record ReviewSummary(
        String reviewStatus,
        String reviewComment,
        Instant reviewedAt
    ) {
    }

    public record DiscLevelSummary(
        String discLevelResultId,
        Integer rankIndex,
        String level,
        String localizationStatus,
        String localizationSource,
        boolean localizationValidated,
        boolean abstentionPreserved,
        String axialPairingStatus,
        boolean axialPairingValidated,
        boolean axialPairingExperimental,
        boolean axialPairingReviewRequired,
        String degenerativeFindingsStatus,
        boolean automaticInferenceEnabled,
        String reviewStatus,
        String reviewComment,
        Instant reviewedAt
    ) {
    }

    public record MeasurementSummary(
        String level,
        String plane,
        String structure,
        String metric,
        String unit,
        Double automaticValue,
        Double correctedValue,
        Double effectiveValue,
        String effectiveSource
    ) {
    }

    public record LongitudinalHistorySummary(
        boolean available,
        CurrentStudySummary currentStudy,
        List<PreviousStudySummary> previousStudies,
        List<LongitudinalHistoryComparison> comparisons,
        String message
    ) {
    }

    public record CurrentStudySummary(
        String caseId,
        LocalDate studyDate,
        String analysisRunId
    ) {
    }

    public record PreviousStudySummary(
        String caseId,
        LocalDate studyDate,
        String analysisRunId,
        String comparisonStatus,
        int comparableMeasurementCount,
        int nonComparableMeasurementCount
    ) {
    }

    public record LongitudinalHistoryComparison(
        String previousCaseId,
        LocalDate previousStudyDate,
        LocalDate currentStudyDate,
        String level,
        String plane,
        String structure,
        String metric,
        String unit,
        Double previousEffectiveValue,
        Double currentEffectiveValue,
        Double absoluteDelta,
        Double percentageDelta,
        String previousValueSource,
        String currentValueSource,
        Double previousAutomaticValue,
        Double currentAutomaticValue,
        Double previousCorrectedValue,
        Double currentCorrectedValue
    ) {
    }

    public record SafetySummary(
        boolean humanReviewRequired,
        boolean notClinicalDiagnosis,
        boolean autonomousDiagnosis,
        String disclaimer
    ) {
    }

    public record TraceabilitySummary(
        String caseId,
        String analysisRunId,
        String analysisSchemaVersion,
        String releaseId,
        Instant generatedAt
    ) {
    }
}
