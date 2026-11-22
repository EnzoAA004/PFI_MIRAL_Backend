package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.EvidenceItem;
import java.util.List;

/**
 * EXT-01: el resultado de comparar un grupo de {@link EvidenceItem}s del mismo fenómeno/nivel/lado
 * bajo {@link ConcordanceRuleSetV1}. Nunca afirma un diagnóstico — {@code reasonCode} es siempre un
 * string de máquina corto y auditable, y la capa de presentación propia del Frontend es responsable de
 * redactarlo como "las fuentes de evidencia son concordantes según la regla X", nunca "confirmado".
 */
public record ConcordanceResult(
    String state,
    String reasonCode,
    List<String> comparedEvidenceIds,
    String ruleVersion
) {
    public static final List<String> STATES = List.of("CONCORDANT", "PARTIALLY_CONCORDANT", "DISCREPANT", "INSUFFICIENT_EVIDENCE", "NOT_COMPARABLE");
}
