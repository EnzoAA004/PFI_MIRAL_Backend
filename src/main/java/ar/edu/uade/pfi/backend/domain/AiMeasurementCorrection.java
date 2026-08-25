package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;

/**
 * A professional correction of one field of an {@link AiMeasurementResult} — stage 78-D.
 *
 * <p>Append-only by design: {@code AiMeasurementResult} itself is never updated (it stays the
 * AI's original, immutable output — stage 76's own guarantee). Each correction is a new row
 * carrying both {@code originalValue} (a snapshot of the AI value at correction time) and
 * {@code correctedValue}, so "what the AI said" is never lost even if corrected more than once.
 * The current corrected value for a measurement is simply the latest row by {@code createdAt}.
 *
 * <p>{@code field} is one of {@code areaMm2|majorAxisMm|minorAxisMm} — the only
 * {@code AiMeasurementResult} numeric fields this product actually exposes (stage 76 deliberately
 * has no {@code bboxHeightMm}/{@code bboxWidthMm}, so there is nothing to correct there either).
 * {@code unit} is derived server-side from {@code field} — never accepted from the client, so a
 * correction can't silently relabel {@code mm} as {@code mm2} or vice versa.
 */
public record AiMeasurementCorrection(
    String id,
    String measurementId,
    String analysisRunId,
    String field,
    Double originalValue,
    double correctedValue,
    String unit,
    String comment,
    String reviewedBy,
    Instant createdAt
) {
}
