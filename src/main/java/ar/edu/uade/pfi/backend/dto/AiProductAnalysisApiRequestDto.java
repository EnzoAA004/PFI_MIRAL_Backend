package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Public request body for {@code POST /api/studies/{caseId}/product-analysis}. {@code caseId}
 * comes from the path, not the body — this is a sub-resource of a study, matching the
 * {@code /api/studies/{caseId}/...} convention already used elsewhere in this controller family.
 *
 * <p>At least one of {@code sagittalInputId}/{@code axialInputId} must be present — enforced in
 * the service layer (the same rule the AI Module itself enforces on
 * {@code ProductAnalyzeRequest}), not duplicated here as a bean-validation annotation so the
 * error message stays under this Backend's control.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiProductAnalysisApiRequestDto(String sagittalInputId, String axialInputId, Map<String, Object> metadata) {
}
