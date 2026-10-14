package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Capa de lectura de las mediciones REALES del análisis inicial: las que la corrida multiplanar guarda en
 * {@code domain_study_runs.metrics_snapshot.planes.<plano>.measurements}, con las correcciones
 * profesionales persistidas ({@code domain_review_corrections}).
 *
 * <p>Solo lee. No ejecuta modelos, no escribe nada y no toca las tablas del circuito de producto
 * (demostración): las dos fuentes nunca se mezclan ni una sirve de reemplazo de la otra.
 *
 * <p>No decide equivalencias: entrega cada medición con todo lo que hace falta para decidirlas
 * ({@link InitialAnalysisComparisonService} y {@link InitialMeasurementMatcher}) y marca como
 * inválida la que no se puede leer como número, sin convertirla en cero.
 */
@Component
public class InitialAnalysisMeasurementReader {
    private final StudyRepository repository;

    public InitialAnalysisMeasurementReader(StudyRepository repository) {
        this.repository = repository;
    }

    /** Condiciones de la corrida que afectan si dos mediciones se pueden comparar. */
    public record RunContext(
        String modelKey,
        String artifactHash,
        String effectiveInferenceMode,
        /** {@code t1}, {@code t2} o vacío si el estudio no lo informa. */
        String sagittalSequence,
        boolean syntheticSagittal
    ) {
    }

    public record Measurement(
        String id,
        String plane,
        String labelKey,
        String level,
        String levelScope,
        String unit,
        String basis,
        boolean experimental,
        Integer sliceIndex,
        Double originalValue,
        Double effectiveValue,
        Double correctedValue,
        boolean corrected,
        /** No nulo cuando el valor no se puede usar: se informa, nunca se reemplaza por cero. */
        String invalidReason
    ) {
    }

    public record Reading(StudyRun run, RunContext context, List<Measurement> measurements) {
    }

    public Reading read(Study study, StudyRun run) {
        Map<String, Object> planes = asMap(run.metricsSnapshot().get("planes"));
        Map<String, MeasurementCorrection> corrections = latestCorrections(run);
        List<Measurement> measurements = new ArrayList<>();
        boolean syntheticSagittal = false;
        String sagittalInputId = null;
        for (String plane : List.of("sagittal", "axial")) {
            Map<String, Object> planeNode = asMap(planes.get(plane));
            if (plane.equals("sagittal")) {
                syntheticSagittal = Boolean.TRUE.equals(planeNode.get("synthetic"));
                sagittalInputId = text(asMap(planeNode.get("input")).get("inputId"));
            }
            Object list = planeNode.get("measurements");
            if (!(list instanceof List<?> items)) continue;
            for (Object item : items) {
                if (item instanceof Map<?, ?> raw) measurements.add(toMeasurement(plane, asMap(raw), corrections));
            }
        }
        RunContext context = new RunContext(
            blankToEmpty(run.sagittalModelKey()),
            blankToEmpty(run.sagittalArtifactHash()),
            blankToEmpty(run.effectiveInferenceMode()),
            sequenceOf(study, sagittalInputId),
            syntheticSagittal
        );
        return new Reading(run, context, List.copyOf(measurements));
    }

    private Measurement toMeasurement(String plane, Map<String, Object> raw, Map<String, MeasurementCorrection> corrections) {
        String id = text(raw.get("id"));
        String unit = blankToEmpty(text(raw.get("unit")));
        Double stored = number(raw.get("value"));
        Double original = number(raw.get("aiValue"));
        if (original == null) original = stored;
        String labelKey = text(raw.get("labelKey"));
        if (labelKey == null) labelKey = text(raw.get("label"));

        Double corrected = null;
        boolean hasCorrection = false;
        String invalid = null;
        MeasurementCorrection correction = id == null ? null : corrections.get(id);
        if (correction != null) {
            hasCorrection = true;
            Object afterValue = correction.afterValue().get("value");
            corrected = number(afterValue);
            String correctedUnit = text(correction.afterValue().get("unit"));
            if (corrected == null) invalid = "La corrección profesional guardada no es un número (" + afterValue + ").";
            else if (correctedUnit != null && !correctedUnit.equals(unit)) {
                invalid = "La corrección profesional está en otra unidad (" + correctedUnit + " en vez de " + unit + ").";
            }
        }
        Double effective = invalid == null && corrected != null ? corrected : original;
        if (invalid == null && effective == null) invalid = "La medición no trae un valor numérico.";
        Object slice = raw.get("sliceIndex");
        return new Measurement(
            id == null ? "(sin identificador)" : id,
            plane,
            labelKey,
            blankToNull(text(raw.get("level"))),
            blankToEmpty(text(raw.get("levelScope"))),
            unit,
            blankToEmpty(text(raw.get("measurementBasis"))),
            Boolean.TRUE.equals(raw.get("experimental")),
            slice instanceof Number sliceNumber ? sliceNumber.intValue() : null,
            original,
            effective,
            invalid == null ? corrected : null,
            invalid == null && hasCorrection,
            id == null ? "La medición no trae identificador." : invalid
        );
    }

    /** Si una medición tiene más de una corrección guardada, rige la más reciente. */
    private Map<String, MeasurementCorrection> latestCorrections(StudyRun run) {
        Map<String, MeasurementCorrection> latest = new HashMap<>();
        for (MeasurementCorrection correction : repository.findCorrectionsByStudyRunId(run.id())) {
            latest.merge(
                correction.measurementId(),
                correction,
                (left, right) -> Comparator.comparing(MeasurementCorrection::createdAt).compare(left, right) >= 0 ? left : right
            );
        }
        return latest;
    }

    private String sequenceOf(Study study, String sagittalInputId) {
        if (sagittalInputId == null) return "";
        for (InputResource input : repository.findInputsByStudyId(study.id())) {
            if (sagittalInputId.equals(input.inputId())) {
                String weighting = blankToEmpty(input.weighting()).toLowerCase(Locale.ROOT);
                return weighting.equals("unknown") ? "" : weighting;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** Número finito, o {@code null}: un valor ausente o ilegible nunca pasa a ser cero. */
    static Double number(Object value) {
        Double parsed = null;
        if (value instanceof Number number) parsed = number.doubleValue();
        else if (value instanceof String string && !string.isBlank()) {
            try {
                parsed = Double.parseDouble(string.trim().replace(',', '.'));
            } catch (NumberFormatException ignored) {
                parsed = null;
            }
        }
        return parsed != null && Double.isFinite(parsed) ? parsed : null;
    }
}
