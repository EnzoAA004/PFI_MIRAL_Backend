package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.util.List;

/** Stage 80-V: {@code GET /api/studies/{caseId}/chat-conversations} — a summary list, not full transcripts. */
public record ChatConversationHistoryResponseDto(String caseId, List<Summary> conversations) {

    public record Summary(String conversationId, Instant createdAt, int messageCount) {
    }
}
