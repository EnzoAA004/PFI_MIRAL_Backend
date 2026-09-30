package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.EvidenceItem;
import java.util.List;

/**
 * EXT-01: the outcome of comparing one group of same-phenomenon/level/side {@link EvidenceItem}s
 * under {@link ConcordanceRuleSetV1}. Never asserts a diagnosis — {@code reasonCode} is always a
 * short, auditable machine string, and the Frontend's own display layer is responsible for
 * phrasing it as "las fuentes de evidencia son concordantes según la regla X", never "confirmado".
 */
public record ConcordanceResult(
    String state,
    String reasonCode,
    List<String> comparedEvidenceIds,
    String ruleVersion
) {
    public static final List<String> STATES = List.of("CONCORDANT", "PARTIALLY_CONCORDANT", "DISCREPANT", "INSUFFICIENT_EVIDENCE", "NOT_COMPARABLE");
}
