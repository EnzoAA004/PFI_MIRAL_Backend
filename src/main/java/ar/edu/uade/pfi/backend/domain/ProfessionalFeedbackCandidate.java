package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * EXT-07D: a structured, traceable CAPTURE of one professional correction (of a measurement or
 * an evidence region) or one professional-authored finding the AI never suggested — candidate
 * material for a future, entirely separate curation/offline-retraining process. This record
 * never trains anything itself; see {@link ar.edu.uade.pfi.backend.service.ProfessionalFeedbackCaptureService}
 * for the read-only capture step that produces these rows from data that already exists
 * (append-only) elsewhere.
 *
 * <p>{@code status} moves only by an explicit human-issued call — never automatically, and never
 * to a "ready for training" value, which this product does not model at all (see
 * {@code ALLOWED_STATUSES}).
 */
public record ProfessionalFeedbackCandidate(
    String id,
    String studyId,
    String caseId,
    String analysisRunId,
    /** {@code "measurement" | "evidence_region" | "professional_new_finding" | "rejected_ai_suggestion"}. */
    String source,
    /** The correction row (or, for {@code professional_new_finding}, the region row itself) this was captured from. */
    String correctionId,
    String level,
    String plane,
    String structure,
    /** Populated for {@code source="measurement"} only. */
    String metric,
    /** Populated for {@code source="evidence_region"}/{@code "professional_new_finding"} only. */
    String geometryType,
    Double aiOriginalValue,
    Map<String, Object> aiOriginalGeometry,
    Double professionalValue,
    Map<String, Object> professionalGeometry,
    String modelId,
    String modelSha256,
    String pipelineVersion,
    /** Internal only — never serialized to the public feedback-candidate API (EXT-04A precedent). */
    String reviewedBy,
    String note,
    String status,
    Instant correctedAt,
    Instant capturedAt,
    /** EXT-05: internal only, same rule as {@code reviewedBy} — never serialized. Who made the *curation* decision (distinct from who reviewed/corrected the original AI output). */
    String curatorId,
    /** EXT-05: when the current {@code status} was set by a curation decision; null while still {@code "captured"}. */
    Instant curatedAt,
    /** EXT-05: only meaningful when {@code status="excluded"}; a free-text reason a curator gave for excluding this candidate. */
    String exclusionReason
) {
    private static final List<String> SOURCES = List.of("measurement", "evidence_region", "professional_new_finding", "rejected_ai_suggestion");
    public static final List<String> ALLOWED_STATUSES = List.of("captured", "eligible_for_curation", "excluded", "curated");

    public ProfessionalFeedbackCandidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(studyId, "studyId");
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(analysisRunId, "analysisRunId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(correctionId, "correctionId");
        Objects.requireNonNull(correctedAt, "correctedAt");
        Objects.requireNonNull(capturedAt, "capturedAt");
        require(SOURCES.contains(source), "source inválido: " + source);
        status = (status == null || status.isBlank()) ? "captured" : status;
        require(ALLOWED_STATUSES.contains(status), "status inválido: " + status);
        aiOriginalGeometry = aiOriginalGeometry == null ? null : Map.copyOf(aiOriginalGeometry);
        professionalGeometry = professionalGeometry == null ? null : Map.copyOf(professionalGeometry);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
