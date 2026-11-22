package ar.edu.uade.pfi.backend.service;

import java.time.Instant;
import java.util.List;

/**
 * EXT-02: "telemetría de feedback de revisión" — responde "dónde está fallando más el modelo" usando solo
 * datos que ya existen (correcciones de mediciones, correcciones/estado de revisión de regiones de evidencia),
 * nunca una nueva capacidad de IA. Explícitamente descriptivo, nunca diagnóstico: ningún campo acá afirma
 * "precisión clínica" ni "seguridad del modelo" — ver los disclaimers estilo {@code REPORT_SAFETY_TEXT} en la
 * capa de presentación del Frontend.
 */
public record ReviewAnalyticsReport(
    String schemaVersion,
    Instant generatedAt,
    MeasurementAnalytics measurements,
    EvidenceRegionAnalytics evidenceRegions,
    /** Brechas documentadas — una métrica que esta spec pidió y que no tiene un contrato estable desde el cual calcularla, nombrada explícitamente en lugar de inventada. */
    List<String> limitations
) {
    public record MeasurementAnalytics(
        int totalAutomaticMeasurements,
        int correctedCount,
        double correctionRate,
        List<MeasurementGroup> groups
    ) {
    }

    /** Un bucket (level, plane, structure, metric, unit, releaseId) — nunca mezcla unidades ni métricas. */
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
     * Solo se produce para una corrección cuya geometría original y corregida comparten la misma
     * forma comparable (BBOX→BBOX o ELLIPSE→ELLIPSE) — una corrección que cambia de forma (p. ej.
     * BBOX→ELLIPSE) o cualquier cosa que toque MASK_REFERENCE se omite, nunca se fuerza a un número
     * que no significaría lo mismo entre filas.
     */
    public record GeometryCorrectionDelta(
        String regionId,
        String correctionId,
        String geometryType,
        double centroidDisplacement,
        double areaDelta,
        /** Solo se calcula para BBOX; null para ELLIPSE (no se implementó un IoU simple de forma cerrada en esta etapa — ver limitaciones). */
        Double intersectionOverUnion
    ) {
    }
}
