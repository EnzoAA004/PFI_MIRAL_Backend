package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;

/**
 * Decision del profesional sobre un hallazgo propuesto por la IA en UNA corrida exacta. {@code decision} es
 * aceptado, rechazado o dudoso; la ausencia de registro significa "sin revisar". No altera la prediccion original.
 */
public record FindingDecision(
    String studyId,
    String runId,
    String findingKey,
    String decision,
    String note,
    String decidedBy,
    Instant decidedAt) {}
