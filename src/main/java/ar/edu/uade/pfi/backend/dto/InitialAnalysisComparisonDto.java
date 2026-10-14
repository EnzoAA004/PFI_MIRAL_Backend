package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Comparación entre dos estudios sobre las mediciones REALES del análisis inicial (la corrida
 * multiplanar que muestra el visor), no sobre las tablas del circuito de producto/demostración.
 *
 * <p>Convención: {@code diferencia = estudio abierto − referencia}. Nada de lo que viaja acá se
 * interpreta clínicamente: "increased"/"decreased" describen el valor numérico.
 */
public record InitialAnalysisComparisonDto(
    String schemaVersion,
    String source,
    String differenceDefinition,
    StudySide current,
    StudySide reference,
    Summary summary,
    List<Row> comparisons,
    List<Exclusion> excluded,
    List<String> limitations
) {
    public static final String SCHEMA_VERSION = "pfi.initial-analysis-comparison.v1";
    public static final String SOURCE = "initial_analysis";
    public static final String DIFFERENCE_DEFINITION = "diferencia = estudio abierto − referencia";

    /** El estudio y la corrida del análisis inicial que se consultó, para que nada se sustituya en silencio. */
    public record StudySide(
        String caseId,
        LocalDate studyDate,
        String runId,
        Instant runCreatedAt,
        String runStatus,
        String reviewStatus,
        String modelKey,
        String sagittalSequence,
        /** {@code user_declared}, {@code auto_detected} o {@code unknown}. Una declaración no es una detección. */
        String sagittalSequenceSource,
        /** Texto libre de la secuencia «otra», solo informativo. */
        String sagittalSequenceDescription,
        int usableMeasurementCount,
        String patientLink
    ) {
    }

    public record Summary(int comparedCount, int excludedCount, int currentMeasurementCount, int referenceMeasurementCount) {
    }

    /** Un valor tal como se usó: el efectivo, y el original de la IA siempre consultable. */
    public record Value(
        String measurementId,
        double effectiveValue,
        double originalValue,
        Double correctedValue,
        boolean corrected,
        Integer sliceIndex,
        Location location
    ) {
    }

    public record Point(double x, double y) {
    }

    /**
     * Dónde está la medición en la imagen, tal como quedó persistida: nada se deduce por el nombre de la
     * medición ni por el número de corte del otro estudio.
     *
     * <p>{@code geometry} es el trazo de la IA en el espacio {@code coordinateSpace}. La corrección numérica no lo
     * modifica; {@code correctedGeometry} solo viaja si el revisor guardó un trazo propio distinto del de la IA.
     * {@code status}: {@code located} (corte y trazo), {@code slice_only} (corte sin trazo, p. ej. áreas),
     * {@code not_located} (sin corte persistido).
     */
    public record Location(
        String plane,
        String planeRunId,
        String inputId,
        Integer sliceIndex,
        String coordinateSpace,
        Integer coordinateWidth,
        Integer coordinateHeight,
        String geometryKind,
        List<Point> geometry,
        List<Point> correctedGeometry,
        String status,
        String reason
    ) {
        public static final String LOCATED = "located";
        public static final String SLICE_ONLY = "slice_only";
        public static final String NOT_LOCATED = "not_located";
    }

    public record Row(
        String level,
        String structure,
        String metric,
        String plane,
        String unit,
        boolean experimental,
        Value reference,
        Value current,
        double deltaAbsolute,
        Double deltaPercent,
        String percentStatus,
        String trend
    ) {
        public static final String PERCENT_OK = "ok";
        public static final String PERCENT_REFERENCE_ZERO = "not_computable_reference_zero";
    }

    /** Qué quedó fuera y por qué, con el motivo concreto de cada una. */
    public record Exclusion(
        String side,
        String level,
        String plane,
        String metricKey,
        List<String> measurementIds,
        String reasonCode,
        String reason
    ) {
    }

    /** Un estudio del mismo paciente ofrecido como referencia. */
    public record Candidate(
        String caseId,
        LocalDate studyDate,
        String runId,
        Instant runCreatedAt,
        int usableMeasurementCount,
        boolean eligible,
        String reasonCode,
        String reason,
        String patientLink
    ) {
    }

    public record Candidates(
        String schemaVersion,
        StudySide current,
        List<Candidate> candidates,
        String runSelectionRule
    ) {
    }
}
