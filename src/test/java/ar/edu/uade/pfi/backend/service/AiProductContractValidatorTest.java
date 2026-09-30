package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ar.edu.uade.pfi.backend.config.AiProductContractProperties;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeResponseDto;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Stage 75-F contract/safety validation — see docs/post_e50/75_SPRING_AI_PRODUCT_CONTRACT.md. */
class AiProductContractValidatorTest {
    private final AiProductContractValidator validator = new AiProductContractValidator(AiProductContractProperties.defaults());

    private AiProductAnalyzeResponseDto.Review review() {
        return new AiProductAnalyzeResponseDto.Review(true, "pending", true);
    }

    private AiProductAnalyzeResponseDto.Planes emptyPlanes() {
        AiProductAnalyzeResponseDto.Plane notRequested = new AiProductAnalyzeResponseDto.Plane("not_requested", false, null, null, true, null, null);
        return new AiProductAnalyzeResponseDto.Planes(notRequested, notRequested);
    }

    private AiProductAnalyzeResponseDto valid() {
        return new AiProductAnalyzeResponseDto(
            "pfi.ai-product-result.v1",
            "product-1",
            "trace-1",
            "case-1",
            new AiProductAnalyzeResponseDto.Release("post-e50-ai-product-freeze-v1"),
            "completed",
            emptyPlanes(),
            List.of(),
            List.of(),
            review(),
            true,
            false,
            true
        );
    }

    /** Tests 10/11: a fully-compliant response passes without exception. */
    @Test
    void validResponsePassesValidation() {
        assertDoesNotThrow(() -> validator.validate(valid()));
    }

    /** Test 14: schemaVersion mismatch is a contract violation. */
    @Test
    void wrongSchemaVersionIsContractViolation() {
        AiProductAnalyzeResponseDto response = withSchemaVersion(valid(), "pfi.ai-product-result.v0");
        assertThrows(AiProductContractViolationException.class, () -> validator.validate(response));
    }

    /** Test 15: releaseId mismatch is a contract violation. */
    @Test
    void wrongReleaseIdIsContractViolation() {
        AiProductAnalyzeResponseDto response = withRelease(valid(), new AiProductAnalyzeResponseDto.Release("some-other-release"));
        assertThrows(AiProductContractViolationException.class, () -> validator.validate(response));
    }

    @Test
    void missingReleaseIsContractViolation() {
        AiProductAnalyzeResponseDto response = withRelease(valid(), null);
        assertThrows(AiProductContractViolationException.class, () -> validator.validate(response));
    }

    /** Test 12: autonomousDiagnosis=true must FAIL CLOSED regardless of status. */
    @Test
    void autonomousDiagnosisTrueFailsClosed() {
        AiProductAnalyzeResponseDto response = withAutonomousDiagnosis(valid(), true);
        assertThrows(AiProductSafetyViolationException.class, () -> validator.validate(response));
    }

    /** Test 13: notClinicalDiagnosis=false must FAIL CLOSED regardless of status. */
    @Test
    void notClinicalDiagnosisFalseFailsClosed() {
        AiProductAnalyzeResponseDto response = withNotClinicalDiagnosis(valid(), false);
        assertThrows(AiProductSafetyViolationException.class, () -> validator.validate(response));
    }

    @Test
    void humanReviewRequiredFalseFailsClosed() {
        AiProductAnalyzeResponseDto response = withHumanReviewRequired(valid(), false);
        assertThrows(AiProductSafetyViolationException.class, () -> validator.validate(response));
    }

    @Test
    void nullResponseIsContractViolation() {
        assertThrows(AiProductContractViolationException.class, () -> validator.validate(null));
    }

    // --- small copy helpers, since AiProductAnalyzeResponseDto is a record ---

    private AiProductAnalyzeResponseDto withSchemaVersion(AiProductAnalyzeResponseDto base, String schemaVersion) {
        return new AiProductAnalyzeResponseDto(schemaVersion, base.runId(), base.traceId(), base.caseId(), base.release(), base.status(), base.planes(), base.discLevels(), base.warnings(), base.review(), base.notClinicalDiagnosis(), base.autonomousDiagnosis(), base.humanReviewRequired());
    }

    private AiProductAnalyzeResponseDto withRelease(AiProductAnalyzeResponseDto base, AiProductAnalyzeResponseDto.Release release) {
        return new AiProductAnalyzeResponseDto(base.schemaVersion(), base.runId(), base.traceId(), base.caseId(), release, base.status(), base.planes(), base.discLevels(), base.warnings(), base.review(), base.notClinicalDiagnosis(), base.autonomousDiagnosis(), base.humanReviewRequired());
    }

    private AiProductAnalyzeResponseDto withAutonomousDiagnosis(AiProductAnalyzeResponseDto base, boolean autonomousDiagnosis) {
        return new AiProductAnalyzeResponseDto(base.schemaVersion(), base.runId(), base.traceId(), base.caseId(), base.release(), base.status(), base.planes(), base.discLevels(), base.warnings(), base.review(), base.notClinicalDiagnosis(), autonomousDiagnosis, base.humanReviewRequired());
    }

    private AiProductAnalyzeResponseDto withNotClinicalDiagnosis(AiProductAnalyzeResponseDto base, boolean notClinicalDiagnosis) {
        return new AiProductAnalyzeResponseDto(base.schemaVersion(), base.runId(), base.traceId(), base.caseId(), base.release(), base.status(), base.planes(), base.discLevels(), base.warnings(), base.review(), notClinicalDiagnosis, base.autonomousDiagnosis(), base.humanReviewRequired());
    }

    private AiProductAnalyzeResponseDto withHumanReviewRequired(AiProductAnalyzeResponseDto base, boolean humanReviewRequired) {
        return new AiProductAnalyzeResponseDto(base.schemaVersion(), base.runId(), base.traceId(), base.caseId(), base.release(), base.status(), base.planes(), base.discLevels(), base.warnings(), base.review(), base.notClinicalDiagnosis(), base.autonomousDiagnosis(), humanReviewRequired);
    }
}
