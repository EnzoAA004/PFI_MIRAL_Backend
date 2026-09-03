package ar.edu.uade.pfi.backend.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DerivedEvidenceOriginTest {

    @Test
    void aiOriginPendingReviewIsSuggested() {
        assertEquals(DerivedEvidenceOrigin.AI_SUGGESTED, DerivedEvidenceOrigin.derive("ai", false, "pending"));
    }

    @Test
    void aiOriginAcceptedIsValidated() {
        assertEquals(DerivedEvidenceOrigin.PROFESSIONAL_VALIDATED, DerivedEvidenceOrigin.derive("ai", false, "accepted"));
    }

    @Test
    void aiOriginObservedIsAlsoValidated() {
        // "observed" is treated exactly like "accepted", the same reading EffectiveAnalysisRunSelector
        // already gives it for run selection -- not a weaker, ambiguous third state.
        assertEquals(DerivedEvidenceOrigin.PROFESSIONAL_VALIDATED, DerivedEvidenceOrigin.derive("ai", false, "observed"));
    }

    @Test
    void aiOriginRejectedWithoutCorrectionIsStillLabeledSuggested() {
        // Rejection changes EFFECTIVENESS (EffectiveAnnotationResolver), not the origin label --
        // it is still the AI's own, unmodified suggestion.
        assertEquals(DerivedEvidenceOrigin.AI_SUGGESTED, DerivedEvidenceOrigin.derive("ai", false, "rejected"));
    }

    @Test
    void aiOriginWithAnyCorrectionIsCorrectedRegardlessOfReviewStatus() {
        assertEquals(DerivedEvidenceOrigin.PROFESSIONAL_CORRECTED, DerivedEvidenceOrigin.derive("ai", true, "pending"));
        assertEquals(DerivedEvidenceOrigin.PROFESSIONAL_CORRECTED, DerivedEvidenceOrigin.derive("ai", true, "rejected"));
        assertEquals(DerivedEvidenceOrigin.PROFESSIONAL_CORRECTED, DerivedEvidenceOrigin.derive("ai", true, "accepted"));
    }

    @Test
    void professionalOriginIsAlwaysDrawnRegardlessOfReviewStatus() {
        assertEquals(DerivedEvidenceOrigin.PROFESSIONAL_DRAWN, DerivedEvidenceOrigin.derive("professional", false, "pending"));
        assertEquals(DerivedEvidenceOrigin.PROFESSIONAL_DRAWN, DerivedEvidenceOrigin.derive("professional", false, null));
    }
}
