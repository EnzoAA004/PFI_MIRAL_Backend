package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Outbound request body for POST /v1/product/analyze (pfi.ai-product-result.v1). Mirrors the AI
 * Module's {@code ProductAnalyzeRequest} pydantic model (case_id/sagittal_input_id/axial_input_id
 * aliased to caseId/sagittalInputId/axialInputId, extra="forbid"): only identifiers and control
 * metadata are sent — never a local path, PatientName, PatientID, or a raw DICOM UID.
 *
 * <p>{@code capabilities} and {@code inputIds} exist on the upstream contract but are
 * deliberately not sent here: the AI Module's own defaults already match what this stage needs
 * (P10.7 pathology inference off by default), and duplicating that policy on the Backend side
 * would just be one more place it could drift out of sync with the frozen release.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiProductAnalyzeRequestDto(String caseId, String sagittalInputId, String axialInputId, Map<String, Object> metadata) {
}
