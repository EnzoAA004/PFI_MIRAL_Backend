package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;

/**
 * Estado de revisión profesional para un {@link AiProductAnalysisRun} — etapa 78-B. Exactamente una fila
 * por corrida (upsert, no append): la decisión actual del profesional sobre esta corrida, no una
 * traza de auditoría append-only. La auditabilidad de los *cambios* en esta fila se provee por separado vía
 * {@link DomainAuditEvent} ({@code action = "ai_product_review.run_status_changed"}), siguiendo
 * la convención existente en lugar de inventar un nuevo event sourcing.
 *
 * <p>{@code reviewStatus} es uno de {@code pending|accepted|observed|rejected} — deliberadamente no
 * un vocabulario clínico ("diagnosed"/"confirmed"/"normal"); esta es una decisión de revisión profesional
 * sobre la corrida de IA, no un hallazgo clínico.
 */
public record AiProductAnalysisRunReview(
    String id,
    String analysisRunId,
    String reviewStatus,
    String reviewComment,
    String reviewedBy,
    Instant reviewedAt,
    Instant updatedAt
) {
}
