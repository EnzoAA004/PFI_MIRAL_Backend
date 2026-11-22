package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.AiMeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Etapa 84: extraído, sin cambios textuales, de {@link LongitudinalComparisonService} (Etapa 79)
 * para que una nueva serie de mediciones de N Studies (Etapa 84-L: "reutilizar exactamente la key de Stage 79 --
 * NO duplicar lógica") y la comparación de dos Studies puedan compartir exactamente la misma resolución
 * de valor efectivo y la misma lógica de clave de emparejamiento, en lugar de que cada una tenga su propia copia.
 *
 * <p>Esto es un refactor puro de extraer método/clase: la propia suite {@code LongitudinalComparisonServiceTest}
 * de {@link LongitudinalComparisonService} no cambia y sigue ejercitando el mismo
 * comportamiento a través de {@code compare(...)}.
 */
@Component
public class EffectiveMeasurementResolver {
    private static final List<String> MEASUREMENT_FIELDS = List.of("areaMm2", "majorAxisMm", "minorAxisMm");

    private final StudyRepository repository;

    public EffectiveMeasurementResolver(StudyRepository repository) {
        this.repository = repository;
    }

    /** Un campo de una medición, resuelto a su valor efectivo (el corregido si lo hay, si no el de la IA). */
    public record EffectiveMeasurement(
        String level,
        String plane,
        String structure,
        String metric,
        String unit,
        Double automaticValue,
        Double correctedValue,
        Double effectiveValue,
        String source
    ) {
    }

    /**
     * Etapa 79-E / 84-L: la clave de emparejamiento exacta -- {@code (level, plane, structure, metric)}.
     * {@code unit} se excluye deliberadamente de la clave misma (es fija por {@code metric} del lado
     * del servidor, ver {@link #unitFor}, así que nunca puede discrepar dentro de una misma clave) pero igual se
     * lleva en {@link EffectiveMeasurement} para mostrar.
     */
    public record MatchKey(String level, String plane, String structure, String metric) {
    }

    /**
     * Etapa 79-B/79-O: para cada campo de medición, la última corrección (por {@code createdAt})
     * para ese par exacto {@code measurementId}+{@code field} gana -- las correcciones nunca se
     * suman, y el valor propio de la IA nunca se sobrescribe (se lee fresco de
     * {@link AiMeasurementResult} cada vez, independientemente de cualquier corrección).
     */
    public List<EffectiveMeasurement> effectiveMeasurements(AiProductAnalysisRun run) {
        List<AiMeasurementResult> measurements = repository.findAiMeasurementResultsByAnalysisRunId(run.id());
        Map<String, AiMeasurementCorrection> latestCorrections = latestCorrectionsByMeasurementAndField(
            repository.findAiMeasurementCorrectionsByAnalysisRunId(run.id())
        );

        List<EffectiveMeasurement> result = new ArrayList<>();
        for (AiMeasurementResult measurement : measurements) {
            for (String field : MEASUREMENT_FIELDS) {
                Double automatic = valueFor(measurement, field);
                AiMeasurementCorrection correction = latestCorrections.get(measurement.id() + "|" + field);
                if (automatic == null && correction == null) continue; // nada que informar para este campo
                Double corrected = correction == null ? null : correction.correctedValue();
                Double effective = corrected != null ? corrected : automatic;
                String source = corrected != null ? "professional_correction" : "ai_original";
                result.add(new EffectiveMeasurement(
                    measurement.level(), measurement.plane(), measurement.structure(), field, unitFor(field),
                    automatic, corrected, effective, source
                ));
            }
        }
        return result;
    }

    /** Etapa 79-E / 84-K: una medición con {@code level=null} nunca tiene un MatchKey válido -- quienes llaman deben chequear primero. */
    public MatchKey matchKey(EffectiveMeasurement measurement) {
        return new MatchKey(measurement.level(), measurement.plane(), measurement.structure(), measurement.metric());
    }

    private Map<String, AiMeasurementCorrection> latestCorrectionsByMeasurementAndField(List<AiMeasurementCorrection> corrections) {
        Map<String, AiMeasurementCorrection> latest = new HashMap<>();
        for (AiMeasurementCorrection correction : corrections) {
            String key = correction.measurementId() + "|" + correction.field();
            AiMeasurementCorrection existing = latest.get(key);
            if (existing == null || correction.createdAt().isAfter(existing.createdAt())) {
                latest.put(key, correction);
            }
        }
        return latest;
    }

    private Double valueFor(AiMeasurementResult measurement, String field) {
        return switch (field) {
            case "areaMm2" -> measurement.areaMm2();
            case "majorAxisMm" -> measurement.majorAxisMm();
            case "minorAxisMm" -> measurement.minorAxisMm();
            default -> throw new IllegalStateException("unreachable: unknown measurement field " + field);
        };
    }

    /** Fijo por campo, igual que el `unitFor` de la etapa 78 -- nunca derivado de ningún input externo. */
    private String unitFor(String field) {
        return "areaMm2".equals(field) ? "mm2" : "mm";
    }
}
