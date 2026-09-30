package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Stage 78-D: request body for
 * {@code POST /api/studies/{caseId}/product-analyses/{analysisId}/measurements/{measurementId}/corrections}.
 *
 * <p>{@code field} selects which of the measurement's own numeric fields is being corrected
 * ({@code areaMm2|majorAxisMm|minorAxisMm} — the only ones this product exposes). {@code unit} is
 * never accepted here: it is derived server-side from {@code field}, so a correction can never
 * silently relabel a value's unit.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiMeasurementCorrectionRequestDto(String field, Double correctedValue, String comment) {
}
