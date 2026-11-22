package ar.edu.uade.pfi.backend.client;

import ar.edu.uade.pfi.backend.client.exception.AiMultiplanarContractViolationException;

/**
 * Protege contra que el body.traceId del request v2 se desvíe alguna vez del header X-Trace-Id enviado al
 * módulo de IA. Ambos salen del mismo valor del MDC en operación normal, así que una discrepancia acá
 * indica una regresión de código, no un input del usuario — de ahí la falla dura por violación de contrato en lugar de
 * elegir silenciosamente un valor.
 */
final class TraceIdConsistencyGuard {
  private TraceIdConsistencyGuard() {}

  static void require(String bodyTraceId, String headerTraceId) {
    if (bodyTraceId == null || headerTraceId == null) return;
    if (!bodyTraceId.equals(headerTraceId)) {
      throw new AiMultiplanarContractViolationException(
          "traceId del body v2 ("
              + bodyTraceId
              + ") no coincide con el header X-Trace-Id ("
              + headerTraceId
              + ")");
    }
  }
}
