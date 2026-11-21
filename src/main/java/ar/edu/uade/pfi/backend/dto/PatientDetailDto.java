package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.time.LocalDate;

public record PatientDetailDto(
    String id, String patientReference, String firstName, String lastName, LocalDate dateOfBirth, Instant createdAt, Instant updatedAt, boolean active) {
  public PatientDetailDto(String id, String patientReference, Instant createdAt, Instant updatedAt) { this(id, patientReference, null, null, null, createdAt, updatedAt, true); }
  public PatientDetailDto(String id, String patientReference, String firstName, String lastName, LocalDate dateOfBirth, Instant createdAt, Instant updatedAt) { this(id, patientReference, firstName, lastName, dateOfBirth, createdAt, updatedAt, true); }
}
