package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Stage 80-J: request body for {@code POST /api/studies/{caseId}/chat}. The Frontend never sends
 * measurements/context/facts of any kind here — only the question and, optionally, which
 * analysis/baseline to ground it in. Spring alone resolves the rest server-side (80-J: "Spring
 * resuelve todo el contexto").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRequestDto(String question, String analysisId, String baselineCaseId) {
}
