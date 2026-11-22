package ar.edu.uade.pfi.backend.domain;

import java.util.Objects;

/**
 * EXT-01: una unidad de evidencia IA/profesional normalizada y comparable — el contrato sobre el que opera cada
 * regla de concordancia, sin importar qué superficie real del producto la produjo (una
 * corrida del clasificador por lote de niveles de disco, una clasificación interactiva de ROI subarticular, una
 * medición, o un resultado de revisión profesional). Deliberadamente NO atada a ninguna tabla de persistencia:
 * las dos fuentes reales, calculadas de forma independiente, que la auditoría de esta etapa encontró comparables son
 * ambas instancias del mismo contrato wire `DegenerativeFindingsV1Dto` (Backend
 * `DegenerativeFindingTypeV1`/`DegenerativeFindingSeverityV1`) invocado desde dos contextos distintos
 * (análisis por lote de niveles de disco vs. clasificación de ROI subarticular a demanda) — ver
 * `docs/extensions/EXT_01_CONCORDANCE.md` "Overlap matrix" para saber qué se juzgó y qué no
 * comparable.
 */
public record EvidenceItem(
    String id,
    String source,
    String modelId,
    String pipelineVersion,
    String caseId,
    String level,
    String side,
    String plane,
    /** DE QUÉ es realmente esta ítem una clasificación/medición — p. ej. "subarticular_stenosis". Solo se comparan ítems que comparten el mismo fenómeno. */
    String phenomenon,
    /** La etiqueta categórica, cuando el fenómeno es ordinal/categórico (p. ej. "normal_mild"/"moderate"/"severe"). Null para un ítem puramente numérico. */
    String finding,
    /** El valor numérico, cuando el fenómeno es una medición. Null para un ítem puramente categórico. */
    Double value,
    /** Solo cuando el modelo de origen realmente emite uno — nunca se fabrica. */
    Double confidence,
    boolean experimental,
    /** "pending"/"accepted"/"observed"/"rejected", cuando este ítem pasó por un flujo de revisión; null en caso contrario. */
    String reviewStatus
) {
    public EvidenceItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(phenomenon, "phenomenon");
        if (finding == null && value == null) {
            throw new IllegalArgumentException("Un EvidenceItem necesita finding o value: no puede quedar completamente vacío.");
        }
    }
}
