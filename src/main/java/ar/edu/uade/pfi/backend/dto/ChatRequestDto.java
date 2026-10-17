package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code POST /api/studies/{caseId}/chat}. El navegador nunca envía mediciones, hallazgos ni contexto: solo la pregunta
 * y QUÉ datos usar. {@code runId} (la corrida multiplanar que muestra el visor) es obligatorio; si se agregan {@code
 * referenceCaseId} y {@code referenceRunId} el ámbito pasa a ser el par A/B del visor dual. El servidor resuelve y valida
 * todo lo demás.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRequestDto(String question, String runId, String referenceCaseId, String referenceRunId, String conversationId) {
}
