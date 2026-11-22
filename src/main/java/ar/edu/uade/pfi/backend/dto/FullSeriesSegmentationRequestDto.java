package ar.edu.uade.pfi.backend.dto;

/**
 * Contrato público/backend para la pasada de serie completa de P10.9.
 *
 * <p>El módulo de IA solo segmenta series para las que existe un modelo de segmentación entrenado compatible.
 * "Serie completa" significa cada slice de esa serie analizable soportada, no cada serie del
 * estudio subido.
 */
public record FullSeriesSegmentationRequestDto(
    String caseId, String inputId, String plane, String modelKey) {}
