package ar.edu.uade.pfi.backend.client;

/**
 * Etapa 80: resultado de chat agnóstico del proveedor. Deliberadamente no lleva flags de seguridad
 * ({@code humanReviewRequired}/{@code notClinicalDiagnosis}) — los setea
 * {@code ChatService} mismo, incondicionalmente, nunca se derivan de la respuesta de un proveedor (etapa
 * 80-L: "No permitir que provider cambie esos flags").
 */
public record LlmChatResult(String content, String model, String provider) {
}
