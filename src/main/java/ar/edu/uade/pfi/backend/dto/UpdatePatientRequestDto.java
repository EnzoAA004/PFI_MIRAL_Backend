package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;

/**
 * Cambio parcial: un campo ausente (null) no se toca. Para borrar nombre o apellido se envia
 * una cadena vacia.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record UpdatePatientRequestDto(
    String patientReference, String firstName, String lastName, LocalDate dateOfBirth) {
  public UpdatePatientRequestDto(String patientReference) {
    this(patientReference, null, null, null);
  }
}
