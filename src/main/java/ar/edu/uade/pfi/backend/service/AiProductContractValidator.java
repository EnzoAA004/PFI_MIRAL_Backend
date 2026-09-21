package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.config.AiProductContractProperties;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeResponseDto;
import org.springframework.stereotype.Component;

/**
 * Enforces the minimum contract Spring must verify on every POST /v1/product/analyze response
 * (stage 75-F): {@code schemaVersion}, {@code release.releaseId}, and the three safety fields.
 *
 * <p>The safety check is FAIL CLOSED: {@code autonomousDiagnosis=true},
 * {@code notClinicalDiagnosis=false}, or {@code humanReviewRequired=false} is a contract violation
 * regardless of {@code status}, and the result must never be exposed as valid. This is
 * deliberately never relaxed by configuration — there is no flag to turn it off.
 */
@Component
public class AiProductContractValidator {
    private final AiProductContractProperties properties;

    public AiProductContractValidator(AiProductContractProperties properties) {
        this.properties = properties == null ? AiProductContractProperties.defaults() : properties;
    }

    /**
     * @throws AiProductContractViolationException schemaVersion/releaseId mismatch, or the
     *     response is missing a required top-level section.
     * @throws AiProductSafetyViolationException the safety contract itself is violated — never
     *     recoverable, never exposed as a valid result.
     */
    public void validate(AiProductAnalyzeResponseDto response) {
        if (response == null) {
            throw new AiProductContractViolationException("ai_product_response_missing");
        }
        if (!properties.expectedSchemaVersion().equals(response.schemaVersion())) {
            throw new AiProductContractViolationException("ai_product_schema_version_mismatch");
        }
        if (response.release() == null || !properties.expectedReleaseId().equals(response.release().releaseId())) {
            throw new AiProductContractViolationException("ai_product_release_id_mismatch");
        }
        if (response.status() == null) {
            throw new AiProductContractViolationException("ai_product_status_missing");
        }

        // Fail closed — see class docs. Checked last so a plain contract mismatch above is never
        // masked as "safety violation" in logs/metrics.
        if (!Boolean.TRUE.equals(response.humanReviewRequired())) {
            throw new AiProductSafetyViolationException("ai_product_human_review_required_false");
        }
        if (!Boolean.TRUE.equals(response.notClinicalDiagnosis())) {
            throw new AiProductSafetyViolationException("ai_product_not_clinical_diagnosis_false");
        }
        if (!Boolean.FALSE.equals(response.autonomousDiagnosis())) {
            throw new AiProductSafetyViolationException("ai_product_autonomous_diagnosis_true");
        }
    }
}
