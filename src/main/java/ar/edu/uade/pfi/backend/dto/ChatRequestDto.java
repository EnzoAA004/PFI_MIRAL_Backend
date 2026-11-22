package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Etapa 80-J: body del request de {@code POST /api/studies/{caseId}/chat}. El Frontend nunca envía
 * mediciones/contexto/hechos de ningún tipo acá — solo la pregunta y, opcionalmente, en qué
 * análisis/baseline fundamentarla. Spring resuelve por sí solo el resto del lado del servidor (80-J: "Spring
 * resuelve todo el contexto").
 *
 * <p>Etapa 81-C: {@code conversationId} es opcional. {@code null} inicia una nueva conversación
 * (comportamiento de la Etapa 80, sin cambios). Un valor no nulo continúa una existente — {@link
 * ar.edu.uade.pfi.backend.service.ChatService} valida que pertenezca a este Study antes de
 * agregarle un turno.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRequestDto(String question, String analysisId, String baselineCaseId, String conversationId) {
}
