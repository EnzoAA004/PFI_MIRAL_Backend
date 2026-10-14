package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiInputResponseDto(
    String inputId, String caseId, String plane, String format, long size, Sequence sequence) {

  public AiInputResponseDto(String inputId, String caseId, String plane, String format, long size) {
    this(inputId, caseId, plane, format, size, null);
  }

  /** Ponderación registrada del archivo y de dónde sale: {@code user_declared}, {@code auto_detected} o desconocida. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Sequence(String value, String source, String description) {}
}
