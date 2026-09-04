package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ar.edu.uade.pfi.backend.domain.EvidenceItem;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConcordanceRuleSetV1Test {
    private final ConcordanceRuleSetV1 ruleSet = new ConcordanceRuleSetV1();

    private EvidenceItem categorical(String id, String source, String level, String side, String phenomenon, String finding) {
        return new EvidenceItem(id, source, "sagittal_spider", "pfi.multiplanar-run.v2", "CASE-1", level, side, "axial", phenomenon, finding, null, null, true, null);
    }

    private EvidenceItem numeric(String id, String source, String level, String side, String phenomenon, double value) {
        return new EvidenceItem(id, source, "sagittal_spider", "pfi.multiplanar-run.v2", "CASE-1", level, side, "sagittal", phenomenon, null, value, null, false, null);
    }

    @Test
    void sameFindingAcrossTwoSourcesIsConcordant() {
        List<ConcordanceResult> results = ruleSet.evaluate(List.of(
            categorical("e1", "disc_level_batch", "L4-L5", "left", "subarticular_stenosis", "moderate"),
            categorical("e2", "subarticular_roi", "L4-L5", "left", "subarticular_stenosis", "moderate")
        ));
        assertEquals(1, results.size());
        assertEquals("CONCORDANT", results.get(0).state());
        assertEquals("v1", results.get(0).ruleVersion());
    }

    @Test
    void oneStepSeverityDifferenceIsPartiallyConcordant() {
        List<ConcordanceResult> results = ruleSet.evaluate(List.of(
            categorical("e1", "disc_level_batch", "L4-L5", "left", "subarticular_stenosis", "normal_mild"),
            categorical("e2", "subarticular_roi", "L4-L5", "left", "subarticular_stenosis", "moderate")
        ));
        assertEquals("PARTIALLY_CONCORDANT", results.get(0).state());
        assertEquals("ADJACENT_SEVERITY_STEP", results.get(0).reasonCode());
    }

    @Test
    void twoStepSeverityDifferenceIsDiscrepant() {
        List<ConcordanceResult> results = ruleSet.evaluate(List.of(
            categorical("e1", "disc_level_batch", "L4-L5", "left", "subarticular_stenosis", "normal_mild"),
            categorical("e2", "subarticular_roi", "L4-L5", "left", "subarticular_stenosis", "severe")
        ));
        assertEquals("DISCREPANT", results.get(0).state());
        assertEquals("SEVERITY_SPREAD_TWO_OR_MORE_STEPS", results.get(0).reasonCode());
    }

    @Test
    void aSingleSourceIsInsufficientEvidenceNeverConcordantByDefault() {
        List<ConcordanceResult> results = ruleSet.evaluate(List.of(
            categorical("e1", "disc_level_batch", "L4-L5", "left", "subarticular_stenosis", "moderate")
        ));
        assertEquals("INSUFFICIENT_EVIDENCE", results.get(0).state());
        assertEquals("SINGLE_SOURCE_ONLY", results.get(0).reasonCode());
    }

    @Test
    void differentPhenomenaAreNeverGroupedTogether() {
        List<ConcordanceResult> results = ruleSet.evaluate(List.of(
            categorical("e1", "disc_level_batch", "L4-L5", "left", "subarticular_stenosis", "moderate"),
            categorical("e2", "disc_level_batch", "L4-L5", "left", "central_canal_stenosis", "moderate")
        ));
        // Two distinct phenomenon groups, each with a single item -> both insufficient, never merged.
        assertEquals(2, results.size());
        assertEquals("INSUFFICIENT_EVIDENCE", results.get(0).state());
        assertEquals("INSUFFICIENT_EVIDENCE", results.get(1).state());
    }

    @Test
    void differentLevelsAreNeverGroupedTogether() {
        List<ConcordanceResult> results = ruleSet.evaluate(List.of(
            categorical("e1", "disc_level_batch", "L4-L5", "left", "subarticular_stenosis", "moderate"),
            categorical("e2", "subarticular_roi", "L3-L4", "left", "subarticular_stenosis", "moderate")
        ));
        assertEquals(2, results.size());
    }

    @Test
    void differentSidesAreNeverGroupedTogether() {
        List<ConcordanceResult> results = ruleSet.evaluate(List.of(
            categorical("e1", "disc_level_batch", "L4-L5", "left", "subarticular_stenosis", "moderate"),
            categorical("e2", "subarticular_roi", "L4-L5", "right", "subarticular_stenosis", "moderate")
        ));
        assertEquals(2, results.size());
    }

    @Test
    void mixingCategoricalAndNumericEvidenceIsNotComparableNeverForced() {
        List<ConcordanceResult> results = ruleSet.evaluate(List.of(
            categorical("e1", "disc_level_batch", "L4-L5", "left", "canal_area", "moderate"),
            numeric("e2", "measurement", "L4-L5", "left", "canal_area", 120.0)
        ));
        assertEquals("NOT_COMPARABLE", results.get(0).state());
        assertEquals("MIXED_CATEGORICAL_AND_NUMERIC_EVIDENCE", results.get(0).reasonCode());
    }

    @Test
    void twoNumericItemsAreInsufficientEvidenceNoInventedTolerance() {
        List<ConcordanceResult> results = ruleSet.evaluate(List.of(
            numeric("e1", "measurement", "L4-L5", "left", "canal_area", 120.0),
            numeric("e2", "measurement", "L4-L5", "left", "canal_area", 118.0)
        ));
        assertEquals("INSUFFICIENT_EVIDENCE", results.get(0).state());
        assertEquals("NUMERIC_CONCORDANCE_RULE_NOT_DEFINED_IN_V1", results.get(0).reasonCode());
    }

    @Test
    void threeConcordantSourcesStayConcordant() {
        List<ConcordanceResult> results = ruleSet.evaluate(List.of(
            categorical("e1", "disc_level_batch", "L4-L5", "left", "subarticular_stenosis", "severe"),
            categorical("e2", "subarticular_roi", "L4-L5", "left", "subarticular_stenosis", "severe"),
            categorical("e3", "professional_review", "L4-L5", "left", "subarticular_stenosis", "severe")
        ));
        assertEquals(1, results.size());
        assertEquals("CONCORDANT", results.get(0).state());
        assertEquals(3, results.get(0).comparedEvidenceIds().size());
    }

    @Test
    void nonOrdinalFindingsThatDifferAreDiscrepantNotPartial() {
        List<ConcordanceResult> results = ruleSet.evaluate(List.of(
            categorical("e1", "disc_level_batch", "L4-L5", "left", "modic_change", "type_1"),
            categorical("e2", "subarticular_roi", "L4-L5", "left", "modic_change", "type_2")
        ));
        assertEquals("DISCREPANT", results.get(0).state());
        assertEquals("DIFFERENT_NON_ORDINAL_FINDINGS", results.get(0).reasonCode());
    }

    @Test
    void emptyEvidenceListProducesNoResultsNotAnException() {
        assertEquals(0, ruleSet.evaluate(List.of()).size());
    }
}
