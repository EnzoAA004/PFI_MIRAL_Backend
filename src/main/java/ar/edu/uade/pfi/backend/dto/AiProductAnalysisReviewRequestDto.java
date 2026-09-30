package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Stage 78-B/78-C: request body for
 * {@code PATCH /api/studies/{caseId}/product-analyses/{analysisId}/review} and
 * {@code PATCH .../disc-levels/{discLevelId}/review}. {@code status} must be one of
 * {@code pending|accepted|observed|rejected} (validated server-side, case-insensitive) —
 * deliberately not a clinical vocabulary. {@code reviewer}/{@code reviewedBy} is intentionally
 * absent here: the Backend derives it from the authenticated principal, never from client input.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiProductAnalysisReviewRequestDto(String status, String comment) {
}
