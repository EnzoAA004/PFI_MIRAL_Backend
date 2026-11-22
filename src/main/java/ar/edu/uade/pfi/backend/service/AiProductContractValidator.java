package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.config.AiProductContractProperties;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeResponseDto;
import org.springframework.stereotype.Component;

/**
 * Aplica el contrato mínimo que Spring debe verificar en cada respuesta de POST /v1/product/analyze
 * (etapa 75-F): {@code schemaVersion}, {@code release.releaseId} y los tres campos de seguridad.
 *
 * <p>El chequeo de seguridad es FAIL CLOSED: {@code autonomousDiagnosis=true},
 * {@code notClinicalDiagnosis=false} o {@code humanReviewRequired=false} es una violación de contrato
 * sin importar el {@code status}, y el resultado nunca debe exponerse como válido. Esto
 * deliberadamente nunca se relaja por configuración — no hay ningún flag para apagarlo.
 */
@Component
public class AiProductContractValidator {
    private final AiProductContractProperties properties;

    public AiProductContractValidator(AiProductContractProperties properties) {
        this.properties = properties == null ? AiProductContractProperties.defaults() : properties;
    }

    /**
     * @throws AiProductContractViolationException discrepancia de schemaVersion/releaseId, o la
     *     respuesta carece de una sección requerida de primer nivel.
     * @throws AiProductSafetyViolationException se viola el propio contrato de seguridad — nunca
     *     recuperable, nunca expuesto como un resultado válido.
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

        // Fail closed — ver docs de clase. Se chequea al final para que una discrepancia de contrato simple de arriba nunca
        // se enmascare como "safety violation" en logs/métricas.
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
