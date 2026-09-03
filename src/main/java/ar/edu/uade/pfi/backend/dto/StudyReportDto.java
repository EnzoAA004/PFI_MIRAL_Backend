package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
    /**
     * EXT-04B2: additive, backward-compatible — always present (an empty list when there is no
     * evidence to show, never omitted), so a consumer that only knows about pre-EXT-04B2 fields
     * keeps working unchanged. Never a diagnostic claim: see {@link VisualEvidenceSummary}.
     */
    List<VisualEvidenceSummary> visualEvidence,
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

    /**
     * EXT-04B2: one visual region of interest — never a diagnostic claim. {@code geometry} is
     * always the already-resolved EFFECTIVE geometry (corrected-if-any, else the AI's or the
     * professional's own), in the canonical normalised 0..256 basis; for
     * {@code geometryType="MASK_REFERENCE"} it only carries {@code {runId, plane, assetName}},
     * resolved through the existing {@code /api/ai/assets/{runId}/{plane}/{assetName}} endpoint
     * — never a filesystem path, bucket path, or DICOM UID.
     *
     * <p>{@code source} is one of {@code ai_suggested|ai_validated|professional_corrected|
     * professional_drawn} — the same four EXT-04B1 concepts, always DERIVED (never an
     * independently-persisted enum). {@code reviewedBy}/{@code authoredBy} are never exposed
     * here — same rule EXT-04A already applies to the run/disc-level review summaries.
     */
    public record VisualEvidenceSummary(
        String evidenceId,
        String plane,
        String level,
        String structure,
        String geometryType,
        Map<String, Object> geometry,
        String source,
        String reviewStatus,
        String label,
        String warning,
        boolean experimental
    ) {
    }
}
