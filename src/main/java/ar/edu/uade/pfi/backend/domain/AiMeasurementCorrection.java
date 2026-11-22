package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;

/**
 * Una corrección profesional de un campo de un {@link AiMeasurementResult} — etapa 78-D.
 *
 * <p>Append-only por diseño: el propio {@code AiMeasurementResult} nunca se actualiza (sigue siendo la
 * salida original e inmutable de la IA — la propia garantía de la etapa 76). Cada corrección es una fila nueva
 * que lleva tanto {@code originalValue} (una instantánea del valor de la IA al momento de la corrección) como
 * {@code correctedValue}, de modo que "lo que dijo la IA" nunca se pierde aunque se corrija más de una vez.
 * El valor corregido vigente de una medición es simplemente la última fila por {@code createdAt}.
 *
 * <p>{@code field} es uno de {@code areaMm2|majorAxisMm|minorAxisMm} — los únicos campos numéricos de
 * {@code AiMeasurementResult} que este producto realmente expone (la etapa 76 deliberadamente
 * no tiene {@code bboxHeightMm}/{@code bboxWidthMm}, así que tampoco hay nada que corregir ahí).
 * {@code unit} se deriva del lado del servidor a partir de {@code field} — nunca se acepta del cliente, para que una
 * corrección no pueda reetiquetar en silencio {@code mm} como {@code mm2} o viceversa.
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
