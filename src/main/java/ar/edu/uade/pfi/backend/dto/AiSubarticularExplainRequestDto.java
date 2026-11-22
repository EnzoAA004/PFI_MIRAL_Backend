package ar.edu.uade.pfi.backend.dto;

/**
 * EXT-03: request upstream al {@code POST
 * /degenerative-findings/subarticular/explain} del módulo de IA — replica {@link AiSubarticularPredictRequestDto}
 * más la única adición, un {@code targetClass} opcional (por defecto, del lado del servidor, la clase
 * predicha por el propio modelo cuando se omite).
 */
public record AiSubarticularExplainRequestDto(
    String inputId,
    int instanceNumber,
    double x,
    double y,
    String side,
    String level,
    String targetClass
) {
}
