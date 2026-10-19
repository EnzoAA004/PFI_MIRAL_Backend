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
    Provider provider,
    /** {@code structured} o {@code free}. */
    String mode,
    /** Las consultas que el asistente responde, con su intención; el texto exacto de cada una se resuelve sin consultar al modelo. */
    List<SuggestedQuestion> suggestedQuestions
) {
    public record SuggestedQuestion(String text, String intent) {
    }

    public record Provider(boolean enabled, boolean available, String model) {
    }
}
