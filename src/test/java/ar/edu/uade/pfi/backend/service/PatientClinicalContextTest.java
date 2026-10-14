package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.CreatePatientRequestDto;
import ar.edu.uade.pfi.backend.dto.PatientDetailDto;
import ar.edu.uade.pfi.backend.dto.PatientSummaryDto;
import ar.edu.uade.pfi.backend.dto.StudyContextDto;
import ar.edu.uade.pfi.backend.dto.StudyMetadataDto;
import ar.edu.uade.pfi.backend.dto.UpdatePatientRequestDto;
import ar.edu.uade.pfi.backend.repository.InMemoryPatientRepository;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.exception.PatientDomainException;
import ar.edu.uade.pfi.backend.service.exception.StudyMetadataException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pacientes con perfil, edad al estudio y contexto clinico propio de cada estudio.
 *
 * Comportamiento sobre los repositorios en memoria (los de Postgres se prueban con
 * Testcontainers cuando hay Docker). Cubre lo que importa clinicamente: la edad no se inventa ni
 * se resuelve en silencio, y editar al paciente despues no reescribe el contexto historico.
 */
class PatientClinicalContextTest {
  private InMemoryPatientRepository patients;
  private InMemoryStudyRepository studies;
  private PatientService patientService;
  private StudyRunService studyService;
  private StudyContextService contextService;

  @BeforeEach
  void setUp() {
    patients = new InMemoryPatientRepository();
    studies = new InMemoryStudyRepository();
    patientService = new PatientService(patients, studies, new AuditService(studies));
    studyService = new StudyRunService(studies, patients);
    contextService = new StudyContextService(studies, patients);
  }

  private PatientDetailDto createPatient(String reference, String first, String last, LocalDate dob) {
    return patientService.create(new CreatePatientRequestDto(reference, first, last, dob), "tester", "trace");
  }

  private StudyMetadataDto metadata(
      String patientId, LocalDate studyDate, Integer age, String provenance, String reason) {
    return new StudyMetadataDto(
        null, studyDate, null, null, null, patientId, age, provenance, reason, "dolor lumbar", "3 meses", "sin cirugias");
  }

  @Test
  void createsPatientWithProfileAndFindsItByCodeOrName() {
    PatientDetailDto created = createPatient("PAC-001", "María José", "Pérez-Gómez", LocalDate.parse("1980-05-20"));
    assertEquals("PAC-001", created.patientReference());
    assertEquals("María José", created.firstName());
    assertEquals(LocalDate.parse("1980-05-20"), created.dateOfBirth());

    List<PatientSummaryDto> byCode = patientService.search("pac-0", null);
    List<PatientSummaryDto> byName = patientService.search("josé", null);
    List<PatientSummaryDto> byFullName = patientService.search("maría josé pérez", null);
    assertEquals(1, byCode.size());
    assertEquals(1, byName.size());
    assertEquals(1, byFullName.size());
    assertEquals(created.id(), byName.get(0).id());
    assertEquals(0, patientService.search("zzz", null).size());
  }

  @Test
  void profileIsOptionalAndLegacyPatientsStayValid() {
    PatientDetailDto onlyCode = createPatient("PAC-LEGACY", null, null, null);
    assertNull(onlyCode.firstName());
    assertNull(onlyCode.dateOfBirth());
    assertEquals(1, patientService.search("", null).size());
  }

  @Test
  void rejectsInvalidNamesAndBirthDates() {
    assertThrows(PatientDomainException.class, () -> createPatient("PAC-X1", "R2D2", null, null));
    assertThrows(PatientDomainException.class, () -> createPatient("PAC-X2", null, null, LocalDate.parse("1850-01-01")));
    assertThrows(PatientDomainException.class, () -> createPatient("PAC-X3", null, null, LocalDate.now(ZoneOffset.UTC).plusDays(1)));
    assertThrows(PatientDomainException.class, () -> patientService.search("bad<query>", null));
  }

  @Test
  void updateIsPartialAndKeepsWhatWasNotSent() {
    PatientDetailDto created = createPatient("PAC-002", "Ana", "Gil", LocalDate.parse("1990-01-01"));
    PatientDetailDto renamed =
        patientService.update(created.id(), new UpdatePatientRequestDto(null, "Anabel", null, null), "tester", "trace");
    assertEquals("PAC-002", renamed.patientReference());
    assertEquals("Anabel", renamed.firstName());
    assertEquals("Gil", renamed.lastName());
    assertEquals(LocalDate.parse("1990-01-01"), renamed.dateOfBirth());
    PatientDetailDto cleared =
        patientService.update(created.id(), new UpdatePatientRequestDto(null, "", null, null), "tester", "trace");
    assertNull(cleared.firstName());
  }

