package ar.edu.uade.pfi.backend.dto;

/**
 * EXT-03: upstream request to the AI module's {@code POST
 * /degenerative-findings/subarticular/explain} — mirrors {@link AiSubarticularPredictRequestDto}
 * plus the one addition, an optional {@code targetClass} (defaults server-side to the model's own
 * predicted class when omitted).
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
