package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.dto.InitialAnalysisComparisonDto;
import ar.edu.uade.pfi.backend.service.InitialAnalysisMeasurementReader.Measurement;
import ar.edu.uade.pfi.backend.service.InitialAnalysisMeasurementReader.RunContext;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Qué mediciones del análisis inicial se pueden comparar entre dos estudios, y cómo.
 *
 * <p><b>Equivalencia.</b> Un identificador igual no prueba nada: el análisis inicial arma el id de un disco
 * como {@code sagittal-disc-<nivel>-<métrica>} pero el de una vértebra es posicional
 * ({@code sagittal-vertebra-v3}) y el nivel viaja aparte (ver {@code build_measurements} en el módulo de
 * IA). Por eso la clave es la tupla {@code (nivel, plano, estructura, métrica)} que sale de la
 * {@code labelKey} canónica y del nivel que asignó la corrida, nunca el id ni la posición en una lista.
 *
 * <p>Solo se comparan las definiciones de {@link #DEFINITIONS}: cada una se leyó en el código que las genera
 * y mide la misma cosa en cualquier estudio (la máscara de esa estructura, en el único corte inferido).
 * Todo lo demás (clases axiales {@code raw_*}, el área de todo el canal, clases sin nivel) se excluye con su
 * motivo y no se supone equivalente.
 *
 * <p><b>Ausencias.</b> Una medición que falta en un lado se informa como ausencia; nunca se convierte a
 * cero ni se promedia con otra.
 */
public final class InitialMeasurementMatcher {
    private InitialMeasurementMatcher() {
    }

    /** Magnitud física de la métrica, para exigir la unidad esperada. */
    enum Quantity {
        LENGTH("mm"),
        AREA("mm2"),
        ANGLE("deg");

        private final String unit;

        Quantity(String unit) {
            this.unit = unit;
        }
    }

    record Definition(String structure, String metric, Quantity quantity) {
    }

    /** {@code labelKey} canónica (minúsculas) → definición demostrada en el código que genera la medición. */
    static final Map<String, Definition> DEFINITIONS = Map.of(
        "disc height", new Definition("disc", "height", Quantity.LENGTH),
        "disc width", new Definition("disc", "width", Quantity.LENGTH),
        "disc area", new Definition("disc", "area", Quantity.AREA),
        "vertebra height", new Definition("vertebra", "height", Quantity.LENGTH),
        "vertebra width", new Definition("vertebra", "width", Quantity.LENGTH),
        "vertebra area", new Definition("vertebra", "area", Quantity.AREA),
        "canal ap", new Definition("canal", "ap_diameter", Quantity.LENGTH),
        "segmental angle", new Definition("disc", "segmental_angle", Quantity.ANGLE)
    );

    private static final List<String> ANATOMICAL_ORDER = List.of(
        "T9", "T9-T10", "T10", "T10-T11", "T11", "T11-T12", "T12", "T12-L1",
        "L1", "L1-L2", "L2", "L2-L3", "L3", "L3-L4", "L4", "L4-L5", "L5", "L5-S1", "S1"
    );
    private static final List<String> METRIC_ORDER = List.of("height", "width", "area", "ap_diameter", "segmental_angle");
    private static final List<String> STRUCTURE_ORDER = List.of("disc", "vertebra", "canal");

    record Key(String level, String plane, String structure, String metric) {
    }

    /** Medición que pasó las comprobaciones propias (sin mirar al otro estudio). */
    record Eligible(Measurement measurement, Definition definition, Key key) {
    }

    record Eligibility(Optional<InitialAnalysisComparisonDto.Exclusion> blocked, Eligible eligible) {
    }

    private static InitialAnalysisComparisonDto.Exclusion exclusion(String side, Measurement m, String code, String reason) {
        return new InitialAnalysisComparisonDto.Exclusion(side, m.level(), m.plane(), m.labelKey(), List.of(m.id()), code, reason);
    }

    static Eligibility check(String side, Measurement m) {
        if (m.invalidReason() != null) return blocked(exclusion(side, m, "invalid_value", m.invalidReason()));
        if ("axial".equals(m.plane())) {
            return blocked(exclusion(side, m, "axial_unmapped",
                "Plano axial: sus clases (raw_*) no tienen una estructura anatómica asignada, así que no se puede demostrar que dos de ellas midan lo mismo."));
        }
        Definition definition = m.labelKey() == null ? null : DEFINITIONS.get(m.labelKey().trim().toLowerCase(Locale.ROOT));
        if (definition == null) {
            String reason = "study".equals(m.levelScope())
                ? "Describe toda la máscara y no un nivel (por ejemplo, el área completa del canal): depende de cuánta columna abarque el encuadre."
                : "Sin una definición anatómica equivalente demostrada entre estudios (" + (m.labelKey() == null ? "sin nombre" : m.labelKey()) + ").";
            return blocked(exclusion(side, m, "no_equivalent_definition", reason));
        }
        if (m.level() == null || !"level".equals(m.levelScope())) {
            return blocked(exclusion(side, m, "no_level",
                "Sin nivel anatómico asignado por el análisis inicial (no pudo numerar los discos). No se asigna uno por posición."));
        }
        Key key = new Key(m.level(), m.plane(), definition.structure(), definition.metric());
        return new Eligibility(Optional.empty(), new Eligible(m, definition, key));
    }

    /** Por qué una medición, aun apareada, no se puede usar como valor físico: píxeles, base o unidad ajenas a la métrica. */
    static Mismatch calibrationProblem(Eligible eligible) {
        Measurement m = eligible.measurement();
        if ("pixel_space".equals(m.basis()) || m.unit().startsWith("px")) {
            return new Mismatch("pixel_space", "Medida en píxeles, sin calibración física: dos estudios con distinta resolución no son comparables.");
        }
        if (!m.basis().isEmpty() && !"physical_spacing".equals(m.basis())) {
            return new Mismatch("unsupported_basis", "Base de medición no reconocida (" + m.basis() + ").");
        }
        if (!eligible.definition().quantity().unit.equals(m.unit())) {
            return new Mismatch("unit_unexpected",
                "La unidad (" + m.unit() + ") no es la que define esta métrica (" + eligible.definition().quantity().unit + ").");
        }
        return null;
    }

    private static Eligibility blocked(InitialAnalysisComparisonDto.Exclusion exclusion) {
        return new Eligibility(Optional.of(exclusion), null);
    }

    /** Cuántas mediciones del estudio son utilizables: pasan las comprobaciones y su clave no está duplicada. */
    public static int usableCount(List<Measurement> measurements) {
        Map<Key, Integer> counts = new LinkedHashMap<>();
        for (Measurement m : measurements) {
            Eligibility result = check("current", m);
            if (result.eligible() != null && calibrationProblem(result.eligible()) == null) {
                counts.merge(result.eligible().key(), 1, Integer::sum);
            }
        }
        return (int) counts.values().stream().filter((count) -> count == 1).count();
    }

    /** Resultado de aparear los dos estudios. */
    public record Result(List<InitialAnalysisComparisonDto.Row> rows, List<InitialAnalysisComparisonDto.Exclusion> excluded) {
    }

    /** Por qué dos corridas no son comparables entre sí (modelo, secuencia, etc.), o {@code null}. */
    static String runMismatch(RunContext current, RunContext reference) {
        if (current.syntheticSagittal() || reference.syntheticSagittal()) {
            return "Una de las corridas es sintética: no corresponde a una inferencia real.";
        }
        if (!current.modelKey().equals(reference.modelKey())) {
            return "Modelos distintos (" + dash(reference.modelKey()) + " en la referencia, " + dash(current.modelKey()) + " en el estudio abierto).";
        }
        if (!current.artifactHash().equals(reference.artifactHash())) {
            return "El modelo tiene versiones distintas (checkpoints diferentes): la diferencia podría deberse al modelo y no al estudio.";
        }
        if (!current.effectiveInferenceMode().equals(reference.effectiveInferenceMode())) {
            return "Modos de inferencia distintos (" + dash(reference.effectiveInferenceMode()) + " y " + dash(current.effectiveInferenceMode()) + ").";
        }
        if (current.sagittalSequence().isEmpty() || reference.sagittalSequence().isEmpty()) {
            return "La secuencia de la serie sagital no está informada en uno de los estudios: no se puede demostrar que se midió sobre la misma.";
        }
        if (!current.sagittalSequence().equals(reference.sagittalSequence())) {
            return "Secuencias distintas (" + reference.sagittalSequence().toUpperCase(Locale.ROOT) + " en la referencia, "
                + current.sagittalSequence().toUpperCase(Locale.ROOT) + " en el estudio abierto).";
        }
        return null;
    }

    private static String dash(String value) {
        return value.isEmpty() ? "sin informar" : value;
    }

    public static Result match(
        List<Measurement> currentMeasurements,
        RunContext currentContext,
        List<Measurement> referenceMeasurements,
        RunContext referenceContext
    ) {
        List<InitialAnalysisComparisonDto.Exclusion> excluded = new ArrayList<>();
        Map<Key, List<Eligible>> current = group("current", currentMeasurements, excluded);
        Map<Key, List<Eligible>> reference = group("reference", referenceMeasurements, excluded);
        String runMismatch = runMismatch(currentContext, referenceContext);

        List<Key> keys = new ArrayList<>(current.keySet());
        for (Key key : reference.keySet()) if (!current.containsKey(key)) keys.add(key);
        keys.sort(KEY_ORDER);

        List<InitialAnalysisComparisonDto.Row> rows = new ArrayList<>();
        for (Key key : keys) {
            List<Eligible> currentList = current.getOrDefault(key, List.of());
            List<Eligible> referenceList = reference.getOrDefault(key, List.of());
            boolean ambiguous = false;
            if (currentList.size() > 1) {
                excluded.add(ambiguous("current", key, currentList));
                ambiguous = true;
            }
            if (referenceList.size() > 1) {
                excluded.add(ambiguous("reference", key, referenceList));
                ambiguous = true;
            }
            if (ambiguous) continue;
            if (currentList.isEmpty() || referenceList.isEmpty()) {
                Eligible only = currentList.isEmpty() ? referenceList.get(0) : currentList.get(0);
                String side = currentList.isEmpty() ? "reference" : "current";
                String otherName = currentList.isEmpty() ? "estudio abierto" : "referencia";
                excluded.add(exclusion(side, only.measurement(), "missing_in_other",
                    "Sin equivalente en el " + otherName + ": la ausencia no se cuenta como cero."));
                continue;
            }
            Eligible cur = currentList.get(0);
            Eligible ref = referenceList.get(0);
            Mismatch mismatch = pairMismatch(cur, ref, runMismatch);
            if (mismatch != null) {
                excluded.add(new InitialAnalysisComparisonDto.Exclusion(
                    "both", key.level(), key.plane(), cur.measurement().labelKey(),
                    List.of(ref.measurement().id(), cur.measurement().id()), mismatch.code(), mismatch.reason()));
                continue;
            }
            rows.add(row(key, cur, ref));
        }
        return new Result(List.copyOf(rows), List.copyOf(excluded));
    }

    record Mismatch(String code, String reason) {
    }

    private static Mismatch pairMismatch(Eligible currentItem, Eligible referenceItem, String runMismatch) {
        Measurement current = currentItem.measurement();
        Measurement reference = referenceItem.measurement();
        if (!current.unit().equals(reference.unit())) {
            return new Mismatch("unit_mismatch",
                "Unidades incompatibles (" + reference.unit() + " en la referencia, " + current.unit() + " en el estudio abierto).");
        }
        Mismatch calibration = calibrationProblem(currentItem);
        if (calibration == null) calibration = calibrationProblem(referenceItem);
        if (calibration != null) return calibration;
        if (!current.basis().equals(reference.basis())) {
            return new Mismatch("method_mismatch",
                "Base de medición distinta (" + (reference.basis().isEmpty() ? "sin informar" : reference.basis()) + " y "
                    + (current.basis().isEmpty() ? "sin informar" : current.basis()) + ").");
        }
        if (current.experimental() != reference.experimental()) {
            return new Mismatch("method_mismatch", "Método distinto: una es una medida derivada experimental y la otra no.");
        }
        return runMismatch == null ? null : new Mismatch("method_mismatch", runMismatch);
    }

    private static Map<Key, List<Eligible>> group(String side, List<Measurement> measurements, List<InitialAnalysisComparisonDto.Exclusion> excluded) {
        Map<Key, List<Eligible>> grouped = new LinkedHashMap<>();
        for (Measurement m : measurements) {
            Eligibility result = check(side, m);
            if (result.blocked().isPresent()) {
                excluded.add(result.blocked().get());
            } else {
                grouped.computeIfAbsent(result.eligible().key(), (k) -> new ArrayList<>()).add(result.eligible());
            }
        }
        return grouped;
    }

    private static InitialAnalysisComparisonDto.Exclusion ambiguous(String side, Key key, List<Eligible> items) {
        List<String> ids = items.stream().map((item) -> item.measurement().id()).toList();
        return new InitialAnalysisComparisonDto.Exclusion(
            side, key.level(), key.plane(), items.get(0).measurement().labelKey(), ids, "ambiguous_duplicate",
            items.size() + " mediciones equivalentes en el mismo estudio (" + String.join(", ", ids)
                + "): la correspondencia es ambigua y no se promedian ni se elige una.");
    }

    private static InitialAnalysisComparisonDto.Row row(Key key, Eligible current, Eligible reference) {
        Measurement c = current.measurement();
        Measurement r = reference.measurement();
        BigDecimal currentValue = BigDecimal.valueOf(c.effectiveValue());
        BigDecimal referenceValue = BigDecimal.valueOf(r.effectiveValue());
        BigDecimal delta = currentValue.subtract(referenceValue);
        Double percent = null;
        String percentStatus = InitialAnalysisComparisonDto.Row.PERCENT_REFERENCE_ZERO;
        if (referenceValue.signum() != 0) {
            percent = delta.divide(referenceValue.abs(), MathContext.DECIMAL128).multiply(BigDecimal.valueOf(100)).doubleValue();
            percentStatus = InitialAnalysisComparisonDto.Row.PERCENT_OK;
        }
        int sign = delta.signum();
        return new InitialAnalysisComparisonDto.Row(
            key.level(), key.structure(), key.metric(), key.plane(), c.unit(), c.experimental(),
            value(r), value(c), delta.doubleValue(), percent, percentStatus,
            sign == 0 ? "unchanged" : sign > 0 ? "increased" : "decreased"
        );
    }

    private static InitialAnalysisComparisonDto.Value value(Measurement m) {
        return new InitialAnalysisComparisonDto.Value(
            m.id(), m.effectiveValue(), m.originalValue(), m.correctedValue(), m.corrected(), m.sliceIndex());
    }

    private static final Comparator<Key> KEY_ORDER = Comparator
        .comparingInt((Key key) -> indexOf(ANATOMICAL_ORDER, key.level()))
        .thenComparingInt((Key key) -> indexOf(STRUCTURE_ORDER, key.structure()))
        .thenComparingInt((Key key) -> indexOf(METRIC_ORDER, key.metric()))
        .thenComparing(Key::level)
        .thenComparing(Key::plane);

    private static int indexOf(List<String> order, String value) {
        int index = order.indexOf(value);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    /** Para pruebas y para el listado de candidatos. */
    static Set<String> definitionKeys() {
        return DEFINITIONS.keySet();
    }
}
