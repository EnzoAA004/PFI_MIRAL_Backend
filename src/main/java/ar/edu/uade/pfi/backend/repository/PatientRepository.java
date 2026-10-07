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

  Optional<Patient> updateReference(String patientId, String patientReference, Instant updatedAt);

  /** Busca por prefijo de codigo o por nombre/apellido (contiene), sin distinguir mayusculas. */
  List<Patient> search(String query, int limit);

  /** Reemplaza referencia y perfil del paciente; quien llama ya resolvio los campos no tocados. */
  Optional<Patient> updateProfile(
      String patientId,
      String patientReference,
      String firstName,
      String lastName,
      LocalDate dateOfBirth,
      Instant updatedAt);
}
