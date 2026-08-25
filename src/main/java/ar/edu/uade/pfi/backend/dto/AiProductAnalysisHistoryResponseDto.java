package ar.edu.uade.pfi.backend.dto;

import java.util.List;

/** Response body for {@code GET /api/studies/{caseId}/product-analyses} (stage 76-O). */
public record AiProductAnalysisHistoryResponseDto(String caseId, List<AiProductAnalysisResultDto> analyses) {
}
