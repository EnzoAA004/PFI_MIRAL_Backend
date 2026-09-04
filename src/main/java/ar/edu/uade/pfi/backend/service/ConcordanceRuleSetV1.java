package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.EvidenceItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * EXT-01: "ConcordanceRuleSet v1" — versioned on purpose (a future v2 is a new class, never a
 * silent behavior change on this one, so a previously-computed {@link ConcordanceResult} stays
 * interpretable by whatever ruleVersion it recorded). Groups {@link EvidenceItem}s that are
 * genuinely comparable (same {@code phenomenon}, same {@code level}, same {@code side} when
 * either side declares one) and classifies each group. Never infers a diagnosis: the output is
 * always {@code state + reasonCode + comparedEvidence + ruleVersion}, nothing else.
 *
 * <p>Ordinal severity handling: for the only real ordinal contract this product has
 * ({@code normal_mild < moderate < severe}, from {@code DegenerativeFindingSeverityV1}), a
 * one-step disagreement is {@code PARTIALLY_CONCORDANT} ("consistencia informacional", per the
 * explicit instruction to never treat an ordinal difference as a clinical rule) and a two-step
 * disagreement is {@code DISCREPANT}. A {@code finding} string outside the known ordinal scale is
 * compared by exact equality only (no invented ordering).
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

        // A group mixing a categorical finding with a bare numeric value under the same
        // phenomenon/level/side key is a structural mismatch, not a lack of data — two different
        // KINDS of evidence were normalized under one label, and forcing a verdict between "3.2"
        // and "moderate" would invent a comparison that was never real. NOT_COMPARABLE, not
        // INSUFFICIENT_EVIDENCE (which is reserved for "not enough of the SAME kind yet").
        boolean allCategorical = group.stream().allMatch(item -> item.finding() != null);
        boolean allNumeric = group.stream().allMatch(item -> item.value() != null && item.finding() == null);
        if (!allCategorical && !allNumeric) {
            return new ConcordanceResult("NOT_COMPARABLE", "MIXED_CATEGORICAL_AND_NUMERIC_EVIDENCE", ids, VERSION);
        }
        if (allNumeric) {
            // v1 does not define a numeric concordance rule (no agreed tolerance band exists in
            // this product yet) -- named explicitly rather than inventing a threshold.
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
