package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.util.List;

/**
 * Stage 80-K/80-L: {@code humanReviewRequired}/{@code notClinicalDiagnosis} are always {@code
 * true} — set unconditionally by {@code ChatService}, never derived from the provider's response.
 * No internal infrastructure (LLM base URL, hostnames, checkpoint paths) is ever exposed here.
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
    List<String> contextSources
) {
}
