package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.util.List;

/**
 * Stage 76-N / 78: what the Frontend actually receives from POST/GET on
 * {@code /api/studies/{caseId}/product-analys(is|es)} — built from the persisted
 * {@code AiProductAnalysisRun}/{@code AiDiscLevelResult}/{@code AiMeasurementResult} rows plus
 * their professional-review overlay (stage 78), never from the raw upstream response directly.
 * Deliberately excludes the AI Module's base URL, the resolved
 * {@code sagittalInputId}/{@code axialInputId}, and any model checkpoint SHA — none of those are
 * needed by the Frontend and are not re-exposed here.
 *
 * <p>{@code discLevelId}/{@code measurementId} ARE exposed (unlike the fields above) — they are
 * opaque, Backend-generated identifiers with no clinical/infrastructure meaning, and the
 * Frontend needs them to address the stage-78 review/correction endpoints.
 */
public record AiProductAnalysisResultDto(
    String analysisRunId,
    String studyId,
    String caseId,
    String status,
    List<String> warnings,
    List<DiscLevelView> discLevels,
    ReviewView review,
    boolean humanReviewRequired,
    boolean notClinicalDiagnosis,
    boolean autonomousDiagnosis,
    Instant createdAt,
    Instant completedAt
) {

    public record DiscLevelView(
        String discLevelId,
        String level,
        String localizationStatus,
        boolean localizationValidated,
        boolean abstentionPreserved,
        String axialPairingStatus,
        boolean axialPairingValidated,
        boolean axialPairingExperimental,
        boolean axialPairingReviewRequired,
        String degenerativeFindingsStatus,
        boolean automaticInferenceEnabled,
        List<MeasurementView> measurements,
        ReviewView review
    ) {
    }

    public record MeasurementView(
        String measurementId,
        String plane,
        String level,
        String structure,
        Double areaMm2,
        Double majorAxisMm,
        Double minorAxisMm,
        boolean reviewRequired,
        List<MeasurementCorrectionView> corrections
    ) {
    }

    /** One field's current professional correction (stage 78-D) — the AI's original value is always preserved alongside it. */
    public record MeasurementCorrectionView(
        String field,
        Double originalValue,
        double correctedValue,
        String unit,
        String comment,
        String reviewedBy,
        Instant createdAt
    ) {
    }

    /**
     * Professional-review decision — reused as-is for both the run-level and each disc-level's
     * review (stage 78-B/78-C). {@code status} is one of
     * {@code pending|accepted|observed|rejected}; never a clinical vocabulary. This is always a
     * distinct concept from the AI's own {@code localizationStatus}/{@code axialPairingStatus}/
     * {@code validated} fields above — accepting a review here never changes those.
     */
    public record ReviewView(boolean required, String status, String comment, String reviewedBy, Instant reviewedAt) {
    }
}
