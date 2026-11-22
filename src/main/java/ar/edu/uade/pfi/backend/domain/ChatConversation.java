package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;

/**
 * Etapa 80-S: un hilo de intercambio de chat para un Study. Un Study puede tener muchas conversaciones
 * (80-U) — esta etapa crea exactamente una conversación nueva por cada llamada {@code POST .../chat}
 * (un solo turno por conversación; una etapa futura podría agregar un {@code conversationId} opcional a
 * el request para continuar una, pero eso no está construido acá — documentado como una simplificación
 * deliberada, no como un descuido).
 */
public record ChatConversation(String id, String studyId, Instant createdAt) {
}
