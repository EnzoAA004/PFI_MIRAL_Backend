package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Enable (true) or disable (false) a patient; nothing else can be sent. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SetPatientActiveRequestDto(Boolean active) {}
