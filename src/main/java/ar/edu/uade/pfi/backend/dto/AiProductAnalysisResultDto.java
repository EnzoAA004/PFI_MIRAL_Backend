package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.util.List;

/**
 * Stage 76-N: what the Frontend actually receives from POST/GET on
 * {@code /api/studies/{caseId}/product-analys(is|es)} — built from the persisted
 * {@code AiProductAnalysisRun}/{@code AiDiscLevelResult}/{@code AiMeasurementResult} rows, never
 * from the raw upstream response directly. Deliberately excludes the AI Module's base URL, the
 * resolved {@code sagittalInputId}/{@code axialInputId}, and any model checkpoint SHA — none of
 * those are needed by the Frontend and are not re-exposed here.
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
        List<MeasurementView> measurements
    ) {
    }

    public record MeasurementView(
        String plane,
        String level,
        String structure,
        Double areaMm2,
        Double majorAxisMm,
        Double minorAxisMm,
        boolean reviewRequired
    ) {
    }

    public record ReviewView(boolean required, String status) {
    }
}
