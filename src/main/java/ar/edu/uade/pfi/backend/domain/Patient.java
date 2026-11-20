package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** De-identified longitudinal patient identity. Direct patient identifiers are out of scope. */
public record Patient(String id, String patientReference, String firstName, String lastName, LocalDate dateOfBirth, Instant createdAt, Instant updatedAt) {
  public Patient(String id, String patientReference, Instant createdAt, Instant updatedAt) {
    this(id, patientReference, null, null, null, createdAt, updatedAt);
  }
  public Patient {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(patientReference, "patientReference");
    patientReference = patientReference.trim();
    if (patientReference.isEmpty()) throw new IllegalArgumentException("patientReference is blank");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    firstName = clean(firstName);
    lastName = clean(lastName);
  }

  private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
