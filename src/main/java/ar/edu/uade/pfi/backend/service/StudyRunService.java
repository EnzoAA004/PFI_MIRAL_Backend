package ar.edu.uade.pfi.backend.service;
import ar.edu.uade.pfi.backend.service.exception.StudyMetadataException;
import ar.edu.uade.pfi.backend.service.exception.DatabaseUnavailableException;

import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.InputSequence;
import ar.edu.uade.pfi.backend.domain.Patient;
import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.StudyMetadataDto;
import ar.edu.uade.pfi.backend.repository.PatientRepository;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class StudyRunService {
    private static final Logger log = LoggerFactory.getLogger(StudyRunService.class);
    private static final Pattern SUBJECT_REF_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{3,64}$");

    private final StudyRepository repository;
    private final PatientRepository patientRepository;
    private final Clock clock;

    @Autowired
    public StudyRunService(StudyRepository repository, PatientRepository patientRepository) {
        this(repository, patientRepository, Clock.systemUTC());
    }

    public StudyRunService(StudyRepository repository) {
        this(repository, null, Clock.systemUTC());
    }

    StudyRunService(StudyRepository repository, Clock clock) {
        this(repository, null, clock);
    }

    StudyRunService(StudyRepository repository, PatientRepository patientRepository, Clock clock) {
        this.repository = repository;
        this.patientRepository = patientRepository;
        this.clock = clock;
    }

    public Study createStudy(String caseId, String status) {
        Instant now = clock.instant();
        return repository.saveStudy(new Study(
            UUID.randomUUID().toString(),
            caseId,
            status,
            now,
            now
        ));
    }

    public PreparedStudyMetadata prepareStudyMetadata(String caseId, StudyMetadataDto metadata) {
        String normalizedCaseId = requireCaseId(caseId);
        StudyMetadataDto normalized = normalizeMetadata(metadata);
        Study existing = findStudyForMetadata(normalizedCaseId).orElse(null);
        boolean ageDiscrepancy = false;
        if (normalized != null) {
            Patient patient = resolvePatient(normalized.patientId());
            normalized = withDerivedSubjectRef(normalized, patient);
            AgeResolution age = resolveAge(
                normalized.ageAtStudy(),
                normalized.ageProvenance(),
                normalized.studyDate() != null || existing == null ? normalized.studyDate() : existing.studyDate(),
                patient == null ? null : patient.dateOfBirth()
            );
            ageDiscrepancy = age.discrepancy();
            normalized = withAge(normalized, age);
            if (existing != null
                && existing.patientId() != null
                && normalized.patientId() != null
                && !existing.patientId().equals(normalized.patientId())) {
                throw new StudyMetadataException(
                    HttpStatus.CONFLICT,
                    "STUDY_PATIENT_CONFLICT",
                    "El estudio ya esta asociado a otro paciente; la reasignacion se hace con la correccion explicita."
                );
            }
        }
        if (existing != null
            && normalized != null
            && normalized.subjectRef() != null
            && existing.subjectRef() != null
            && !existing.subjectRef().equalsIgnoreCase(normalized.subjectRef())) {
            throw new StudyMetadataException(
                HttpStatus.CONFLICT,
                "SUBJECT_REFERENCE_CONFLICT",
                "El estudio ya tiene una referencia de-identificada distinta."
            );
        }
        return new PreparedStudyMetadata(normalizedCaseId, normalized, existing, ageDiscrepancy);
    }

    public Study upsertStudyMetadata(String caseId, StudyMetadataDto metadata) {
        return upsertStudyMetadata(caseId, "created", metadata);
    }

    public Study upsertStudyMetadata(String caseId, String status, StudyMetadataDto metadata) {
        PreparedStudyMetadata prepared = prepareStudyMetadata(caseId, metadata);
        String normalizedCaseId = prepared.caseId();
        StudyMetadataDto normalized = prepared.metadata();
        Optional<Study> existing = Optional.ofNullable(prepared.existingStudy());
        if (existing.isEmpty()) {
            Instant now = clock.instant();
            Study created = saveStudyForMetadata(new Study(
                UUID.randomUUID().toString(),
                normalizedCaseId,
                status,
                normalized == null ? null : normalized.patientId(),
                normalized == null ? null : normalized.subjectRef(),
                normalized == null ? null : normalized.studyDate(),
                normalized == null ? null : normalized.modality(),
                normalized == null ? null : normalized.description(),
                normalized == null ? "medium" : valueOrDefault(normalized.reviewPriority(), "medium"),
                normalized == null ? null : normalized.ageAtStudy(),
                normalized == null ? null : normalized.ageProvenance(),
                normalized == null ? null : normalized.clinicalReason(),
                normalized == null ? null : normalized.symptoms(),
                normalized == null ? null : normalized.symptomDuration(),
                normalized == null ? null : normalized.relevantHistory(),
                Study.CONTEXT_VERSION,
                now,
                now
            ));
            audit("study.metadata.created", created, normalized, contextAuditExtra(created, prepared.ageDiscrepancy(), Map.of("metadataPresent", normalized != null)));
            if (created.subjectRef() != null) audit("study.subject_ref.assigned", created, normalized, Map.of("subjectRef", created.subjectRef()));
            return created;
        }

        Study current = existing.get();
        if (normalized == null) return current;

        String subjectRef = firstNonBlank(current.subjectRef(), normalized.subjectRef());
        LocalDate studyDate = normalized.studyDate() == null ? current.studyDate() : normalized.studyDate();
        String modality = firstNonBlank(normalized.modality(), current.modality());
        String description = firstNonBlank(normalized.description(), current.description());
        String reviewPriority = firstNonBlank(normalized.reviewPriority(), current.reviewPriority());
        String patientId = current.patientId() != null ? current.patientId() : normalized.patientId();
        // El contexto clinico es del estudio: un dato nuevo lo reemplaza, uno ausente lo conserva.
        boolean newAge = normalized.ageAtStudy() != null;
        Integer ageAtStudy = newAge ? normalized.ageAtStudy() : current.ageAtStudy();
        String ageProvenance = newAge ? normalized.ageProvenance() : current.ageProvenance();
        String clinicalReason = firstNonBlank(normalized.clinicalReason(), current.clinicalReason());
        String symptoms = firstNonBlank(normalized.symptoms(), current.symptoms());
        String symptomDuration = firstNonBlank(normalized.symptomDuration(), current.symptomDuration());
        String relevantHistory = firstNonBlank(normalized.relevantHistory(), current.relevantHistory());
        boolean changed =
            !same(current.subjectRef(), subjectRef)
                || !same(current.patientId(), patientId)
                || !same(current.studyDate(), studyDate)
                || !same(current.modality(), modality)
                || !same(current.description(), description)
                || !same(current.reviewPriority(), reviewPriority)
                || !same(current.ageAtStudy(), ageAtStudy)
                || !same(current.ageProvenance(), ageProvenance)
                || !same(current.clinicalReason(), clinicalReason)
                || !same(current.symptoms(), symptoms)
                || !same(current.symptomDuration(), symptomDuration)
                || !same(current.relevantHistory(), relevantHistory);
        if (!changed) return current;

        Study updated = saveStudyForMetadata(new Study(
            current.id(),
            current.caseId(),
            current.status(),
            patientId,
            subjectRef,
            studyDate,
            modality,
            description,
            reviewPriority,
            ageAtStudy,
            ageProvenance,
            clinicalReason,
            symptoms,
            symptomDuration,
            relevantHistory,
            current.contextVersion(),
            current.createdAt(),
            clock.instant()
        ));
        audit("study.metadata.updated", updated, normalized, contextAuditExtra(updated, prepared.ageDiscrepancy(), Map.of("metadataPresent", true)));
        if (current.subjectRef() == null && updated.subjectRef() != null) {
            audit("study.subject_ref.assigned", updated, normalized, Map.of("subjectRef", updated.subjectRef()));
        }
        return updated;
    }

    public InputResource createInput(Study study, String plane, String inputId, String format, long size) {
        return createInput(study, plane, inputId, format, size, "", "", 0, false, false, true);
    }

    /**
     * Registers one series of the study, analysed or not.
     *
     * <p>The catalogue holds every series the archive carried, because that is what the
     * doctor reads. {@code analyzable} is what separates the two the models run on from
     * the rest; it is not derivable from the plane, since a sagittal T1 has a plane the
     * registry accepts and still is not what was inferred.
     */
    public InputResource createInput(
        Study study,
        String plane,
        String inputId,
        String format,
        long size,
        String description,
        String weighting,
        int sliceCount,
        boolean multiplanar,
        boolean derived,
        boolean analyzable
    ) {
        return createInput(study, plane, inputId, format, size, description, weighting, sliceCount, multiplanar, derived, analyzable, "", "");
    }

    /**
     * Igual que la anterior, con la procedencia de la ponderación ({@code user_declared} o
     * {@code auto_detected}) y la descripción de la secuencia «otra». Ver {@link InputSequence}.
     */
    public InputResource createInput(
        Study study,
        String plane,
        String inputId,
        String format,
        long size,
        String description,
        String weighting,
        int sliceCount,
        boolean multiplanar,
        boolean derived,
        boolean analyzable,
        String sequenceSource,
        String sequenceDescription
    ) {
        return repository.saveInput(new InputResource(
            UUID.randomUUID().toString(),
            study.id(),
            plane,
            inputId,
            format,
            size,
            description,
            weighting,
            sliceCount,
            multiplanar,
            derived,
            analyzable,
            sequenceSource,
            sequenceDescription,
            clock.instant()
        ));
    }

    public StudyRun createRun(
        Study study,
        String multiplanarRunId,
        String traceId,
        String requestedInferenceMode,
        String effectiveInferenceMode,
        String sagittalModelKey,
        String axialModelKey,
        String sagittalRunId,
        String axialRunId,
        Map<String, Object> assets,
        String status
    ) {
        return createRun(
            study,
            multiplanarRunId,
            traceId,
            requestedInferenceMode,
            effectiveInferenceMode,
            sagittalModelKey,
            axialModelKey,
            "",
            "",
            sagittalRunId,
            axialRunId,
            assets,
            Map.of(),
            List.of(),
            status,
            "pending",
            "",
            null,
            ""
        );
    }

    public StudyRun createRun(
        Study study,
        String multiplanarRunId,
        String traceId,
        String requestedInferenceMode,
        String effectiveInferenceMode,
        String sagittalModelKey,
        String axialModelKey,
        String sagittalArtifactHash,
        String axialArtifactHash,
        String sagittalRunId,
        String axialRunId,
        Map<String, Object> assets,
        Map<String, Object> metricsSnapshot,
        List<RunArtifact> artifacts,
        String status,
        String reviewStatus,
        String reviewer,
        Instant reviewedAt,
        String comments
    ) {
        return createRunWithId(
            UUID.randomUUID().toString(),
            study,
            multiplanarRunId,
            traceId,
            requestedInferenceMode,
            effectiveInferenceMode,
            sagittalModelKey,
            axialModelKey,
            sagittalArtifactHash,
            axialArtifactHash,
            sagittalRunId,
            axialRunId,
            assets,
            metricsSnapshot,
            artifacts,
            status,
            reviewStatus,
            reviewer,
            reviewedAt,
            comments
        );
    }

    public StudyRun createRunWithId(
        String id,
        Study study,
        String multiplanarRunId,
        String traceId,
        String requestedInferenceMode,
        String effectiveInferenceMode,
        String sagittalModelKey,
        String axialModelKey,
        String sagittalArtifactHash,
        String axialArtifactHash,
        String sagittalRunId,
        String axialRunId,
        Map<String, Object> assets,
        Map<String, Object> metricsSnapshot,
        List<RunArtifact> artifacts,
        String status,
        String reviewStatus,
        String reviewer,
        Instant reviewedAt,
        String comments
    ) {
        Instant now = clock.instant();
        return repository.saveRun(new StudyRun(
            id,
            study.id(),
            multiplanarRunId,
            traceId,
            requestedInferenceMode,
            effectiveInferenceMode,
            sagittalModelKey,
            axialModelKey,
            sagittalArtifactHash,
            axialArtifactHash,
            sagittalRunId,
            axialRunId,
            assets,
            metricsSnapshot,
            artifacts,
            status,
            reviewStatus,
            reviewer,
            reviewedAt,
            comments,
            now,
            now
        ));
    }

    public Optional<Study> findStudyByCaseId(String caseId) {
        try {
            return repository.findStudyByCaseId(caseId);
        } catch (IllegalStateException ex) {
            throw new DatabaseUnavailableException(ex);
        }
    }

    public List<InputResource> findInputs(Study study) {
        return repository.findInputsByStudyId(study.id());
    }

    public Optional<StudyRun> findRunByMultiplanarRunId(String multiplanarRunId) {
        return repository.findRunByMultiplanarRunId(multiplanarRunId);
    }

    public Optional<StudyRun> findRunByTraceId(String traceId) {
        return repository.findRunByTraceId(traceId);
    }

    public StudyRun updateRunMetricsSnapshot(String multiplanarRunId, Map<String, Object> metricsSnapshot) {
        return repository.updateRunMetricsSnapshot(multiplanarRunId, metricsSnapshot);
    }

    private StudyMetadataDto normalizeMetadata(StudyMetadataDto metadata) {
        if (metadata == null) return null;
        return new StudyMetadataDto(
            normalizeSubjectRef(metadata.subjectRef()),
            metadata.studyDate(),
            trimToNull(metadata.modality()),
            trimToNull(metadata.description()),
            normalizePriority(metadata.reviewPriority()),
            normalizePatientId(metadata.patientId()),
            metadata.ageAtStudy(),
            normalizeProvenance(metadata.ageProvenance()),
            boundedText(metadata.clinicalReason(), "clinicalReason", 1000),
            boundedText(metadata.symptoms(), "symptoms", 1000),
            boundedText(metadata.symptomDuration(), "symptomDuration", 120),
            boundedText(metadata.relevantHistory(), "relevantHistory", 2000)
        );
    }

    private String normalizePatientId(String patientId) {
        String value = trimToNull(patientId);
        if (value == null) return null;
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException ex) {
            throw new StudyMetadataException(HttpStatus.BAD_REQUEST, "INVALID_PATIENT_ID", "patientId debe ser un UUID valido.");
        }
    }

    private String normalizeProvenance(String provenance) {
        String value = trimToNull(provenance);
        if (value == null) return null;
        String normalized = value.toLowerCase();
        if (!normalized.equals(Study.AGE_CALCULATED) && !normalized.equals(Study.AGE_REPORTED)) {
            throw new StudyMetadataException(
                HttpStatus.BAD_REQUEST,
                "INVALID_AGE_PROVENANCE",
                "ageProvenance debe ser calculated o reported."
            );
        }
        return normalized;
    }

    private String boundedText(String value, String field, int max) {
        String text = trimToNull(value);
        if (text != null && text.length() > max) {
            throw new StudyMetadataException(
                HttpStatus.BAD_REQUEST,
                "INVALID_CLINICAL_CONTEXT",
                field + " supera el maximo de " + max + " caracteres."
            );
        }
        return text;
    }

    private Patient resolvePatient(String patientId) {
        if (patientId == null) return null;
        if (patientRepository == null) {
            throw new StudyMetadataException(
                HttpStatus.BAD_REQUEST, "PATIENT_NOT_FOUND", "No hay repositorio de pacientes disponible."
            );
        }
        try {
            return patientRepository.findById(patientId).orElseThrow(() ->
                new StudyMetadataException(HttpStatus.BAD_REQUEST, "PATIENT_NOT_FOUND", "El paciente indicado no existe.")
            );
        } catch (IllegalStateException ex) {
            throw new DatabaseUnavailableException(ex);
        }
    }

    /** Un paciente asociado aporta su codigo como subjectRef (compatibilidad con el historial por referencia). */
    private StudyMetadataDto withDerivedSubjectRef(StudyMetadataDto metadata, Patient patient) {
        if (patient == null) return metadata;
        String reference = patient.patientReference();
        if (metadata.subjectRef() != null && !metadata.subjectRef().equalsIgnoreCase(reference)) {
            throw new StudyMetadataException(
                HttpStatus.CONFLICT,
                "SUBJECT_REFERENCE_PATIENT_MISMATCH",
                "subjectRef no coincide con el codigo del paciente asociado."
            );
        }
        String subjectRef = metadata.subjectRef() != null
            ? metadata.subjectRef()
            : (SUBJECT_REF_PATTERN.matcher(reference).matches() && !reference.contains("..") ? reference : null);
        return new StudyMetadataDto(
            subjectRef, metadata.studyDate(), metadata.modality(), metadata.description(), metadata.reviewPriority(),
            metadata.patientId(), metadata.ageAtStudy(), metadata.ageProvenance(), metadata.clinicalReason(),
            metadata.symptoms(), metadata.symptomDuration(), metadata.relevantHistory()
        );
    }

    private StudyMetadataDto withAge(StudyMetadataDto metadata, AgeResolution age) {
        return new StudyMetadataDto(
            metadata.subjectRef(), metadata.studyDate(), metadata.modality(), metadata.description(), metadata.reviewPriority(),
            metadata.patientId(), age.value(), age.provenance(), metadata.clinicalReason(),
            metadata.symptoms(), metadata.symptomDuration(), metadata.relevantHistory()
        );
    }

    /**
     * Resuelve la edad al estudio sin inventar ni resolver en silencio:
     * - "calculated" exige fecha de nacimiento y fecha de estudio, y el numero tiene que ser el que
     *   salga de esas fechas (si no, se rechaza en vez de elegir uno);
     * - "reported" se guarda tal cual, y si las fechas permiten calcularla y no coincide queda
     *   marcada como discrepancia (la declarada se conserva, con su procedencia).
     */
    static AgeResolution resolveAge(Integer age, String provenance, LocalDate studyDate, LocalDate dateOfBirth) {
        if (age == null && provenance == null) return new AgeResolution(null, null, false);
        if (age == null) {
            throw new StudyMetadataException(HttpStatus.BAD_REQUEST, "INVALID_AGE_CONTEXT", "ageProvenance requiere ageAtStudy.");
        }
        if (age < 0 || age > 130) {
            throw new StudyMetadataException(HttpStatus.BAD_REQUEST, "INVALID_AGE_AT_STUDY", "ageAtStudy debe estar entre 0 y 130.");
        }
        if (provenance == null) {
            throw new StudyMetadataException(
                HttpStatus.BAD_REQUEST, "AGE_PROVENANCE_REQUIRED", "ageProvenance es obligatorio cuando se informa la edad."
            );
        }
        Integer calculated = null;
        if (dateOfBirth != null && studyDate != null) {
            if (studyDate.isBefore(dateOfBirth)) {
                throw new StudyMetadataException(
                    HttpStatus.BAD_REQUEST, "INVALID_STUDY_DATE", "La fecha del estudio es anterior a la fecha de nacimiento."
                );
            }
            calculated = Period.between(dateOfBirth, studyDate).getYears();
        }
        if (Study.AGE_CALCULATED.equals(provenance)) {
            if (calculated == null) {
                throw new StudyMetadataException(
                    HttpStatus.BAD_REQUEST,
                    "AGE_NOT_CALCULABLE",
                    "No se puede calcular la edad: faltan la fecha de nacimiento del paciente o la fecha del estudio."
                );
            }
            if (!calculated.equals(age)) {
                throw new StudyMetadataException(
                    HttpStatus.BAD_REQUEST,
                    "AGE_CALCULATION_MISMATCH",
                    "ageAtStudy no coincide con la edad calculada (" + calculated + ")."
                );
            }
            return new AgeResolution(calculated, Study.AGE_CALCULATED, false);
        }
        return new AgeResolution(age, Study.AGE_REPORTED, calculated != null && !calculated.equals(age));
    }

    private Map<String, Object> contextAuditExtra(Study study, boolean ageDiscrepancy, Map<String, Object> base) {
        // Solo indicadores: ni el texto clinico ni la edad exacta viajan al registro de auditoria.
        Map<String, Object> extra = new java.util.LinkedHashMap<>(base);
        extra.put("patientLinked", study.patientId() != null);
        extra.put("ageProvenance", study.ageProvenance());
        extra.put("ageDiscrepancy", ageDiscrepancy);
        extra.put("clinicalTextPresent",
            study.clinicalReason() != null || study.symptoms() != null
                || study.symptomDuration() != null || study.relevantHistory() != null);
        return extra;
    }

    private String normalizeSubjectRef(String subjectRef) {
        String value = trimToNull(subjectRef);
        if (value == null) return null;
        if (!SUBJECT_REF_PATTERN.matcher(value).matches() || value.contains("..")) {
            throw new StudyMetadataException(
                HttpStatus.BAD_REQUEST,
                "INVALID_SUBJECT_REFERENCE",
                "subjectRef debe ser una referencia academica de-identificada de 3 a 64 caracteres, sin espacios, arroba, barras ni traversal."
            );
        }
        return value;
    }

    private String normalizePriority(String priority) {
        String value = trimToNull(priority);
        if (value == null) return null;
        String normalized = value.toLowerCase();
        return switch (normalized) {
            case "low", "medium", "high" -> normalized;
            case "baja" -> "low";
            case "media" -> "medium";
            case "alta" -> "high";
            default -> throw new StudyMetadataException(
                HttpStatus.BAD_REQUEST,
                "INVALID_REVIEW_PRIORITY",
                "reviewPriority debe ser low/medium/high o baja/media/alta."
            );
        };
    }

    private void audit(String action, Study study, StudyMetadataDto metadata, Map<String, Object> extra) {
        try {
            Map<String, Object> base = new java.util.LinkedHashMap<>();
            base.put("caseId", study.caseId());
            base.put("subjectRefPresent", study.subjectRef() != null);
            base.put("studyDatePresent", study.studyDate() != null);
            base.put("modalityPresent", study.modality() != null);
            base.put("descriptionPresent", study.description() != null);
            base.put("reviewPriority", study.reviewPriority());
            if (metadata != null && metadata.subjectRef() != null) base.put("subjectRef", metadata.subjectRef());
            base.putAll(extra);
            repository.saveAuditEvent(new DomainAuditEvent(
                UUID.randomUUID().toString(),
                "backend",
                action,
                study.id(),
                "",
                clock.instant(),
                base
            ));
        } catch (RuntimeException auditFailure) {
            log.warn(
                "metadata_audit_failed action={} caseId={} errorType={}",
                action,
                study.caseId(),
                auditFailure.getClass().getSimpleName()
            );
        }
    }

    private Optional<Study> findStudyForMetadata(String caseId) {
        try {
            return repository.findStudyByCaseId(caseId);
        } catch (IllegalStateException ex) {
            throw new DatabaseUnavailableException(ex);
        }
    }

    private Study saveStudyForMetadata(Study study) {
        try {
            return repository.saveStudy(study);
        } catch (IllegalStateException ex) {
            throw new DatabaseUnavailableException(ex);
        }
    }

    private String requireCaseId(String caseId) {
        String value = trimToNull(caseId);
        if (value == null) throw new IllegalArgumentException("caseId es obligatorio");
        return value;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean same(Object left, Object right) {
        return java.util.Objects.equals(left, right);
    }

    public record PreparedStudyMetadata(String caseId, StudyMetadataDto metadata, Study existingStudy, boolean ageDiscrepancy) {
        public PreparedStudyMetadata(String caseId, StudyMetadataDto metadata, Study existingStudy) {
            this(caseId, metadata, existingStudy, false);
        }
    }

    record AgeResolution(Integer value, String provenance, boolean discrepancy) {
    }
}
