package ar.edu.uade.pfi.backend.dto;

import java.time.LocalDate;

/**
 * Etapa 85-B/85-T: una fila de la proyección de lista de pacientes — un agregado liviano sobre
 * {@code Study.subjectRef}, nunca una entidad persistida. {@code latestAnalysisStatus}/
 * {@code latestReviewStatus} describen el análisis efectivo propio del Study {@code latestCaseId}
 * (vía {@link ar.edu.uade.pfi.backend.service.EffectiveAnalysisRunSelector}, Etapa 79) — la misma
 * regla de selección que ya usan el historial/series de la Etapa 84 y el chat de la Etapa 80, de modo que esta lista nunca sea
 * una segunda fuente de verdad divergente sobre "qué corrida representa a un Study."
 *
 * <p>Deliberadamente no lleva ningún detalle de Study/medición/corrección — eso es trabajo de la
 * Etapa 84 ({@code GET /api/subjects/{subjectRef}/history}), no de esta lista.
 */
public record SubjectSummaryDto(
    String subjectRef,
    int studyCount,
    LocalDate latestStudyDate,
    String latestCaseId,
    /** {@code null} cuando el último Study todavía no tiene una corrida de análisis efectiva (85: un estado normal, no un error). */
    String latestAnalysisStatus,
    /** {@code null} bajo la misma condición que {@code latestAnalysisStatus}; de lo contrario pending|accepted|observed|rejected. */
    String latestReviewStatus
) {
}
