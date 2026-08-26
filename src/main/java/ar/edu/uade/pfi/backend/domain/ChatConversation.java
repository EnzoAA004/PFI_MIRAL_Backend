package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;

/**
 * Stage 80-S: one chat exchange thread for a Study. A Study can have many conversations
 * (80-U) — this stage creates exactly one new conversation per {@code POST .../chat} call
 * (single-turn per conversation; a future stage could add an optional {@code conversationId} to
 * the request to continue one, but that is not built here — documented as a deliberate
 * simplification, not an oversight).
 */
public record ChatConversation(String id, String studyId, Instant createdAt) {
}
