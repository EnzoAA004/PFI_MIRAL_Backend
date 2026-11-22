package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Etapa 79: una comparación determinística y a demanda (nunca persistida — ver etapa 79-J) de las mediciones de la IA
 * entre dos Studies del MISMO paciente. Puramente descriptiva: este tipo y el
 * servicio que lo arma nunca emiten un juicio clínico ("mejoró"/"empeoró"/un diagnóstico) —
 * solo el delta aritmético crudo entre dos valores ya persistidos y, si corresponde, ya revisados
 * (etapa 79-G).
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
     * Un campo de una medición, emparejado por una clave exacta
     * {@code (level, plane, structure, metric)} (etapa 79-E) — nunca una coincidencia más laxa, y
     * nunca dos entradas con {@code level=null} emparejadas entre sí (etapa 79-E "level null").
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
        /** "increased" | "decreased" | "unchanged" | null — puramente matemático (etapa 79-G), nunca clínico. */
        String trend,
        /** "comparable" | "missing_baseline" | "missing_current" | "level_unresolved" | "unit_mismatch" (etapa 79-Q). */
        String comparisonStatus
    ) {
    }

    /**
     * La corrección de la etapa 78 siempre tiene prioridad sobre el valor propio de la IA cuando existe una
     * (etapa 79-B/79-N) — pero el valor original de la IA nunca se omite, ni siquiera cuando hay una corrección
     * vigente.
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
