package ar.edu.uade.pfi.backend.dto;

import java.util.List;

/**
 * {@code GET /api/studies/{caseId}/chat-context}: qué datos respaldarían una pregunta ahora (sin consultar al modelo).
 * {@code contextText} es exactamente lo que recibiría el proveedor, para que el profesional pueda revisarlo.
 */
public record ChatContextInfoDto(
    ChatResponseDto.Scope scope,
    String contextFingerprint,
    String promptVersion,
    List<String> contextSources,
    List<String> missing,
    int elementCount,
    String contextText,
    Provider provider
) {
    public record Provider(boolean enabled, boolean available, String model) {
    }
}
