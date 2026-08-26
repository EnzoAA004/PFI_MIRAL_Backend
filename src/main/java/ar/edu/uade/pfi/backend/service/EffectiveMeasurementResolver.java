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
 * Stage 84: extracted, verbatim-unchanged, from {@link LongitudinalComparisonService} (Stage 79)
 * so a new N-Study measurement series (Stage 84-L: "reutilizar exactamente la key de Stage 79 --
 * NO duplicar lógica") and the two-Study comparison can share the exact same effective-value
 * resolution and matching-key logic, instead of each having its own copy.
 *
 * <p>This is a pure extract-method/class refactor: {@link LongitudinalComparisonService}'s own
 * {@code LongitudinalComparisonServiceTest} suite is unchanged and still exercises the identical
 * behavior through {@code compare(...)}.
 */
@Component
public class EffectiveMeasurementResolver {
    private static final List<String> MEASUREMENT_FIELDS = List.of("areaMm2", "majorAxisMm", "minorAxisMm");

    private final StudyRepository repository;

    public EffectiveMeasurementResolver(StudyRepository repository) {
        this.repository = repository;
    }

    /** One field of one measurement, resolved to its effective (corrected-if-any, else AI) value. */
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
     * Stage 79-E / 84-L: the exact matching key -- {@code (level, plane, structure, metric)}.
     * {@code unit} is deliberately excluded from the key itself (it is fixed per {@code metric}
     * server-side, see {@link #unitFor}, so it can never disagree within one key) but is still
     * carried on {@link EffectiveMeasurement} for display.
     */
    public record MatchKey(String level, String plane, String structure, String metric) {
    }

    /**
     * Stage 79-B/79-O: for each measurement field, the latest correction (by {@code createdAt})
     * for that exact {@code measurementId}+{@code field} pair wins -- corrections are never
     * summed, and the AI's own value is never overwritten (it is read fresh from
     * {@link AiMeasurementResult} every time, independent of any correction).
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
                if (automatic == null && correction == null) continue; // nothing to report for this field
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

    /** Stage 79-E / 84-K: a {@code level=null} measurement never has a valid MatchKey -- callers must check first. */
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

    /** Fixed per field, matching stage 78's own `unitFor` -- never derived from any external input. */
    private String unitFor(String field) {
        return "areaMm2".equals(field) ? "mm2" : "mm";
    }
}
