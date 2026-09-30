package ar.edu.uade.pfi.backend.service;

/**
 * The AI Module's POST /v1/product/analyze response does not match the frozen
 * pfi.ai-product-result.v1 contract identity (schemaVersion or release.releaseId), or is
 * structurally unusable (missing a required top-level field). Never a safety violation —
 * see {@link AiProductSafetyViolationException} for that.
 */
public class AiProductContractViolationException extends RuntimeException {
    public AiProductContractViolationException(String message) {
        super(message);
    }
}
