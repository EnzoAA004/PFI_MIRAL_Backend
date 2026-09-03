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
    /**
     * EXT-04B4: additive, backward-compatible — always present (empty when there is no
     * comparable previous study, never omitted). One entry per
     * {@code longitudinalHistory.comparisons[]} row (same cap, same source of truth for the
     * numeric values/deltas — never recomputed here), each optionally paired with a matching
     * {@link VisualEvidenceSummary} on either side when one exists at the same level/plane/
     * structure. Consumed by the PDF's "Comparación con estudio previo" section; the JSON/UI
     * report already has this information split across {@code longitudinalHistory} and
     * {@code visualEvidence} separately, so this field exists purely to save the PDF renderer
     * from re-deriving the same pairing the Frontend already does client-side (EXT-07B).
     */
    List<BeforeAfterEvidencePairDto> beforeAfterEvidence,
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
        boolean experimental,
        /**
         * EXT-04B3.5: additive, nullable — how Frontend fetches a real base image for this
         * region, reusing the existing, already-tested {@code GET /api/ai/series/{inputId}/
         * slices/{index}} endpoint (Stage 87/89) rather than a new asset mechanism. {@code null}
         * when the region's own analysis run has no per-plane inputId for {@code plane}, or when
         * the region itself carries no {@code sliceIndex} — a fail-safe absence, never a
         * fabricated reference. See {@link BaseImageRef}.
         */
        BaseImageRef baseImage
    ) {
    }

    /**
     * EXT-04B3.5: {@code inputId} is the Backend's own opaque per-upload identifier (the same
     * shape already used by {@code /inputs} and {@code AiProductAnalysisRun.sagittalInputId}/
     * {@code axialInputId} — never a filesystem path, host, bucket, or DICOM UID) and
     * {@code sliceIndex} is the same 0-based index {@code AiBackendService.getSeriesSlice}
     * already validates. No {@code plane} field here: an {@code inputId} is already per-plane
     * (one per {@code AiProductAnalysisRun} plane), so it would be redundant with
     * {@code VisualEvidenceSummary.plane}.
     */
    public record BaseImageRef(String inputId, Integer sliceIndex) {
    }

    /**
     * EXT-04B4: one "Anterior vs Actual" row — the numeric fields are copied verbatim from the
     * matching {@link LongitudinalHistoryComparison} row (never recomputed), and
     * {@code previousEvidence}/{@code currentEvidence} are {@code null} (never fabricated) when
     * no {@link VisualEvidenceSummary} exists at the same level/plane/structure on that side.
     */
    public record BeforeAfterEvidencePairDto(
        String level,
        String plane,
        String structure,
        String metric,
        String unit,
        LocalDate previousStudyDate,
        LocalDate currentStudyDate,
        Double previousEffectiveValue,
        Double currentEffectiveValue,
        Double absoluteDelta,
        Double percentageDelta,
        String previousValueSource,
        String currentValueSource,
        VisualEvidenceSummary previousEvidence,
        VisualEvidenceSummary currentEvidence
    ) {
    }
}
