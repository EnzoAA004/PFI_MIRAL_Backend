package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Stage 79: a deterministic, on-demand (never persisted — see stage 79-J) comparison of AI
 * measurements between two Studies of the SAME patient. Purely descriptive: this type and the
 * service that builds it never emit a clinical judgment ("improved"/"worsened"/a diagnosis) —
 * only the raw arithmetic delta between two already-persisted, already-reviewed-if-applicable
 * values (stage 79-G).
 */
public record LongitudinalComparisonResultDto(
    String baselineCaseId,
    String currentCaseId,
    String baselineStudyId,
    String currentStudyId,
    String baselineAnalysisRunId,
    String currentAnalysisRunId,
    LocalDate baselineStudyDate,
    LocalDate currentStudyDate,
    Instant generatedAt,
    List<MeasurementComparison> measurements
) {

    /**
     * One field of one measurement, matched by an exact
     * {@code (level, plane, structure, metric)} key (stage 79-E) — never a looser match, and
     * never two {@code level=null} entries paired with each other (stage 79-E "level null").
     */
    public record MeasurementComparison(
        String level,
        String plane,
        String structure,
        String metric,
        String unit,
        EffectiveValue baseline,
        EffectiveValue current,
        Double deltaAbsolute,
        Double deltaPercent,
        /** "increased" | "decreased" | "unchanged" | null — purely mathematical (stage 79-G), never clinical. */
        String trend,
        /** "comparable" | "missing_baseline" | "missing_current" | "level_unresolved" | "unit_mismatch" (stage 79-Q). */
        String comparisonStatus
    ) {
    }

    /**
     * Stage 78's correction always takes priority over the AI's own value when one exists
     * (stage 79-B/79-N) — but the AI's original value is never omitted, even when a correction
     * is in effect.
     */
    public record EffectiveValue(
        Double automaticValue,
        Double correctedValue,
        Double effectiveValue,
        /** "ai_original" | "professional_correction". */
        String source
    ) {
    }
}
