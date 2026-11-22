package ar.edu.uade.pfi.backend.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Etapa 84-I: el valor de una medición a lo largo de N Studies del mismo paciente (identificado por
 * {@code subjectRef}). Reutiliza la clave de emparejamiento exacta y la semántica de valor efectivo de la Etapa 79
 * ({@link ar.edu.uade.pfi.backend.service.EffectiveMeasurementResolver}) — es una
 * agregación determinística sobre datos ya persistidos, nunca un nuevo cómputo de IA/ML y nunca
 * un juicio de tendencia clínica (84-N: sin "improved"/"worsened"/"progression").
 */
public record MeasurementSeriesDto(MeasurementSeriesKeyDto key, List<Point> points) {

    /** {@code (level, plane, structure, metric)} — la clave de emparejamiento exacta de la Etapa 79, más {@code unit} para mostrar. */
    public record MeasurementSeriesKeyDto(String level, String plane, String structure, String metric, String unit) {
    }

    /** El punto de un Study en la serie — el valor original de la IA siempre está presente, aun cuando exista una corrección. */
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
