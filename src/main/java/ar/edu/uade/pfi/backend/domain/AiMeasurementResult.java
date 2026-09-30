package ar.edu.uade.pfi.backend.domain;

/**
 * One entry of {@code discLevels[].measurements[]}, persisted as its own row (stage 76-F) so
 * queries like "all measurements for structure X across a study's history" don't need to walk a
 * JSON blob. Field set matches {@code AiProductAnalyzeResponseDto.Measurement} exactly — no
 * {@code bboxHeightMm}/{@code bboxWidthMm} columns, because the real AI contract does not emit
 * them; adding unused columns here would be inventing contract fields the Backend does not
 * actually receive.
 */
public record AiMeasurementResult(
    String id,
    String analysisRunId,
    String discLevelResultId,
    String plane,
    String level,
    String structure,
    Double areaMm2,
    Double majorAxisMm,
    Double minorAxisMm,
    boolean reviewRequired
) {
}
