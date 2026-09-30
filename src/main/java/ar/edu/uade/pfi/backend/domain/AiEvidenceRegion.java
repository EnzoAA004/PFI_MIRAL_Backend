package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * EXT-04B2: a visual region of interest tied to an {@link AiProductAnalysisRun} — a bounding
 * box, ellipse, polygon, or mask reference, either suggested by AI or drawn directly by a
 * professional. This is the "root" row: it is never UPDATEd or DELETEd once created (the same
 * immutability guarantee {@link AiMeasurementResult} already gives the AI's original scalar
 * output). A professional correction of an {@code origin="ai"} region is a new, separate
 * {@link AiEvidenceRegionCorrection} row — the geometry here is never overwritten.
 *
 * <p>Deliberately NOT a generalization of {@link ReviewerAnnotation}: that type's
 * {@code scope}/{@code kind} vocabulary is shaped for free-form reviewer notes/measurements
 * that may not correspond to any AI output, and it is edited in place rather than append-only
 * (EXT-04B1 section 6/12). This type instead follows the stage-78
 * {@code AiMeasurementResult}/{@code AiMeasurementCorrection} convention, because it must
 * preserve the original AI geometry forever for EXT-05.
 *
 * <p>Review state is intentionally NOT stored here: whether this region's AI suggestion is
 * pending/accepted/observed/rejected is answered by the existing {@code AiDiscLevelReview}
 * (when {@code discLevelResultId} is set) or {@code AiProductAnalysisRunReview} (run-level
 * fallback) — reused as-is (EXT-04B1 principle 3/6). {@code authoredBy} here is a different,
 * narrower concept: who drew this region directly, meaningful only when
 * {@code origin="professional"}, and never serialized to the structured report (EXT-04B1
 * section 9) — the same "no reviewer identity in the report" rule EXT-04A already enforces.
 */
public record AiEvidenceRegion(
    String id,
    String analysisRunId,
    /** Nullable: a run-level region (not tied to a specific disc level) is valid. */
    String discLevelResultId,
    /** Nullable: an evidence region does not need to illustrate one specific scalar measurement. */
    String measurementId,
    String plane,
    String level,
    String structure,
    Integer sliceIndex,
    /** {@code "ai" | "professional"} — who produced the CURRENT geometry in this row. */
    String origin,
    String geometryType,
    Map<String, Object> geometry,
    /**
     * Nullable, {@code origin="ai"} only: the AI's own per-slice pixel frame
     * (width/height/units/origin/axis directions/sourceSliceIndex), kept only for audit/
     * reproducibility of the {@link EvidenceCoordinateNormalizer} conversion — never the
     * basis {@code geometry} itself is expressed in (EXT-04B1 section 8).
     */
    Map<String, Object> coordinateSpaceRef,
    String modelId,
    String modelSha256,
    String pipelineVersion,
    /** Only ever non-null when the source model genuinely emits one — never fabricated. */
    Double confidence,
    /** True for geometry sourced from an unvalidated/experimental capability (e.g. 67C). */
    boolean experimental,
    /** Neutral, non-diagnostic label — e.g. "Región de interés". Never "patología confirmada". */
    String label,
    String comment,
    /** Internal only; never serialized to the structured report. */
    String authoredBy,
    Instant createdAt
) {
    private static final List<String> ORIGINS = List.of("ai", "professional");
    private static final List<String> PLANES = List.of("sagittal", "axial");
    public static final String DEFAULT_LABEL = "Región de interés";

    public AiEvidenceRegion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(analysisRunId, "analysisRunId");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(geometryType, "geometryType");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(createdAt, "createdAt");
        require(ORIGINS.contains(origin), "origin inválido: " + origin);
        require(plane == null || PLANES.contains(plane), "plane inválido: " + plane);
        require(sliceIndex == null || sliceIndex >= 0, "sliceIndex no puede ser negativo");
        // Eagerly validates the geometry shape — an AiEvidenceRegion can never be constructed
        // with a geometry that wouldn't parse back (mirrors ReviewerAnnotation's own
        // compact-constructor validation, never left to the caller to check separately).
        EvidenceGeometry.fromMap(geometryType, geometry);
        require(
            !"ai".equals(origin) || modelId != null || modelSha256 != null || pipelineVersion != null,
            "una región de origen IA debe declarar al menos un dato de proveniencia (modelId/modelSha256/pipelineVersion)"
        );
        require(confidence == null || (confidence >= 0.0 && confidence <= 1.0), "confidence debe estar en [0,1]");
        label = (label == null || label.isBlank()) ? DEFAULT_LABEL : label;
        geometry = Map.copyOf(geometry);
        coordinateSpaceRef = coordinateSpaceRef == null ? null : Map.copyOf(coordinateSpaceRef);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
