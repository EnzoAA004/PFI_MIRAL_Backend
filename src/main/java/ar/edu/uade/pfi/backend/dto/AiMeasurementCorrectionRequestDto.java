package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Etapa 78-D: body del request para
 * {@code POST /api/studies/{caseId}/product-analyses/{analysisId}/measurements/{measurementId}/corrections}.
 *
 * <p>{@code field} selecciona cuál de los campos numéricos propios de la medición se está corrigiendo
 * ({@code areaMm2|majorAxisMm|minorAxisMm} — los únicos que expone este producto). {@code unit}
 * nunca se acepta acá: se deriva del lado del servidor a partir de {@code field}, de modo que una corrección nunca pueda
 * reetiquetar en silencio la unidad de un valor.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiMeasurementCorrectionRequestDto(String field, Double correctedValue, String comment) {
}
