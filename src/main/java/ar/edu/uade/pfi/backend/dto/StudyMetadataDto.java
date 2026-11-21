package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StudyMetadataDto(
    String subjectRef,
    LocalDate studyDate,
    String modality,
    String description,
    String reviewPriority,
    String patientId,
    Integer ageAtStudy,
    String ageProvenance,
    String clinicalReason,
    String symptoms,
    String symptomDuration,
    String relevantHistory,
    String contextVersion) {
  public StudyMetadataDto(String subjectRef, LocalDate studyDate, String modality, String description, String reviewPriority) {
    this(subjectRef, studyDate, modality, description, reviewPriority, null, null, null, null, null, null, null, null);
  }
}
