package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = false)
public record CreatePatientRequestDto(String patientReference, String firstName, String lastName, LocalDate dateOfBirth) {
  public CreatePatientRequestDto(String patientReference) { this(patientReference, null, null, null); }
}
