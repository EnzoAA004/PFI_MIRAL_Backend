package ar.edu.uade.pfi.backend.repository;

import ar.edu.uade.pfi.backend.domain.Patient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PatientRepository {
  Patient save(Patient patient);

  Optional<Patient> findById(String patientId);

  List<Patient> searchByReferencePrefix(String query, int limit);

  default List<Patient> search(String query, int limit) {
    return searchByReferencePrefix(query, limit);
  }

  Optional<Patient> updateReference(String patientId, String patientReference, Instant updatedAt);

  /** Como {@link #search} pero oculta los pacientes deshabilitados salvo que {@code includeInactive}. */
  default List<Patient> search(String query, int limit, boolean includeInactive) {
    return search(query, limit).stream().filter(p -> includeInactive || p.active()).toList();
  }

  default Optional<Patient> setActive(String patientId, boolean active, Instant updatedAt) {
    throw new UnsupportedOperationException("setActive");
  }

  default boolean claimPatientOwner(String patientId, String principalId) { return false; }

  default boolean isPatientOwner(String patientId, String principalId) { return false; }

  default Optional<Patient> updateProfile(String patientId, String patientReference, String firstName, String lastName, LocalDate dateOfBirth, Instant updatedAt) {
    return updateReference(patientId, patientReference, updatedAt);
  }
}
