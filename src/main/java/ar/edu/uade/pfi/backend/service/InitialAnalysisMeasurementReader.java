package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.InputSequence;
import ar.edu.uade.pfi.backend.dto.InitialAnalysisComparisonDto;
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
        /** Ponderación de la serie sagital y su procedencia (declarada, detectada o desconocida). */
        InputSequence sequence,
        boolean syntheticSagittal
    ) {
        /** {@code t1}, {@code t2}, {@code other} o vacío si el estudio no lo informa. */
        public String sagittalSequence() {
            return sequence.isKnown() ? sequence.value() : "";
        }
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
        String invalidReason,
        /** Dónde está en la imagen, tal como quedó persistido; nunca deducido. */
        InitialAnalysisComparisonDto.Location location
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
        InputSequence snapshotSequence = null;
        for (String plane : List.of("sagittal", "axial")) {
            Map<String, Object> planeNode = asMap(planes.get(plane));
            if (plane.equals("sagittal")) {
                syntheticSagittal = Boolean.TRUE.equals(planeNode.get("synthetic"));
                Map<String, Object> input = asMap(planeNode.get("input"));
                sagittalInputId = text(input.get("inputId"));
                // La corrida es la fuente de la secuencia con que se midió. Si la publicó (aun como
                // desconocida) se respeta; si no (corridas anteriores) se cae a lo registrado del estudio.
                if (input.get("sequence") instanceof Map<?, ?> published) snapshotSequence = InputSequence.fromMap(published);
            }
            Object list = planeNode.get("measurements");
            if (!(list instanceof List<?> items)) continue;
            PlaneFrame frame = PlaneFrame.of(plane, planeNode);
            for (Object item : items) {
                if (item instanceof Map<?, ?> raw) measurements.add(toMeasurement(plane, asMap(raw), corrections, frame));
            }
        }
        RunContext context = new RunContext(
            blankToEmpty(run.sagittalModelKey()),
            blankToEmpty(run.sagittalArtifactHash()),
            blankToEmpty(run.effectiveInferenceMode()),
            snapshotSequence != null ? snapshotSequence : sequenceOf(study, sagittalInputId),
            syntheticSagittal
        );
        return new Reading(run, context, List.copyOf(measurements));
    }

    private Measurement toMeasurement(String plane, Map<String, Object> raw, Map<String, MeasurementCorrection> corrections, PlaneFrame frame) {
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
            id == null ? "La medición no trae identificador." : invalid,
            locationOf(raw, correction, frame, slice instanceof Number sliceNumber ? sliceNumber.intValue() : null)
        );
    }

    /** Lo que la corrida declara del plano y que hace falta para ubicar un trazo sobre su imagen. */
    private record PlaneFrame(String plane, String planeRunId, String inputId, String space, Integer width, Integer height, Integer sourceSliceIndex, boolean pixelUnits) {
        static PlaneFrame of(String plane, Map<String, Object> planeNode) {
            Map<String, Object> space = asMap(planeNode.get("coordinateSpace"));
            Double width = number(space.get("width"));
            Double height = number(space.get("height"));
            Double source = number(space.get("sourceSliceIndex"));
            return new PlaneFrame(
                plane,
                text(planeNode.get("planeRunId")),
                text(asMap(planeNode.get("input")).get("inputId")),
                text(space.get("name")),
                width == null ? null : width.intValue(),
                height == null ? null : height.intValue(),
                source == null ? null : source.intValue(),
                "pixel".equals(text(space.get("units")))
            );
        }
    }

    /**
     * Ubicación persistida de la medición. El trazo se acepta solo si la corrida declara un espacio de
     * coordenadas en píxeles cuyo corte de origen es el de la medición: sin eso no hay forma de saber sobre qué
     * imagen está. Un trazo no se estira ni se reemplaza para que coincida con un valor corregido.
     */
    private static InitialAnalysisComparisonDto.Location locationOf(
        Map<String, Object> raw, MeasurementCorrection correction, PlaneFrame frame, Integer sliceIndex
    ) {
        List<InitialAnalysisComparisonDto.Point> own = points(raw.get("points"));
        String kind = own.size() == 2 ? "segment" : own.size() == 4 ? "angle" : "none";
        String status;
        String reason = null;
        if (sliceIndex == null) {
            status = InitialAnalysisComparisonDto.Location.NOT_LOCATED;
            reason = "La corrida no guardó el corte de esta medición.";
        } else if (kind.equals("none")) {
            status = InitialAnalysisComparisonDto.Location.SLICE_ONLY;
            reason = "La medición no tiene un trazo guardado (por ejemplo, las áreas): se conoce el corte, no el trazo.";
        } else if (frame.space() == null || !frame.pixelUnits() || frame.width() == null || frame.height() == null) {
            status = InitialAnalysisComparisonDto.Location.SLICE_ONLY;
            kind = "none";
            reason = "La corrida no declara el espacio de coordenadas del trazo, así que no se puede ubicar sobre la imagen.";
        } else if (frame.sourceSliceIndex() != null && !frame.sourceSliceIndex().equals(sliceIndex)) {
            status = InitialAnalysisComparisonDto.Location.SLICE_ONLY;
            kind = "none";
            reason = "El trazo está en el espacio de otro corte (" + frame.sourceSliceIndex() + ") y no se puede ubicar en el corte " + sliceIndex + ".";
        } else {
            status = InitialAnalysisComparisonDto.Location.LOCATED;
        }
        boolean located = status.equals(InitialAnalysisComparisonDto.Location.LOCATED);
        List<InitialAnalysisComparisonDto.Point> corrected = List.of();
        if (located && correction != null) {
            List<InitialAnalysisComparisonDto.Point> saved = points(correction.afterValue().get("points"));
            if (saved.size() == own.size() && !saved.equals(own)) corrected = saved;
        }
        return new InitialAnalysisComparisonDto.Location(
            frame.plane(), frame.planeRunId(), frame.inputId(), sliceIndex,
            frame.space(), frame.width(), frame.height(),
            kind, located ? own : List.of(), corrected, status, reason
        );
    }

    private static List<InitialAnalysisComparisonDto.Point> points(Object value) {
        if (!(value instanceof List<?> items)) return List.of();
        List<InitialAnalysisComparisonDto.Point> result = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> point = asMap(item);
            Double x = number(point.get("x"));
            Double y = number(point.get("y"));
            if (x == null || y == null) return List.of();
            result.add(new InitialAnalysisComparisonDto.Point(x, y));
        }
        return List.copyOf(result);
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

    private InputSequence sequenceOf(Study study, String sagittalInputId) {
        if (sagittalInputId == null) return InputSequence.unknown();
        for (InputResource input : repository.findInputsByStudyId(study.id())) {
            if (sagittalInputId.equals(input.inputId())) {
                return InputSequence.fromStored(input.weighting(), null, null);
            }
        }
        return InputSequence.unknown();
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
