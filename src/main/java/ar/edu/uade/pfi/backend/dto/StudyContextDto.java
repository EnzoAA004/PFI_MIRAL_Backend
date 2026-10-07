package ar.edu.uade.pfi.backend.dto;

import java.time.LocalDate;

/**
 * Paciente asociado y contexto clinico persistido de un estudio. El contexto sale de los campos
 * propios del estudio, nunca del paciente actual.
 */
public record StudyContextDto(
    String caseId,
    PatientRef patient,
    ClinicalContext clinicalContext) {
  public record PatientRef(
      String id, String patientReference, String firstName, String lastName, LocalDate dateOfBirth) {}

  public record ClinicalContext(
      Integer ageAtStudy,
      String ageProvenance,
      String clinicalReason,
      String symptoms,
      String symptomDuration,
      String relevantHistory,
      String contextVersion) {}
}
