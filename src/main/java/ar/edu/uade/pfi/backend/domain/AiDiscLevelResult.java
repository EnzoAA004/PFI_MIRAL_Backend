package ar.edu.uade.pfi.backend.domain;

/**
 * One entry of {@code discLevels[]} from an {@link AiProductAnalysisRun}, persisted structurally.
 * {@code rankIndex} preserves the original array order (0-based) since {@code level} alone cannot
 * — a study can have more than one {@code null}-level entry.
 *
 * <p>{@code level} is nullable BY CONTRACT: {@code null} is the valid, expected shape when
 * {@code localizationStatus} is {@code unavailable} or {@code abstained}. Never coerced to a
 * placeholder string, here or anywhere this record is read.
 */
public record AiDiscLevelResult(
    String id,
    String analysisRunId,
    int rankIndex,
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
    boolean automaticInferenceEnabled
) {
}
