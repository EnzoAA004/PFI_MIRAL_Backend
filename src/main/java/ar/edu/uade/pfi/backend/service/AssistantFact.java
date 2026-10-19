package ar.edu.uade.pfi.backend.service;

/**
 * Un dato registrado y citable del contexto del asistente, con su referencia. Es la única fuente de las cifras, los
 * niveles, las unidades, las clases, las probabilidades, los lados A/B, las diferencias y los motivos que el asistente
 * muestra: el modelo nunca aporta un valor, solo elige qué datos mostrar. Cada línea de una respuesta se genera a partir
 * de UN hecho y cita SU referencia.
 *
 * <p>Los campos que no corresponden al tipo son {@code null}. Los identificadores técnicos (caso, corridas) no viajan acá.
 */
public record AssistantFact(
    String ref,
    Kind kind,
    /** "" en un estudio individual; "A" (referencia) o "B" (actual) en una comparación, cuando el dato es de un lado. */
    String side,
    String level,
    /** Clave estable para filtrar: clave de medición (p. ej. {@code disc height}), tipo de clasificación o clave de la diferencia. */
    String group,
    String label,
    String unit,
    Double value,
    Double originalValue,
    boolean corrected,
    boolean experimental,
    String plane,
    Integer slice,
    /** Clasificación: texto de la clase tal como se muestra («presente», «ausente», «grado II»…). */
    String classText,
    Double probability,
    /** Clasificación o cambio: {@code main} o {@code research}. */
    String section,
    Double referenceValue,
    Double currentValue,
    Double delta,
    Double deltaPercent,
    boolean referenceCorrected,
    boolean currentCorrected,
    Double referenceOriginal,
    Double currentOriginal,
    /** Cambio de clasificación: estado ({@code unchanged}, {@code absent_to_present}…). */
    String status,
    String fromText,
    String toText,
    /** Motivo de una exclusión, de un cambio no comparable o de un dato ausente (texto ya redactado por el sistema). */
    String reason,
    String reasonCode,
    /** Cantidades de un hecho de resumen. */
    Integer count,
    Integer countB,
    Integer countC
) {
    public enum Kind {
        /** Valor de una medición del análisis inicial (estudio individual). */
        MEASUREMENT,
        /** Clasificación degenerativa por IA de un nivel (estudio individual). */
        FINDING,
        /** Estado de las clasificaciones de la corrida (cuántas son válidas, si faltan, por qué). */
        FINDINGS_STATUS,
        /** Datos de la corrida y recuento de mediciones. */
        RUN,
        /** Diferencia numérica calculada entre A y B (una por nivel y medición). */
        DIFFERENCE,
        /** Mediciones no comparables, agrupadas por motivo. */
        EXCLUSION,
        /** Resumen de la comparación numérica. */
        DIFFERENCE_SUMMARY,
        /** Cambio (o no) de clasificación de un nivel entre A y B. */
        FINDING_CHANGE,
        /** Resumen de la comparación de clasificaciones. */
        FINDING_CHANGE_SUMMARY,
        /** Dato que falta o no está informado. */
        MISSING
    }

    public static AssistantFact of(String ref, Kind kind) {
        return new AssistantFact(ref, kind, "", null, null, null, null, null, null, false, false, null, null, null, null, null,
            null, null, null, null, false, false, null, null, null, null, null, null, null, null, null, null);
    }
}
