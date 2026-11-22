package ar.edu.uade.pfi.backend.client;

/**
 * Etapa 80: un turno dentro de un {@link LlmChatRequest} — {@code role} es {@code system|user|assistant},
 * forma de dominio simple, nunca el JSON crudo estilo OpenAI. Traducir esto desde/hacia el
 * formato wire de un proveedor específico es exactamente para lo que existen las implementaciones de {@link LlmProvider}.
 */
public record LlmChatMessage(String role, String content) {
}
