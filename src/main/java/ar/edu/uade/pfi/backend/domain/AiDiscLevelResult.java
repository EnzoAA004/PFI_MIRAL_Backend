package ar.edu.uade.pfi.backend.domain;

/**
 * Una entrada de {@code discLevels[]} de un {@link AiProductAnalysisRun}, persistida de forma estructurada.
 * {@code rankIndex} preserva el orden original del array (base 0) ya que {@code level} por sí solo no puede
 * — un estudio puede tener más de una entrada con nivel {@code null}.
 *
 * <p>{@code level} admite null POR CONTRATO: {@code null} es la forma válida y esperada cuando
 * {@code localizationStatus} es {@code unavailable} o {@code abstained}. Nunca se fuerza a un
 * string placeholder, ni acá ni en ningún lugar donde se lea este record.
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
