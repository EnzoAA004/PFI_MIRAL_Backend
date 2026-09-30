package ar.edu.uade.pfi.backend.service;

import java.time.Instant;
import java.util.List;

/**
 * EXT-02: "review feedback telemetry" — answers "dónde está fallando más el modelo" using only
 * data that already exists (measurement corrections, evidence region corrections/review status),
 * never a new AI capability. Explicitly descriptive, never diagnostic: no field here claims
 * "clinical accuracy" or "model safety" — see {@code REPORT_SAFETY_TEXT}-style disclaimers in the
 * Frontend display layer.
 */
public record ReviewAnalyticsReport(
    String schemaVersion,
    Instant generatedAt,
    MeasurementAnalytics measurements,
    EvidenceRegionAnalytics evidenceRegions,
    /** Documented gaps — a metric this spec asked for that has no stable contract to compute from, named explicitly rather than invented. */
    List<String> limitations
) {
    public record MeasurementAnalytics(
        int totalAutomaticMeasurements,
        int correctedCount,
        double correctionRate,
        List<MeasurementGroup> groups
    ) {
    }

    /** One (level, plane, structure, metric, unit, releaseId) bucket — never mixes units or metrics. */
    public record MeasurementGroup(
        String level,
        String plane,
        String structure,
        String metric,
        String unit,
        String releaseId,
        int automaticCount,
        int correctedCount,
        double correctionRate,
        Double meanAbsoluteError,
        Double medianAbsoluteError,
        Double signedMeanError
    ) {
    }

    public record EvidenceRegionAnalytics(
        int totalAiRegions,
        int accepted,
        int observed,
        int rejected,
        int pending,
        int correctedCount,
        double correctionRate,
        int professionalDrawnCount,
        List<GeometryCorrectionDelta> geometryDeltas
    ) {
    }

    /**
     * Only ever produced for a correction whose original and corrected geometry share the same
     * comparable shape (BBOX→BBOX or ELLIPSE→ELLIPSE) — a shape-changing correction (e.g.
     * BBOX→ELLIPSE) or anything touching MASK_REFERENCE is skipped, never forced into a number
     * that would not mean the same thing across rows.
     */
    public record GeometryCorrectionDelta(
        String regionId,
        String correctionId,
        String geometryType,
        double centroidDisplacement,
        double areaDelta,
        /** Only computed for BBOX; null for ELLIPSE (no closed-form-simple IoU implemented this stage — see limitations). */
        Double intersectionOverUnion
    ) {
    }
}
