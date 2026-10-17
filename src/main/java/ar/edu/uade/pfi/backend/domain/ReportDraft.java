package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;

/**
 * Borrador del preinforme de UNA corrida. {@code generatedJson} es lo generado por codigo (con su huella) y {@code editsJson}
 * lo que decidio y escribio el profesional: se guardan por separado para que una actualizacion explicita de las fuentes
 * nunca pise las ediciones. {@code version} crece en cada guardado (control de concurrencia optimista).
 */
public record ReportDraft(
    String id,
    String studyId,
    String runId,
    int version,
    String sourceFingerprint,
    String generatedJson,
    String editsJson,
    Instant createdAt,
    Instant updatedAt,
    String updatedBy
) {
}
