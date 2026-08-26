package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Stage 80-J: request body for {@code POST /api/studies/{caseId}/chat}. The Frontend never sends
 * measurements/context/facts of any kind here — only the question and, optionally, which
 * analysis/baseline to ground it in. Spring alone resolves the rest server-side (80-J: "Spring
 * resuelve todo el contexto").
 *
 * <p>Stage 81-C: {@code conversationId} is optional. {@code null} starts a new conversation
 * (Stage 80 behavior, unchanged). A non-null value continues an existing one — {@link
 * ar.edu.uade.pfi.backend.service.ChatService} validates it belongs to this Study before
 * appending a turn to it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRequestDto(String question, String analysisId, String baselineCaseId, String conversationId) {
}
