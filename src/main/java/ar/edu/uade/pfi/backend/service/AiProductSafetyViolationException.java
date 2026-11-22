package ar.edu.uade.pfi.backend.service;

/**
 * La respuesta de POST /v1/product/analyze del módulo de IA viola el contrato de seguridad del producto:
 * {@code autonomousDiagnosis=true} o {@code notClinicalDiagnosis=false} (o
 * {@code humanReviewRequired=false}). Esto es FAIL CLOSED — el resultado nunca se expone como
 * válido, sin importar el {@code status}. Se mantiene separada de
 * {@link AiProductContractViolationException} para que una falla de seguridad nunca se confunda con una
 * discrepancia rutinaria de schema/release en logs, métricas o tests.
 */
public class AiProductSafetyViolationException extends RuntimeException {
    public AiProductSafetyViolationException(String message) {
        super(message);
    }
}
