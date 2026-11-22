package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Etapa 78-B/78-C: body del request para
 * {@code PATCH /api/studies/{caseId}/product-analyses/{analysisId}/review} y
 * {@code PATCH .../disc-levels/{discLevelId}/review}. {@code status} debe ser uno de
 * {@code pending|accepted|observed|rejected} (validado del lado del servidor, sin distinguir mayúsculas) —
 * deliberadamente no es un vocabulario clínico. {@code reviewer}/{@code reviewedBy} está intencionalmente
 * ausente acá: el Backend lo deriva del principal autenticado, nunca del input del cliente.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiProductAnalysisReviewRequestDto(String status, String comment) {
}
