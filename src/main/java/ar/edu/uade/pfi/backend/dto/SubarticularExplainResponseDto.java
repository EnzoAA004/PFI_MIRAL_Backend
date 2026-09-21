package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * EXT-03: the AI module's own Grad-CAM explainability asset, re-exposed as-is (the Backend
 * validates and forwards, it never recomputes or reshapes this). {@code heatmapPng} is a small
 * base64-encoded 224x224 grayscale PNG — never a raw array, never a large blob.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubarticularExplainResponseDto(
    String modelId,
    String modelVersion,
    String task,
    String targetClass,
    String layer,
    String method,
    String heatmapPng,
    String generatedAt,
    Boolean experimental,
    String disclaimer,
    String layerDisclaimer
) {
}
