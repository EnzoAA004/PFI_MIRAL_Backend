package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.util.List;

/**
 * Etapa 80-K/80-L: {@code humanReviewRequired}/{@code notClinicalDiagnosis} son siempre {@code
 * true} — seteados incondicionalmente por {@code ChatService}, nunca derivados de la respuesta del proveedor.
 * Nunca se expone acá infraestructura interna (URL base del LLM, hostnames, paths de checkpoints).
 */
public record ChatResponseDto(
    String answer,
    String conversationId,
    String messageId,
    String model,
    String provider,
    String promptVersion,
    Instant generatedAt,
    List<String> warnings,
    boolean humanReviewRequired,
    boolean notClinicalDiagnosis,
    List<String> contextSources,
    String contextVersion,
    String draftStatus
) {
}
