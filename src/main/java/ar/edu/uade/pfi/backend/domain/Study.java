package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

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
    public Study(String id, String caseId, String status, Instant createdAt, Instant updatedAt) {
        this(id, caseId, status, null, null, null, null, null, "medium", null, null, null, null, null, null, null, createdAt, updatedAt);
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
            reviewPriority, null, null, null, null, null, null, null,
            createdAt,
            updatedAt
        );
    }

    public Study(String id, String caseId, String status, String patientId, String subjectRef, LocalDate studyDate, String modality, String description, String reviewPriority, Instant createdAt, Instant updatedAt) {
        this(id, caseId, status, patientId, subjectRef, studyDate, modality, description, reviewPriority, null, null, null, null, null, null, null, createdAt, updatedAt);
    }

    public Study {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(status, "status");
        if (reviewPriority == null || reviewPriority.isBlank()) reviewPriority = "medium";
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (ageAtStudy != null && (ageAtStudy < 0 || ageAtStudy > 130)) throw new IllegalArgumentException("ageAtStudy out of range");
        contextVersion = contextVersion == null || contextVersion.isBlank() ? "pfi.study-context.v1" : contextVersion;
    }
}
