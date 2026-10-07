package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Longitudinal patient identity: a visible code plus an optional first name, last name and date
 * of birth. Administrative identifiers (national id, address, ...) are out of scope.
 */
public record Patient(
    String id,
    String patientReference,
    String firstName,
    String lastName,
    LocalDate dateOfBirth,
    Instant createdAt,
    Instant updatedAt) {
  /** Compatibility constructor for patients that only carry a reference. */
  public Patient(String id, String patientReference, Instant createdAt, Instant updatedAt) {
    this(id, patientReference, null, null, null, createdAt, updatedAt);
  }

  public Patient {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(patientReference, "patientReference");
    patientReference = patientReference.trim();
    if (patientReference.isEmpty()) throw new IllegalArgumentException("patientReference is blank");
    firstName = blankToNull(firstName);
    lastName = blankToNull(lastName);
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
