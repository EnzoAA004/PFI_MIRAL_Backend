package ar.edu.uade.pfi.backend.dto;

import java.util.List;

/** Body de respuesta de {@code GET /api/studies/{caseId}/product-analyses} (etapa 76-O). */
public record AiProductAnalysisHistoryResponseDto(String caseId, List<AiProductAnalysisResultDto> analyses) {
}