  @Test
  void calculatedAgeUsesBirthDateAndStudyDateAndBirthdayBoundary() {
    PatientDetailDto patient = createPatient("PAC-AGE", "Luis", "Rey", LocalDate.parse("1980-06-15"));
    // un dia antes del cumpleanos: 45, no 46
    Study before =
        studyService.upsertStudyMetadata(
            "CASE-AGE-1", "created", metadata(patient.id(), LocalDate.parse("2026-06-14"), 45, "calculated", "control"));
    assertEquals(45, before.ageAtStudy());
    assertEquals("calculated", before.ageProvenance());
    Study onBirthday =
        studyService.upsertStudyMetadata(
            "CASE-AGE-2", "created", metadata(patient.id(), LocalDate.parse("2026-06-15"), 46, "calculated", null));
    assertEquals(46, onBirthday.ageAtStudy());
  }

  @Test
  void aCalculatedAgeThatDoesNotMatchTheDatesIsRejectedNotSilentlyFixed() {
    PatientDetailDto patient = createPatient("PAC-MIS", null, null, LocalDate.parse("1980-06-15"));
    StudyMetadataException mismatch =
        assertThrows(
            StudyMetadataException.class,
            () -> studyService.upsertStudyMetadata(
                "CASE-MIS", "created", metadata(patient.id(), LocalDate.parse("2026-06-14"), 46, "calculated", null)));
    assertEquals("AGE_CALCULATION_MISMATCH", mismatch.code());
  }

  @Test
  void cannotCalculateWithoutBirthDateOrStudyDate() {
    PatientDetailDto noDob = createPatient("PAC-NODOB", "Sin", "Fecha", null);
    assertEquals(
        "AGE_NOT_CALCULABLE",
        assertThrows(
                StudyMetadataException.class,
                () -> studyService.upsertStudyMetadata(
                    "CASE-NODOB", "created", metadata(noDob.id(), LocalDate.parse("2026-01-01"), 40, "calculated", null)))
            .code());
    PatientDetailDto withDob = createPatient("PAC-NODATE", null, null, LocalDate.parse("1980-01-01"));
    assertEquals(
        "AGE_NOT_CALCULABLE",
        assertThrows(
                StudyMetadataException.class,
                () -> studyService.upsertStudyMetadata(
                    "CASE-NODATE", "created", metadata(withDob.id(), null, 40, "calculated", null)))
            .code());
  }

  @Test
  void reportedAgeIsKeptAsDeclaredEvenWhenItDiffersFromTheCalculatedOne() {
    PatientDetailDto patient = createPatient("PAC-REP", null, null, LocalDate.parse("1980-01-01"));
    StudyRunService.PreparedStudyMetadata prepared =
        studyService.prepareStudyMetadata("CASE-REP", metadata(patient.id(), LocalDate.parse("2026-02-01"), 50, "reported", null));
    assertTrue(prepared.ageDiscrepancy(), "la diferencia queda registrada, no se resuelve en silencio");
    Study saved =
        studyService.upsertStudyMetadata(
            "CASE-REP", "created", metadata(patient.id(), LocalDate.parse("2026-02-01"), 50, "reported", null));
    assertEquals(50, saved.ageAtStudy());
    assertEquals("reported", saved.ageProvenance());
  }

  @Test
  void reportedAgeWithoutAPatientIsAllowedButAgeNeedsItsProvenance() {
    Study declared =
        studyService.upsertStudyMetadata("CASE-DECL", "created", metadata(null, null, 62, "reported", "dolor"));
    assertEquals(62, declared.ageAtStudy());
    assertNull(declared.patientId());
    assertEquals(
        "AGE_PROVENANCE_REQUIRED",
        assertThrows(
                StudyMetadataException.class,
                () -> studyService.upsertStudyMetadata("CASE-NOPROV", "created", metadata(null, null, 62, null, null)))
            .code());
    assertEquals(
        "INVALID_AGE_AT_STUDY",
        assertThrows(
                StudyMetadataException.class,
                () -> studyService.upsertStudyMetadata("CASE-BIG", "created", metadata(null, null, 131, "reported", null)))
            .code());
  }

