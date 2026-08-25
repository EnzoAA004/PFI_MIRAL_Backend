package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeResponseDto;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stage 75-D — domain translation between the raw POST /v1/product/analyze DTO and the rest of
 * the Backend. Deliberately thin: {@code completed}/{@code partial}/{@code failed} are returned
 * exactly as the AI Module reported them, never reinterpreted into a clinical judgment.
 *
 * <p>{@code partial} is a normal, successful call where some capability is
 * {@code unavailable}/{@code adapter_pending}/{@code blocked}/{@code experimental}/
 * {@code abstained}/{@code parity_not_proven} — it is returned as a 200 with that status, never
 * converted into an HTTP error. Only a real upstream failure ({@link AiProductUpstreamException})
 * or a contract/safety violation ({@link AiProductContractViolationException} /
 * {@link AiProductSafetyViolationException}) propagates as an exception — those are exceptional;
 * an AI-reported {@code failed} analysis status is not.
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
