package ar.edu.uade.pfi.backend.domain;

/**
 * Una entrada de {@code discLevels[].measurements[]}, persistida como su propia fila (etapa 76-F) para que
 * consultas como "todas las mediciones de la estructura X a lo largo del historial de un estudio" no necesiten recorrer un
 * blob JSON. El conjunto de campos coincide exactamente con {@code AiProductAnalyzeResponseDto.Measurement} — sin
 * columnas {@code bboxHeightMm}/{@code bboxWidthMm}, porque el contrato real de la IA no las emite;
 * agregar columnas sin uso acá sería inventar campos de contrato que el Backend no
 * recibe realmente.
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
