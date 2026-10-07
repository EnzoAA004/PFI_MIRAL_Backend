package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.Patient;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.StudyContextDto;
import ar.edu.uade.pfi.backend.repository.PatientRepository;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import ar.edu.uade.pfi.backend.service.exception.DatabaseUnavailableException;
import ar.edu.uade.pfi.backend.service.exception.StudyNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Lee el paciente asociado y el contexto clinico persistido de un estudio.
 *
 * El contexto sale de las columnas propias del estudio. El paciente se resuelve por su
 * identificador actual: si se edita despues, cambian sus datos de perfil, pero no la edad ni
 * los textos clinicos que quedaron guardados con el estudio.
 */
@Service
public class StudyContextService {
  private final StudyRepository studyRepository;
  private final PatientRepository patientRepository;

  public StudyContextService(StudyRepository studyRepository, PatientRepository patientRepository) {
    this.studyRepository = studyRepository;
    this.patientRepository = patientRepository;
  }

  public StudyContextDto get(String caseId) {
    try {
      Study study =
          studyRepository.findStudyByCaseId(caseId).orElseThrow(() -> new StudyNotFoundException(caseId));
      StudyContextDto.PatientRef patient = null;
      if (study.patientId() != null) {
        Patient found = patientRepository.findById(study.patientId()).orElse(null);
        if (found != null) {
          patient =
              new StudyContextDto.PatientRef(
                  found.id(), found.patientReference(), found.firstName(), found.lastName(), found.dateOfBirth());
        }
      }
      boolean hasContext =
          study.ageAtStudy() != null
              || study.clinicalReason() != null
              || study.symptoms() != null
              || study.symptomDuration() != null
              || study.relevantHistory() != null;
      StudyContextDto.ClinicalContext context =
          hasContext
              ? new StudyContextDto.ClinicalContext(
                  study.ageAtStudy(),
                  study.ageProvenance(),
                  study.clinicalReason(),
                  study.symptoms(),
                  study.symptomDuration(),
                  study.relevantHistory(),
                  study.contextVersion())
              : null;
      return new StudyContextDto(study.caseId(), patient, context);
    } catch (IllegalStateException ex) {
      throw new DatabaseUnavailableException(ex);
    }
  }
}