  @Test
  void studyWithoutPatientOrContextStillWorks() {
    Study plain = studyService.upsertStudyMetadata("CASE-PLAIN", "created", new StudyMetadataDto("SPIDER-101", null, null, null, null));
    assertNull(plain.patientId());
    assertNull(plain.ageAtStudy());
    StudyContextDto context = contextService.get("CASE-PLAIN");
    assertNull(context.patient());
    assertNull(context.clinicalContext());
    assertEquals("pfi.study-context.v1", plain.contextVersion());
  }

  @Test
  void linkingAPatientDerivesSubjectRefForHistoricalCompatibility() {
    PatientDetailDto patient = createPatient("PAC-LINK", "Eva", "Sol", null);
    Study linked = studyService.upsertStudyMetadata("CASE-LINK", "created", metadata(patient.id(), null, null, null, null));
    assertEquals(patient.id(), linked.patientId());
    assertEquals("PAC-LINK", linked.subjectRef());
    assertEquals(1, studies.findStudiesBySubjectRef("PAC-LINK").size());
  }

  @Test
  void editingThePatientLaterDoesNotRewriteTheStudyContext() {
    PatientDetailDto patient = createPatient("PAC-HIST", "Raul", "Mar", LocalDate.parse("1970-03-10"));
    studyService.upsertStudyMetadata(
        "CASE-HIST", "created", metadata(patient.id(), LocalDate.parse("2026-03-09"), 55, "calculated", "lumbalgia"));
    // se corrige la fecha de nacimiento y el nombre del paciente
    patientService.update(
        patient.id(), new UpdatePatientRequestDto(null, "Raúl", null, LocalDate.parse("1990-01-01")), "tester", "trace");

    StudyContextDto context = contextService.get("CASE-HIST");
    assertEquals("Raúl", context.patient().firstName(), "el perfil del paciente si se actualiza");
    assertEquals(55, context.clinicalContext().ageAtStudy(), "la edad del estudio quedo guardada");
    assertEquals("calculated", context.clinicalContext().ageProvenance());
    assertEquals("lumbalgia", context.clinicalContext().clinicalReason());
    assertEquals("dolor lumbar", context.clinicalContext().symptoms());
    assertEquals("3 meses", context.clinicalContext().symptomDuration());
    assertEquals("sin cirugias", context.clinicalContext().relevantHistory());
  }

  @Test
  void saveWithoutContextDoesNotWipeExistingContextAndNewContextReplacesIt() {
    Study first =
        studyService.upsertStudyMetadata("CASE-KEEP", "created", metadata(null, null, 40, "reported", "motivo A"));
    Study again = studyService.upsertStudyMetadata("CASE-KEEP", "created", new StudyMetadataDto("SPIDER-202", null, null, null, null));
    assertEquals(first.id(), again.id());
    assertEquals(40, again.ageAtStudy());
    assertEquals("motivo A", again.clinicalReason());
    Study replaced =
        studyService.upsertStudyMetadata("CASE-KEEP", "created", metadata(null, null, 41, "reported", "motivo B"));
    assertEquals(41, replaced.ageAtStudy());
    assertEquals("motivo B", replaced.clinicalReason());
  }

  @Test
  void unknownPatientAndConflictingPatientAreRejected() {
    assertEquals(
        "PATIENT_NOT_FOUND",
        assertThrows(
                StudyMetadataException.class,
                () -> studyService.upsertStudyMetadata(
                    "CASE-GHOST", "created", metadata("00000000-0000-4000-8000-000000000001", null, null, null, null)))
            .code());
    PatientDetailDto a = createPatient("PAC-A", null, null, null);
    PatientDetailDto b = createPatient("PAC-B", null, null, null);
    studyService.upsertStudyMetadata("CASE-TWO", "created", metadata(a.id(), null, null, null, null));
    assertEquals(
        "STUDY_PATIENT_CONFLICT",
        assertThrows(
                StudyMetadataException.class,
                () -> studyService.upsertStudyMetadata("CASE-TWO", "created", metadata(b.id(), null, null, null, null)))
            .code());
    assertFalse(patients.search("", 10).isEmpty());
    assertNotNull(contextService.get("CASE-TWO").patient());
  }

  @Test
  void overlongClinicalTextIsRejected() {
    assertEquals(
        "INVALID_CLINICAL_CONTEXT",
        assertThrows(
                StudyMetadataException.class,
                () -> studyService.upsertStudyMetadata(
                    "CASE-LONG", "created", metadata(null, null, null, null, "x".repeat(1001))))
            .code());
  }
}
