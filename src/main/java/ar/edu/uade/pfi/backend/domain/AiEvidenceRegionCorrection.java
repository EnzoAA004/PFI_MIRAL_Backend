package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Una corrección profesional de la geometría de un {@link AiEvidenceRegion} con {@code origin="ai"}
 * — EXT-04B2, replicando exactamente {@link AiMeasurementCorrection}: append-only, la fila raíz
 * nunca se muta, y cada corrección es una fila nueva que lleva tanto una instantánea de la geometría de la IA
 * al momento de la corrección ({@code originalGeometryType}/{@code originalGeometry}) como la geometría
 * corregida. La geometría corregida vigente de una región es simplemente la última fila por
 * {@code createdAt} (la misma regla que ya usa {@link ar.edu.uade.pfi.backend.service.EffectiveMeasurementResolver}
 * para las correcciones escalares).
 *
 * <p>Solo tiene sentido para regiones cuyo {@link AiEvidenceRegion#origin()} raíz es {@code "ai"} —
 * que un profesional revise su propia región dibujada a mano es una decisión de producto que queda abierta para una
 * iteración futura (ver limitaciones de EXT-04B2); esta tabla todavía no intenta modelar ese caso,
 * y nada acá lo hace cumplir a nivel de base de datos (la misma convención liviana que ya usa
 * {@code domain_ai_measurement_corrections} — se confía en la capa de servicio, no en
 * un constraint de DB entre tablas).
 */
public record AiEvidenceRegionCorrection(
    String id,
    String evidenceRegionId,
    String analysisRunId,
    /** Admite null junto con {@code originalGeometry}: ausente solo si quien llama realmente no tiene una instantánea para ofrecer. */
    String originalGeometryType,
    Map<String, Object> originalGeometry,
    String correctedGeometryType,
    Map<String, Object> correctedGeometry,
    String comment,
    String reviewedBy,
    Instant createdAt
) {
    public AiEvidenceRegionCorrection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(evidenceRegionId, "evidenceRegionId");
        Objects.requireNonNull(analysisRunId, "analysisRunId");
        Objects.requireNonNull(correctedGeometryType, "correctedGeometryType");
        Objects.requireNonNull(correctedGeometry, "correctedGeometry");
        Objects.requireNonNull(createdAt, "createdAt");
        // Valida ambas formas de forma anticipada, con la misma disciplina que el constructor propio de AiEvidenceRegion.
        EvidenceGeometry.fromMap(correctedGeometryType, correctedGeometry);
        if (originalGeometryType != null || originalGeometry != null) {
            Objects.requireNonNull(originalGeometryType, "originalGeometryType");
            Objects.requireNonNull(originalGeometry, "originalGeometry");
            EvidenceGeometry.fromMap(originalGeometryType, originalGeometry);
            originalGeometry = Map.copyOf(originalGeometry);
        }
        correctedGeometry = Map.copyOf(correctedGeometry);
    }
}
