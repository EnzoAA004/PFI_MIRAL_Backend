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

  default boolean claimPatientOwner(String patientId, String principalId) { return false; }

  default boolean isPatientOwner(String patientId, String principalId) { return false; }

  default Optional<Patient> updateProfile(String patientId, String patientReference, String firstName, String lastName, LocalDate dateOfBirth, Instant updatedAt) {
    return updateReference(patientId, patientReference, updatedAt);
  }
}
