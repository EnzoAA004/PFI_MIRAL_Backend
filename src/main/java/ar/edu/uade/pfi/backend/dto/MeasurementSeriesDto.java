package ar.edu.uade.pfi.backend.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Stage 84-I: one measurement's value across N Studies of the same patient (identified by
 * {@code subjectRef}). Reuses Stage 79's exact matching key and effective-value semantics
 * ({@link ar.edu.uade.pfi.backend.service.EffectiveMeasurementResolver}) — this is a
 * deterministic aggregation over already-persisted data, never a new AI/ML computation and never
 * a clinical trend judgment (84-N: no "improved"/"worsened"/"progression").
 */
public record MeasurementSeriesDto(MeasurementSeriesKeyDto key, List<Point> points) {

    /** {@code (level, plane, structure, metric)} — the exact Stage-79 matching key, plus {@code unit} for display. */
    public record MeasurementSeriesKeyDto(String level, String plane, String structure, String metric, String unit) {
    }

    /** One Study's point in the series — the AI's original value is always present, even when a correction exists. */
    public record Point(
        String caseId,
        LocalDate studyDate,
        Double automaticValue,
        Double correctedValue,
        Double effectiveValue,
        /** "ai_original" | "professional_correction". */
        String effectiveSource
    ) {
    }
}
