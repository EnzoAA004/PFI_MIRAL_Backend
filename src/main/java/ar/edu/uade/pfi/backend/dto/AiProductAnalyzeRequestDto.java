package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Body del request saliente para POST /v1/product/analyze (pfi.ai-product-result.v1). Replica el modelo
 * pydantic {@code ProductAnalyzeRequest} del módulo de IA (case_id/sagittal_input_id/axial_input_id
 * con alias a caseId/sagittalInputId/axialInputId, extra="forbid"): solo se envían identificadores y metadatos
 * de control — nunca un path local, PatientName, PatientID ni un UID DICOM crudo.
 *
 * <p>{@code capabilities} e {@code inputIds} existen en el contrato upstream pero
 * deliberadamente no se envían acá: los defaults propios del módulo de IA ya coinciden con lo que necesita esta etapa
 * (inferencia de patología P10.7 apagada por defecto), y duplicar esa política del lado del Backend
 * sería solo un lugar más donde podría desincronizarse de la release congelada.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiProductAnalyzeRequestDto(String caseId, String sagittalInputId, String axialInputId, Map<String, Object> metadata) {
}
