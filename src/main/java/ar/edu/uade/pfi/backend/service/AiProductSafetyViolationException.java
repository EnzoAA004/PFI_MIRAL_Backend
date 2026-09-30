package ar.edu.uade.pfi.backend.service;

/**
 * The AI Module's POST /v1/product/analyze response violates the product safety contract:
 * {@code autonomousDiagnosis=true} or {@code notClinicalDiagnosis=false} (or
 * {@code humanReviewRequired=false}). This is FAIL CLOSED — the result is never exposed as
 * valid, regardless of {@code status}. Kept distinct from
 * {@link AiProductContractViolationException} so a safety failure is never confused with a
 * routine schema/release mismatch in logs, metrics, or tests.
 */
public class AiProductSafetyViolationException extends RuntimeException {
    public AiProductSafetyViolationException(String message) {
        super(message);
    }
}
