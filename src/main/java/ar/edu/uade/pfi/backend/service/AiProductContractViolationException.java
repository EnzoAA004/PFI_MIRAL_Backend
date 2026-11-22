package ar.edu.uade.pfi.backend.service;

/**
 * La respuesta de POST /v1/product/analyze del módulo de IA no coincide con la identidad del contrato congelado
 * pfi.ai-product-result.v1 (schemaVersion o release.releaseId), o es
 * estructuralmente inutilizable (falta un campo requerido de primer nivel). Nunca una violación de seguridad —
 * para eso ver {@link AiProductSafetyViolationException}.
 */
public class AiProductContractViolationException extends RuntimeException {
    public AiProductContractViolationException(String message) {
        super(message);
    }
}
