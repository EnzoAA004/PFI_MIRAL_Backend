package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;

/**
 * Metadata con la que se crea o actualiza un estudio. {@code patientId} asocia un paciente
 * existente; el contexto clinico ({@code ageAtStudy} y siguientes) es propio del estudio.
 */
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
    String relevantHistory) {
  public StudyMetadataDto(
      String subjectRef, LocalDate studyDate, String modality, String description, String reviewPriority) {
    this(subjectRef, studyDate, modality, description, reviewPriority, null, null, null, null, null, null, null);
  }

  public boolean hasClinicalContext() {
    return ageAtStudy != null
        || ageProvenance != null
        || clinicalReason != null
        || symptoms != null
        || symptomDuration != null
        || relevantHistory != null;
  }
}
