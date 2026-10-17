package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/studies/{caseId}/chat-conversations}: solo las conversaciones del ámbito pedido (una corrida, o el par
 * de corridas). Las del circuito de demostración anterior no aparecen.
 */
public record ChatConversationHistoryResponseDto(String caseId, String scopeKey, List<Summary> conversations) {

    public record Summary(String conversationId, Instant createdAt, int messageCount) {
    }
}
