package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.Patient;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.CreatePatientRequestDto;
import ar.edu.uade.pfi.backend.dto.PatientDetailDto;
import ar.edu.uade.pfi.backend.dto.PatientStudySummaryDto;
import ar.edu.uade.pfi.backend.dto.PatientSummaryDto;
import ar.edu.uade.pfi.backend.dto.StudyPatientAssignmentRequestDto;
import ar.edu.uade.pfi.backend.dto.StudyPatientAssignmentResponseDto;
import ar.edu.uade.pfi.backend.dto.UpdatePatientRequestDto;
import ar.edu.uade.pfi.backend.repository.DuplicatePatientReferenceException;
import ar.edu.uade.pfi.backend.repository.PatientRepository;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import ar.edu.uade.pfi.backend.service.exception.DatabaseUnavailableException;
import ar.edu.uade.pfi.backend.service.exception.PatientDomainException;
import ar.edu.uade.pfi.backend.service.exception.StudyNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PatientService {
  private static final Pattern PATIENT_REFERENCE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  /** Busqueda: codigo o nombre. Letras (con tildes), numeros, espacio, punto, guion y guion bajo. */
  private static final Pattern SEARCH_QUERY = Pattern.compile("^[\\p{L}\\p{N} ._'-]{1,64}$");
  private static final Pattern PERSON_NAME = Pattern.compile("^\\p{L}[\\p{L} .'-]{0,79}$");
  private static final LocalDate EARLIEST_BIRTH_DATE = LocalDate.of(1900, 1, 1);
  private static final Pattern UUID_FORMAT =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final int DEFAULT_LIMIT = 25;
  private static final int MAX_LIMIT = 100;

  private final PatientRepository patientRepository;
  private final StudyRepository studyRepository;
  private final AuditService auditService;
  private final Clock clock;

  @Autowired
  public PatientService(
      PatientRepository patientRepository,
      StudyRepository studyRepository,
      AuditService auditService) {
    this(patientRepository, studyRepository, auditService, Clock.systemUTC());
  }

  PatientService(
      PatientRepository patientRepository,
      StudyRepository studyRepository,
      AuditService auditService,
      Clock clock) {
    this.patientRepository = patientRepository;
    this.studyRepository = studyRepository;
    this.auditService = auditService;
    this.clock = clock;
  }

  public PatientDetailDto create(CreatePatientRequestDto request, String actor, String traceId) {
    String reference = validateReference(request == null ? null : request.patientReference());
    String firstName = validateName(request == null ? null : request.firstName(), "firstName");
    String lastName = validateName(request == null ? null : request.lastName(), "lastName");
    LocalDate dateOfBirth = validateBirthDate(request == null ? null : request.dateOfBirth());
    Instant now = clock.instant();
    Patient patient =
        databaseCall(
            () ->
                patientRepository.save(
                    new Patient(
                        UUID.randomUUID().toString(),
                        reference,
                        firstName,
                        lastName,
                        dateOfBirth,
                        now,
                        now)));
    auditService.record(
        actor,
        "PATIENT_CREATED",
        patient.id(),
        traceId,
        Map.of(
            "patientId",
            patient.id(),
            "changedFieldNames",
            changedFieldNames(reference, firstName, lastName, dateOfBirth)));
    return detail(patient);
  }

  public List<PatientSummaryDto> search(String query, Integer requestedLimit) {
    String normalizedQuery = query == null ? "" : query.trim();
    if (normalizedQuery.length() > 64) {
      throw invalidReference("query supera el largo maximo permitido.");
    }
    if (!normalizedQuery.isEmpty() && !SEARCH_QUERY.matcher(normalizedQuery).matches()) {
      throw invalidReference("query tiene un formato invalido.");
    }
    int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new PatientDomainException(
          HttpStatus.BAD_REQUEST,
          "INVALID_PATIENT_LIMIT",
          "limit debe estar entre 1 y " + MAX_LIMIT + ".");
    }
    return databaseCall(() -> patientRepository.search(normalizedQuery, limit))
        .stream()
        .map(this::summary)
        .toList();
  }

  public PatientDetailDto get(String patientId) {
    String validId = validateUuid(patientId, "patientId");
    return detail(
        databaseCall(() -> patientRepository.findById(validId)).orElseThrow(this::patientNotFound));
  }

  public PatientDetailDto update(
      String patientId, UpdatePatientRequestDto request, String actor, String traceId) {
    String validId = validateUuid(patientId, "patientId");
    if (request == null) {
      throw invalidReference("El cuerpo de la solicitud es obligatorio.");
    }
    Patient current =
        databaseCall(() -> patientRepository.findById(validId)).orElseThrow(this::patientNotFound);
    // Campo ausente (null) = no se toca; en nombre/apellido, cadena vacia = borrar.
    String reference =
        request.patientReference() == null
            ? current.patientReference()
            : validateReference(request.patientReference());
    String firstName =
        request.firstName() == null
            ? current.firstName()
            : validateName(request.firstName(), "firstName");
    String lastName =
        request.lastName() == null
            ? current.lastName()
            : validateName(request.lastName(), "lastName");
    LocalDate dateOfBirth =
        request.dateOfBirth() == null
            ? current.dateOfBirth()
            : validateBirthDate(request.dateOfBirth());
    Patient updated =
        databaseCall(
                () ->
                    patientRepository.updateProfile(
                        validId, reference, firstName, lastName, dateOfBirth, clock.instant()))
            .orElseThrow(this::patientNotFound);
    auditService.record(
        actor,
        "PATIENT_UPDATED",
        updated.id(),
        traceId,
        Map.of(
            "patientId",
            updated.id(),
            "changedFieldNames",
            changedFieldNames(
                request.patientReference(),
                request.firstName(),
                request.lastName(),
                request.dateOfBirth())));
    return detail(updated);
  }

  public List<PatientStudySummaryDto> studies(String patientId) {
    String validId = validateUuid(patientId, "patientId");
    if (databaseCall(() -> patientRepository.findById(validId)).isEmpty()) {
      throw patientNotFound();
    }
    return databaseCall(() -> studyRepository.findStudiesByPatientId(validId)).stream()
        .map(this::studySummary)
        .toList();
  }

  public StudyPatientAssignmentResponseDto assignStudy(
      String caseId, StudyPatientAssignmentRequestDto request, String actor, String traceId) {
    if (request == null || request.patientId() == null || request.patientId().isBlank()) {
      throw new PatientDomainException(
          HttpStatus.BAD_REQUEST, "INVALID_PATIENT_ID", "patientId es obligatorio.");
    }
    String targetPatientId = validateUuid(request.patientId(), "patientId");
    String expectedPatientId =
        request.expectedPatientId() == null
            ? null
            : validateUuid(request.expectedPatientId(), "expectedPatientId");
    AssignmentReason reason = AssignmentReason.parse(request.reason());
    if (databaseCall(() -> patientRepository.findById(targetPatientId)).isEmpty()) {
      throw patientNotFound();
    }

    Study current =
        databaseCall(() -> studyRepository.findStudyByCaseId(caseId))
            .orElseThrow(() -> new StudyNotFoundException(caseId));
    if (!Objects.equals(current.patientId(), expectedPatientId)) {
      throw assignmentConflict();
    }
    if (Objects.equals(current.patientId(), targetPatientId)) {
      return assignmentResponse(current, current.patientId(), reason, false);
    }
    if (current.patientId() == null && reason != AssignmentReason.INITIAL_ASSIGNMENT) {
      throw invalidReason("La primera asociacion requiere INITIAL_ASSIGNMENT.");
    }
    if (current.patientId() != null && reason != AssignmentReason.CORRECTION) {
      throw invalidReason("La reasignacion requiere CORRECTION.");
    }

    Study updated =
        databaseCall(
                () ->
                    studyRepository.updatePatientIfExpected(
                        caseId, targetPatientId, expectedPatientId))
            .orElseThrow(
                () -> {
                  if (databaseCall(() -> studyRepository.findStudyByCaseId(caseId)).isEmpty()) {
                    return new StudyNotFoundException(caseId);
                  }
                  return assignmentConflict();
                });
    String action =
        current.patientId() == null ? "STUDY_PATIENT_ASSIGNED" : "STUDY_PATIENT_REASSIGNED";
    auditService.record(
        actor,
        action,
        updated.id(),
        traceId,
        associationMetadata(updated, current.patientId(), targetPatientId, reason));
    return assignmentResponse(updated, current.patientId(), reason, true);
  }

  private Map<String, Object> associationMetadata(
      Study study, String fromPatientId, String toPatientId, AssignmentReason reason) {
    java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>();
    metadata.put("studyId", study.id());
    metadata.put("caseId", study.caseId());
    metadata.put("fromPatientId", fromPatientId == null ? "" : fromPatientId);
    metadata.put("toPatientId", toPatientId);
    metadata.put("reasonCode", reason.name());
    return metadata;
  }

  private StudyPatientAssignmentResponseDto assignmentResponse(
      Study study, String previousPatientId, AssignmentReason reason, boolean changed) {
    return new StudyPatientAssignmentResponseDto(
        study.id(), study.caseId(), study.patientId(), previousPatientId, reason.name(), changed);
  }

  private PatientSummaryDto summary(Patient patient) {
    return new PatientSummaryDto(
        patient.id(),
        patient.patientReference(),
        patient.firstName(),
        patient.lastName(),
        patient.dateOfBirth(),
        patient.createdAt(),
        patient.updatedAt());
  }

  private PatientDetailDto detail(Patient patient) {
    return new PatientDetailDto(
        patient.id(),
        patient.patientReference(),
        patient.firstName(),
        patient.lastName(),
        patient.dateOfBirth(),
        patient.createdAt(),
        patient.updatedAt());
  }

  private PatientStudySummaryDto studySummary(Study study) {
    return new PatientStudySummaryDto(
        study.id(),
        study.caseId(),
        study.studyDate(),
        study.modality(),
        study.description(),
        study.reviewPriority(),
        study.status());
  }

  private String validateReference(String value) {
    String reference = value == null ? "" : value.trim();
    if (!PATIENT_REFERENCE.matcher(reference).matches()) {
      throw invalidReference(
          "patientReference debe tener entre 1 y 64 caracteres alfanumericos, punto, guion o guion bajo.");
    }
    return reference;
  }

  /** Nombre o apellido opcional: vacio = sin dato; si hay texto, solo letras y separadores. */
  private String validateName(String value, String field) {
    if (value == null || value.isBlank()) return null;
    String name = value.trim();
    if (!PERSON_NAME.matcher(name).matches()) {
      throw new PatientDomainException(
          HttpStatus.BAD_REQUEST,
          "INVALID_PATIENT_NAME",
          field + " debe tener hasta 80 letras, espacios, punto, apostrofe o guion.");
    }
    return name;
  }

  private LocalDate validateBirthDate(LocalDate value) {
    if (value == null) return null;
    if (value.isBefore(EARLIEST_BIRTH_DATE) || value.isAfter(LocalDate.now(clock.withZone(ZoneOffset.UTC)))) {
      throw new PatientDomainException(
          HttpStatus.BAD_REQUEST,
          "INVALID_PATIENT_BIRTH_DATE",
          "dateOfBirth debe estar entre 1900-01-01 y hoy.");
    }
    return value;
  }

  private List<String> changedFieldNames(
      Object reference, Object firstName, Object lastName, Object dateOfBirth) {
    List<String> names = new ArrayList<>();
    if (reference != null) names.add("patientReference");
    if (firstName != null) names.add("firstName");
    if (lastName != null) names.add("lastName");
    if (dateOfBirth != null) names.add("dateOfBirth");
    return names;
  }

  private String validateUuid(String value, String field) {
    String candidate = value == null ? "" : value.trim();
    if (!UUID_FORMAT.matcher(candidate).matches()) {
      throw new PatientDomainException(
          HttpStatus.BAD_REQUEST, "INVALID_PATIENT_ID", field + " debe ser un UUID valido.");
    }
    try {
      return UUID.fromString(candidate).toString();
    } catch (IllegalArgumentException ex) {
      throw new PatientDomainException(
          HttpStatus.BAD_REQUEST, "INVALID_PATIENT_ID", field + " debe ser un UUID valido.");
    }
  }

  private PatientDomainException invalidReference(String message) {
    return new PatientDomainException(HttpStatus.BAD_REQUEST, "INVALID_PATIENT_REFERENCE", message);
  }

  private PatientDomainException patientNotFound() {
    return new PatientDomainException(
        HttpStatus.NOT_FOUND, "PATIENT_NOT_FOUND", "Paciente no encontrado.");
  }

  private PatientDomainException assignmentConflict() {
    return new PatientDomainException(
        HttpStatus.CONFLICT,
        "PATIENT_ASSIGNMENT_CONFLICT",
        "La asociacion del estudio cambio; vuelva a cargar el estado actual.");
  }

  private PatientDomainException invalidReason(String message) {
    return new PatientDomainException(HttpStatus.BAD_REQUEST, "INVALID_REASON_CODE", message);
  }

  private <T> T databaseCall(DatabaseSupplier<T> supplier) {
    try {
      return supplier.get();
    } catch (DuplicatePatientReferenceException ex) {
      throw new PatientDomainException(
          HttpStatus.CONFLICT,
          "DUPLICATE_PATIENT_REFERENCE",
          "Ya existe un paciente con esa referencia.");
    } catch (PatientDomainException | StudyNotFoundException ex) {
      throw ex;
    } catch (IllegalStateException ex) {
      throw new DatabaseUnavailableException(ex);
    }
  }

  private enum AssignmentReason {
    INITIAL_ASSIGNMENT,
    CORRECTION;

    private static AssignmentReason parse(String value) {
      try {
        return valueOf(value == null ? "" : value.trim().toUpperCase());
      } catch (IllegalArgumentException ex) {
        throw new PatientDomainException(
            HttpStatus.BAD_REQUEST,
            "INVALID_REASON_CODE",
            "reason debe ser INITIAL_ASSIGNMENT o CORRECTION.");
      }
    }
  }

  @FunctionalInterface
  private interface DatabaseSupplier<T> {
    T get();
  }
}
