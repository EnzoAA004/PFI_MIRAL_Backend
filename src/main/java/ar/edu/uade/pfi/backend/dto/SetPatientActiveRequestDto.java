package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Habilita (true) o deshabilita (false) un paciente; no se puede enviar nada más. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SetPatientActiveRequestDto(Boolean active) {}
