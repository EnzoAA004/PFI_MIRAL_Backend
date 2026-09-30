package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A professional correction of the geometry of an {@code origin="ai"} {@link AiEvidenceRegion}
 * — EXT-04B2, mirroring {@link AiMeasurementCorrection} exactly: append-only, the root row is
 * never mutated, and each correction is a new row carrying both a snapshot of the AI geometry
 * at correction time ({@code originalGeometryType}/{@code originalGeometry}) and the corrected
 * geometry. The current corrected geometry for a region is simply the latest row by
 * {@code createdAt} (same rule {@link ar.edu.uade.pfi.backend.service.EffectiveMeasurementResolver}
 * already uses for scalar corrections).
 *
 * <p>Only meaningful for regions whose root {@link AiEvidenceRegion#origin()} is {@code "ai"} —
 * a professional revising their own hand-drawn region is a product decision left open for a
 * future iteration (see EXT-04B2 limitations); this table does not attempt to model that case
 * yet, and nothing here enforces it at the database level (the same light-touch convention
 * {@code domain_ai_measurement_corrections} already uses — trusted at the service layer, not by
 * a cross-table DB constraint).
 */
public record AiEvidenceRegionCorrection(
    String id,
    String evidenceRegionId,
    String analysisRunId,
    /** Nullable together with {@code originalGeometry}: absent only if the caller genuinely has no snapshot to offer. */
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
        // Validates both shapes eagerly, same discipline as AiEvidenceRegion's own constructor.
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
