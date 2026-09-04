package ar.edu.uade.pfi.backend.domain;

import java.util.Objects;

/**
 * EXT-01: a normalized, comparable unit of AI/professional evidence — the contract every
 * concordance rule operates on, regardless of which real product surface produced it (a
 * disc-level batch classifier run, an interactive subarticular-ROI classification, a
 * measurement, or a professional review outcome). Deliberately NOT tied to any one persistence
 * table: the two real, independently-computed sources this stage's audit found comparable are
 * both instances of the same `DegenerativeFindingsV1Dto` wire contract (Backend
 * `DegenerativeFindingTypeV1`/`DegenerativeFindingSeverityV1`) invoked from two different contexts
 * (batch disc-level analysis vs. on-demand subarticular-ROI classification) — see
 * `docs/extensions/EXT_01_CONCORDANCE.md` "Overlap matrix" for what was and was not judged
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
    /** What this item is actually a classification/measurement OF — e.g. "subarticular_stenosis". Only items sharing the same phenomenon are ever compared. */
    String phenomenon,
    /** The categorical label, when the phenomenon is ordinal/categorical (e.g. "normal_mild"/"moderate"/"severe"). Null for a purely numeric item. */
    String finding,
    /** The numeric value, when the phenomenon is a measurement. Null for a purely categorical item. */
    Double value,
    /** Only when the source model genuinely emits one — never fabricated. */
    Double confidence,
    boolean experimental,
    /** "pending"/"accepted"/"observed"/"rejected", when this item has gone through a review flow; null otherwise. */
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
