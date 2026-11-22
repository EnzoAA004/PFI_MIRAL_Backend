package ar.edu.uade.pfi.backend.web.error;

/**
 * El único contrato de error explícito de esta API. El orden de los campos acá lo preserva {@link
 * ApiErrorWriter} cuando serializa al body de la respuesta (todavía basado en Map, para campos extensibles por excepción
 * como {@code runId}/{@code plane}/{@code assetName}), de modo que el orden/forma de los campos
 * existentes del frontend no se vea afectado.
 */
public record ApiErrorResponse(
    String status,
    String code,
    String message,
    String traceId,
    String path,
    String method,
    String timestamp,
    String category,
    boolean retryable,
    boolean humanReviewRequired,
    boolean notClinicalDiagnosis) {}
