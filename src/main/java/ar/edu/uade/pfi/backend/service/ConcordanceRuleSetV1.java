package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.EvidenceItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * EXT-01: "ConcordanceRuleSet v1" — versionado a propósito (una futura v2 es una clase nueva, nunca un
 * cambio de comportamiento silencioso en esta, de modo que un {@link ConcordanceResult} calculado previamente siga
 * siendo interpretable por la ruleVersion que registró). Agrupa {@link EvidenceItem}s que son
 * genuinamente comparables (mismo {@code phenomenon}, mismo {@code level}, mismo {@code side} cuando
 * alguno de los lados declara uno) y clasifica cada grupo. Nunca infiere un diagnóstico: la salida es
 * siempre {@code state + reasonCode + comparedEvidence + ruleVersion}, nada más.
 *
 * <p>Manejo de severidad ordinal: para el único contrato ordinal real que tiene este producto
 * ({@code normal_mild < moderate < severe}, de {@code DegenerativeFindingSeverityV1}), un
 * desacuerdo de un paso es {@code PARTIALLY_CONCORDANT} ("consistencia informacional", según la
 * instrucción explícita de nunca tratar una diferencia ordinal como una regla clínica) y un desacuerdo
 * de dos pasos es {@code DISCREPANT}. Un string {@code finding} fuera de la escala ordinal conocida se
 * compara solo por igualdad exacta (sin orden inventado).
 */
@Service
public class ConcordanceRuleSetV1 {
    public static final String VERSION = "v1";

    private static final Map<String, Integer> SEVERITY_ORDER = Map.of(
        "normal_mild", 0,
        "moderate", 1,
        "severe", 2
    );

    public List<ConcordanceResult> evaluate(List<EvidenceItem> items) {
        Map<String, List<EvidenceItem>> groups = new LinkedHashMap<>();
        for (EvidenceItem item : items) {
            String key = String.join("|", item.phenomenon(), nullSafe(item.level()), nullSafe(item.side()));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        List<ConcordanceResult> results = new ArrayList<>();
        for (List<EvidenceItem> group : groups.values()) {
            results.add(evaluateGroup(group));
        }
        return results;
    }

    private ConcordanceResult evaluateGroup(List<EvidenceItem> group) {
        List<String> ids = group.stream().map(EvidenceItem::id).toList();
        if (group.size() < 2) {
            return new ConcordanceResult("INSUFFICIENT_EVIDENCE", "SINGLE_SOURCE_ONLY", ids, VERSION);
        }

        // Un grupo que mezcla un hallazgo categórico con un valor numérico suelto bajo la misma
        // clave fenómeno/nivel/lado es una discrepancia estructural, no una falta de datos — dos
        // TIPOS distintos de evidencia se normalizaron bajo una misma etiqueta, y forzar un veredicto entre "3.2"
        // y "moderate" inventaría una comparación que nunca fue real. NOT_COMPARABLE, no
        // INSUFFICIENT_EVIDENCE (que se reserva para "todavía no hay suficiente del MISMO tipo").
        boolean allCategorical = group.stream().allMatch(item -> item.finding() != null);
        boolean allNumeric = group.stream().allMatch(item -> item.value() != null && item.finding() == null);
        if (!allCategorical && !allNumeric) {
            return new ConcordanceResult("NOT_COMPARABLE", "MIXED_CATEGORICAL_AND_NUMERIC_EVIDENCE", ids, VERSION);
        }
        if (allNumeric) {
            // v1 no define una regla de concordancia numérica (todavía no existe una banda de tolerancia acordada en
            // este producto) -- se nombra explícitamente en lugar de inventar un umbral.
            return new ConcordanceResult("INSUFFICIENT_EVIDENCE", "NUMERIC_CONCORDANCE_RULE_NOT_DEFINED_IN_V1", ids, VERSION);
        }

        List<String> findings = group.stream().map(EvidenceItem::finding).distinct().toList();
        if (findings.size() == 1) {
            return new ConcordanceResult("CONCORDANT", "SAME_FINDING", ids, VERSION);
        }

        boolean allOrdinal = group.stream().allMatch(item -> SEVERITY_ORDER.containsKey(item.finding()));
        if (!allOrdinal) {
            return new ConcordanceResult("DISCREPANT", "DIFFERENT_NON_ORDINAL_FINDINGS", ids, VERSION);
        }

        int min = group.stream().mapToInt(item -> SEVERITY_ORDER.get(item.finding())).min().orElseThrow();
        int max = group.stream().mapToInt(item -> SEVERITY_ORDER.get(item.finding())).max().orElseThrow();
        int spread = max - min;
        if (spread == 1) {
            return new ConcordanceResult("PARTIALLY_CONCORDANT", "ADJACENT_SEVERITY_STEP", ids, VERSION);
        }
        return new ConcordanceResult("DISCREPANT", "SEVERITY_SPREAD_TWO_OR_MORE_STEPS", ids, VERSION);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
