package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Un estudio. Ademas de su identidad y metadata, lleva su propio contexto clinico
 * ({@code ageAtStudy}, {@code ageProvenance}, motivo, sintomas, evolucion y antecedentes): es
 * informacion de ESE estudio, guardada como valor, y no se deriva del paciente. Editar despues al
 * paciente no la reescribe.
 */
public record Study(
    String id,
    String caseId,
    String status,
    String patientId,
    String subjectRef,
    LocalDate studyDate,
    String modality,
    String description,
    String reviewPriority,
    Integer ageAtStudy,
    String ageProvenance,
    String clinicalReason,
    String symptoms,
    String symptomDuration,
    String relevantHistory,
    String contextVersion,
    Instant createdAt,
    Instant updatedAt
) {
    public static final String CONTEXT_VERSION = "pfi.study-context.v1";
    public static final String AGE_CALCULATED = "calculated";
    public static final String AGE_REPORTED = "reported";

    public Study(String id, String caseId, String status, Instant createdAt, Instant updatedAt) {
        this(id, caseId, status, null, null, null, null, null, "medium", createdAt, updatedAt);
    }

    /**
     * Sobrecarga de compatibilidad previa a PATIENT-PR1: conserva la firma sin {@code patientId}
     * que usan los llamadores del linaje post-e50/EXT, delegando con {@code patientId = null}.
     * La identidad longitudinal por {@code subjectRef} y la asociación a {@code patientId}
     * coexisten de forma aditiva; esta migración no reconcilia una desde la otra.
     */
    public Study(
        String id,
        String caseId,
        String status,
        String subjectRef,
        LocalDate studyDate,
        String modality,
        String description,
        String reviewPriority,
        Instant createdAt,
        Instant updatedAt
    ) {
        this(
            id,
            caseId,
            status,
            null,
            subjectRef,
            studyDate,
            modality,
            description,
            reviewPriority,
            createdAt,
            updatedAt
        );
    }

    /** Sobrecarga de compatibilidad: estudio con paciente pero sin contexto clinico. */
    public Study(
        String id,
        String caseId,
        String status,
        String patientId,
        String subjectRef,
        LocalDate studyDate,
        String modality,
        String description,
        String reviewPriority,
        Instant createdAt,
        Instant updatedAt
    ) {
        this(
            id,
            caseId,
            status,
            patientId,
            subjectRef,
            studyDate,
            modality,
            description,
            reviewPriority,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            createdAt,
            updatedAt
        );
    }

    public Study {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(status, "status");
        if (reviewPriority == null || reviewPriority.isBlank()) reviewPriority = "medium";
        if (ageAtStudy != null && (ageAtStudy < 0 || ageAtStudy > 130)) {
            throw new IllegalArgumentException("ageAtStudy out of range");
        }
        if ((ageAtStudy == null) != (ageProvenance == null)) {
            throw new IllegalArgumentException("ageAtStudy and ageProvenance must be set together");
        }
        contextVersion = contextVersion == null || contextVersion.isBlank() ? CONTEXT_VERSION : contextVersion;
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** Copia con otro contexto clinico; el resto del estudio no cambia. */
    public Study withClinicalContext(
        Integer ageAtStudy,
        String ageProvenance,
        String clinicalReason,
        String symptoms,
        String symptomDuration,
        String relevantHistory,
        Instant updatedAt
    ) {
        return new Study(
            id, caseId, status, patientId, subjectRef, studyDate, modality, description, reviewPriority,
            ageAtStudy, ageProvenance, clinicalReason, symptoms, symptomDuration, relevantHistory,
            contextVersion, createdAt, updatedAt
        );
    }
}
