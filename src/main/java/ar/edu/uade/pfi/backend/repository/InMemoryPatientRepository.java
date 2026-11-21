package ar.edu.uade.pfi.backend.repository;

import ar.edu.uade.pfi.backend.domain.Patient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "pfi.persistence.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryPatientRepository implements PatientRepository {
  private final ConcurrentMap<String, Patient> patientsById = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, String> idsByNormalizedReference = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, String> patientOwners = new ConcurrentHashMap<>();

  @Override
  public synchronized Patient save(Patient patient) {
    String normalized = normalize(patient.patientReference());
    if (idsByNormalizedReference.containsKey(normalized)) {
      throw new DuplicatePatientReferenceException(null);
    }
    patientsById.put(patient.id(), patient);
    idsByNormalizedReference.put(normalized, patient.id());
    return patient;
  }

  @Override
  public Optional<Patient> findById(String patientId) {
    return Optional.ofNullable(patientsById.get(patientId));
  }

  @Override
  public List<Patient> searchByReferencePrefix(String query, int limit) {
    String normalizedQuery = normalize(query);
    return patientsById.values().stream()
        .filter(patient -> normalize(patient.patientReference()).startsWith(normalizedQuery))
        .sorted(
            Comparator.comparing((Patient patient) -> normalize(patient.patientReference()))
                .thenComparing(Patient::id))
        .limit(limit)
        .toList();
  }

  @Override
  public List<Patient> search(String query, int limit) {
    String normalizedQuery = normalize(query);
    return patientsById.values().stream()
        .filter(patient -> normalize(patient.patientReference()).startsWith(normalizedQuery)
            || normalize(patient.firstName()).contains(normalizedQuery)
            || normalize(patient.lastName()).contains(normalizedQuery)
            || normalize((patient.firstName() == null ? "" : patient.firstName()) + " "
                + (patient.lastName() == null ? "" : patient.lastName())).contains(normalizedQuery))
        .sorted(Comparator.comparing((Patient patient) -> normalize(patient.patientReference()))
            .thenComparing(Patient::id))
        .limit(limit)
        .toList();
  }

  @Override
  public synchronized Optional<Patient> updateReference(
      String patientId, String patientReference, Instant updatedAt) {
    Patient current = patientsById.get(patientId);
    if (current == null) return Optional.empty();
    String oldNormalized = normalize(current.patientReference());
    String newNormalized = normalize(patientReference);
    String owner = idsByNormalizedReference.get(newNormalized);
    if (owner != null && !owner.equals(patientId)) {
      throw new DuplicatePatientReferenceException(null);
    }
    Patient updated = new Patient(current.id(), patientReference, current.firstName(), current.lastName(), current.dateOfBirth(), current.createdAt(), updatedAt);
    patientsById.put(patientId, updated);
    idsByNormalizedReference.remove(oldNormalized);
    idsByNormalizedReference.put(newNormalized, patientId);
    return Optional.of(updated);
  }

  @Override
  public synchronized Optional<Patient> updateProfile(String patientId, String patientReference, String firstName, String lastName, LocalDate dateOfBirth, Instant updatedAt) {
    Patient current = patientsById.get(patientId);
    if (current == null) return Optional.empty();
    String oldNormalized = normalize(current.patientReference());
    String newNormalized = normalize(patientReference);
    String owner = idsByNormalizedReference.get(newNormalized);
    if (owner != null && !owner.equals(patientId)) throw new DuplicatePatientReferenceException(null);
    Patient updated = new Patient(current.id(), patientReference, firstName, lastName, dateOfBirth, current.createdAt(), updatedAt);
    patientsById.put(patientId, updated);
    idsByNormalizedReference.remove(oldNormalized);
    idsByNormalizedReference.put(newNormalized, patientId);
    return Optional.of(updated);
  }

  @Override
  public boolean claimPatientOwner(String patientId, String principalId) {
    if (patientId == null || principalId == null || principalId.isBlank()) return false;
    String existing = patientOwners.putIfAbsent(patientId, principalId);
    return existing == null || existing.equals(principalId);
  }

  @Override
  public boolean isPatientOwner(String patientId, String principalId) {
    return principalId != null && principalId.equals(patientOwners.get(patientId));
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
