package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * EXT-03: el propio asset de explicabilidad Grad-CAM del módulo de IA, re-expuesto tal cual (el Backend
 * valida y reenvía, nunca recalcula ni remodela esto). {@code heatmapPng} es un PNG
 * en escala de grises de 224x224 chico codificado en base64 — nunca un array crudo, nunca un blob grande.
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
