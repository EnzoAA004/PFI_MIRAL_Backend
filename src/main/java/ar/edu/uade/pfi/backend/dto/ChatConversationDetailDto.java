package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.util.List;

/** {@code GET /api/studies/{caseId}/chat-conversations/{conversationId}}. */
public record ChatConversationDetailDto(
    String conversationId, String caseId, Instant createdAt, String scopeKey, String runId, String referenceRunId, List<Message> messages
) {

    public record Message(
        String id, String role, String content, Instant createdAt, String model, String provider, String promptVersion,
        String contextFingerprint, List<ChatResponseDto.Reference> references
    ) {
    }
}
