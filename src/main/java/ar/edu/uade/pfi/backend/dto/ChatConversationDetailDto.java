package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.util.List;

/** Etapa 80-V: {@code GET /api/studies/{caseId}/chat-conversations/{conversationId}}. */
public record ChatConversationDetailDto(String conversationId, String caseId, Instant createdAt, List<Message> messages) {

    public record Message(String id, String role, String content, Instant createdAt, String model, String provider, String promptVersion, String contextVersion, boolean outdated, String draftStatus) {
    }
}
