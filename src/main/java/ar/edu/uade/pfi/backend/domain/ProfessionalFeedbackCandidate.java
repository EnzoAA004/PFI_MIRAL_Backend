package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * EXT-07D: una CAPTURA estructurada y trazable de una corrección profesional (de una medición o
 * una región de evidencia) o de un hallazgo de autoría profesional que la IA nunca sugirió — material
 * candidato para un proceso futuro, completamente separado, de curación/reentrenamiento offline. Este record
 * nunca entrena nada por sí mismo; ver {@link ar.edu.uade.pfi.backend.service.ProfessionalFeedbackCaptureService}
 * para el paso de captura de solo lectura que produce estas filas a partir de datos que ya existen
 * (append-only) en otro lugar.
 *
 * <p>{@code status} solo se mueve mediante una llamada explícita emitida por una persona — nunca automáticamente, y nunca
 * a un valor "listo para entrenar", que este producto no modela en absoluto (ver
 * {@code ALLOWED_STATUSES}).
 */
public record ProfessionalFeedbackCandidate(
    String id,
    String studyId,
    String caseId,
    String analysisRunId,
    /** {@code "measurement" | "evidence_region" | "professional_new_finding" | "rejected_ai_suggestion"}. */
    String source,
    /** La fila de corrección (o, para {@code professional_new_finding}, la propia fila de región) de la que se capturó esto. */
    String correctionId,
    String level,
    String plane,
    String structure,
    /** Se completa solo para {@code source="measurement"}. */
    String metric,
    /** Se completa solo para {@code source="evidence_region"}/{@code "professional_new_finding"}. */
    String geometryType,
    Double aiOriginalValue,
    Map<String, Object> aiOriginalGeometry,
    Double professionalValue,
    Map<String, Object> professionalGeometry,
    String modelId,
    String modelSha256,
    String pipelineVersion,
    /** Solo interno — nunca se serializa a la API pública de candidatos de feedback (precedente EXT-04A). */
    String reviewedBy,
    String note,
    String status,
    Instant correctedAt,
    Instant capturedAt,
    /** EXT-05: solo interno, misma regla que {@code reviewedBy} — nunca se serializa. Quién tomó la decisión de *curación* (distinto de quién revisó/corrigió la salida original de la IA). */
    String curatorId,
    /** EXT-05: cuándo se fijó el {@code status} actual mediante una decisión de curación; null mientras siga en {@code "captured"}. */
    Instant curatedAt,
    /** EXT-05: solo tiene sentido cuando {@code status="excluded"}; un motivo en texto libre que dio un curador para excluir a este candidato. */
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
