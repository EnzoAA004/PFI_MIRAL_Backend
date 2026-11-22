package ar.edu.uade.pfi.backend.dto;

import java.util.List;

/**
 * Wrapper del backend para la llamada de producto P10.7.
 *
 * <p>{@code multiplanarRunId} es solo del backend y nunca se envía al módulo de IA. Identifica el
 * snapshot inmutable de corrida que recibirá la predicción P10.7.
 */
public record DiscDegenerativeProductRequestDto(
    String multiplanarRunId, String caseId, List<DiscSegmentationSourceDto> sources) {}
