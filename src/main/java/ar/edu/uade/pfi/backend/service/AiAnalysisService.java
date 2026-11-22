package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeResponseDto;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Etapa 75-D — traducción de dominio entre el DTO crudo de POST /v1/product/analyze y el resto de
 * el Backend. Deliberadamente delgada: {@code completed}/{@code partial}/{@code failed} se devuelven
 * exactamente como los informó el módulo de IA, nunca reinterpretados como un juicio clínico.
 *
 * <p>{@code partial} es una llamada normal y exitosa donde alguna capacidad está
 * {@code unavailable}/{@code adapter_pending}/{@code blocked}/{@code experimental}/
 * {@code abstained}/{@code parity_not_proven} — se devuelve como un 200 con ese status, nunca
 * convertido en un error HTTP. Solo una falla upstream real ({@link AiProductUpstreamException})
 * o una violación de contrato/seguridad ({@link AiProductContractViolationException} /
 * {@link AiProductSafetyViolationException}) se propaga como excepción — esas son excepcionales;
 * un estado de análisis {@code failed} informado por la IA no lo es.
 */
@Service
public class AiAnalysisService {
    private final AiServiceOperations aiServiceClient;

    public AiAnalysisService(AiServiceOperations aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    public AiProductAnalyzeResponseDto analyze(String caseId, String sagittalInputId, String axialInputId, Map<String, Object> metadata) {
        String sagittal = blankToNull(sagittalInputId);
        String axial = blankToNull(axialInputId);
        if (sagittal == null && axial == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Se requiere sagittalInputId o axialInputId.");
        }
        AiProductAnalyzeRequestDto request = new AiProductAnalyzeRequestDto(caseId, sagittal, axial, metadata);
        return aiServiceClient.analyzeProduct(request);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
