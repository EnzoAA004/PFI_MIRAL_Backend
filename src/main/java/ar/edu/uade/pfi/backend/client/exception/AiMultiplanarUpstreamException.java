package ar.edu.uade.pfi.backend.client.exception;

import org.springframework.http.HttpStatus;

/**
 * Un error estructurado expuesto por el contrato pfi.multiplanar-run.v2 del módulo de IA
 * (AiStructuredErrorV2Dto), traducido a un código de error estable del backend. Nunca lleva el stack trace
 * upstream ni el body completo de la respuesta.
 */
public class AiMultiplanarUpstreamException extends RuntimeException {
  private final HttpStatus status;
  private final String code;
  private final String aiTraceId;

  public AiMultiplanarUpstreamException(
      HttpStatus status, String code, String message, String aiTraceId) {
    super(message);
    this.status = status;
    this.code = code;
    this.aiTraceId = aiTraceId;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public String aiTraceId() {
    return aiTraceId;
  }
}
