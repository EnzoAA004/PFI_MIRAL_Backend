package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * EXT-04B2: una región visual de interés atada a un {@link AiProductAnalysisRun} — un bounding
 * box, elipse, polígono o referencia a máscara, sugerida por la IA o dibujada directamente por un
 * profesional. Esta es la fila "raíz": nunca se hace UPDATE ni DELETE una vez creada (la misma
 * garantía de inmutabilidad que {@link AiMeasurementResult} ya da a la salida escalar original de la
 * IA). Una corrección profesional de una región {@code origin="ai"} es una fila nueva y separada de
 * {@link AiEvidenceRegionCorrection} — la geometría de acá nunca se sobrescribe.
 *
 * <p>Deliberadamente NO es una generalización de {@link ReviewerAnnotation}: el vocabulario
 * {@code scope}/{@code kind} de ese tipo está pensado para notas/mediciones libres del revisor
 * que pueden no corresponder a ninguna salida de la IA, y se edita en el lugar en vez de ser append-only
 * (EXT-04B1 sección 6/12). Este tipo, en cambio, sigue la convención de la etapa 78
 * {@code AiMeasurementResult}/{@code AiMeasurementCorrection}, porque debe
 * preservar para siempre la geometría original de la IA para EXT-05.
 *
 * <p>El estado de revisión intencionalmente NO se guarda acá: si la sugerencia de IA de esta región está
 * pendiente/aceptada/observada/rechazada lo responde el {@code AiDiscLevelReview} existente
 * (cuando {@code discLevelResultId} está seteado) o {@code AiProductAnalysisRunReview} (fallback a nivel de
 * corrida) — reutilizados tal cual (EXT-04B1 principio 3/6). {@code authoredBy} acá es un concepto distinto y
 * más acotado: quién dibujó esta región directamente, significativo solo cuando
 * {@code origin="professional"}, y nunca se serializa al reporte estructurado (EXT-04B1
 * sección 9) — la misma regla de "sin identidad del revisor en el reporte" que EXT-04A ya aplica.
 */
public record AiEvidenceRegion(
    String id,
    String analysisRunId,
    /** Admite null: una región a nivel de corrida (no atada a un nivel de disco específico) es válida. */
    String discLevelResultId,
    /** Admite null: una región de evidencia no necesita ilustrar una medición escalar específica. */
    String measurementId,
    String plane,
    String level,
    String structure,
    Integer sliceIndex,
    /** {@code "ai" | "professional"} — quién produjo la geometría ACTUAL de esta fila. */
    String origin,
    String geometryType,
    Map<String, Object> geometry,
    /**
     * Admite null, solo con {@code origin="ai"}: el propio marco de píxeles por slice de la IA
     * (width/height/units/origin/direcciones de ejes/sourceSliceIndex), conservado solo para auditoría/
     * reproducibilidad de la conversión de {@link EvidenceCoordinateNormalizer} — nunca la
     * base en la que se expresa la {@code geometry} misma (EXT-04B1 sección 8).
     */
    Map<String, Object> coordinateSpaceRef,
    String modelId,
    String modelSha256,
    String pipelineVersion,
    /** Solo es no-null cuando el modelo de origen realmente emite uno — nunca se fabrica. */
    Double confidence,
    /** True para geometría proveniente de una capacidad no validada/experimental (p. ej. 67C). */
    boolean experimental,
    /** Etiqueta neutral, no diagnóstica — p. ej. "Región de interés". Nunca "patología confirmada". */
    String label,
    String comment,
    /** Solo interno; nunca se serializa al reporte estructurado. */
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
        // Valida de forma anticipada la forma de la geometría — un AiEvidenceRegion nunca puede construirse
        // con una geometría que no pueda volver a parsearse (replica la validación del compact constructor
        // propio de ReviewerAnnotation, sin dejar que quien llama la chequee por separado).
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
