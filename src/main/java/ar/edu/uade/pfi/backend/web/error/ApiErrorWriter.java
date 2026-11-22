package ar.edu.uade.pfi.backend.web.error;

import ar.edu.uade.pfi.backend.web.filter.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Único lugar que arma y (para los filtros de servlet, que corren por fuera de la propia serialización de
 * respuestas de Spring MVC) escribe el único contrato de error de la API — ver {@link ApiErrorResponse}. Lo usan {@code
 * ApiExceptionHandler} (solo el body — Spring MVC lo serializa), y directamente {@code
 * AuthFilter}/{@code CorsResponseFilter} (que deben escribir ellos mismos en la respuesta cruda, antes de que
 * corra cualquier controller/handler).
 *
 * <p>Deliberadamente acotado: nunca lanza excepción, nunca incluye un stack trace, el mensaje original de la excepción,
 * el body del request, el query string ni el header Authorization. Siempre setea
 * Content-Type/Cache-Control/X-Trace-Id.
 */
@Component
public class ApiErrorWriter {
  private static final Logger log = LoggerFactory.getLogger(ApiErrorWriter.class);
  private final ObjectMapper objectMapper;

  public ApiErrorWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Endpoints donde se presume que un POST no es idempotente — retryable siempre se fuerza a false ahí. Ver
   * los docs de clase sobre retryable.
   */
  private static boolean isNonIdempotentInferenceEndpoint(String method, String path) {
    if (!"POST".equalsIgnoreCase(method) || path == null) return false;
    return path.contains("/multiplanar/run")
        || path.contains("/pipeline/run")
        || path.contains("/inputs");
  }

  /**
   * retryable = true solo para un código transitorio conocido (DATABASE_UNAVAILABLE, AUTH_STATE_UNAVAILABLE,
   * UPSTREAM_UNAVAILABLE, AI_TIMEOUT). Un HTTP 502/503/504 solo se usa como fallback defensivo para
   * un código que este catálogo no reconoce ({@link ApiErrorCode#UNKNOWN}) — un código *conocido*
   * y explícitamente clasificado como no transitorio (p. ej. AI_CONTRACT_VIOLATION, que también se mapea a
   * 502) nunca debe promoverse a retryable solo por su status HTTP. En cualquier caso, un POST a
   * un endpoint de inferencia/upload no idempotente siempre fuerza retryable de vuelta a false, ya que el
   * backend no puede garantizar que sea seguro reintentar allí el efecto secundario subyacente en el módulo de IA.
   */
  public boolean resolveRetryable(String code, int status, String method, String path) {
    ApiErrorCode errorCode = ApiErrorCode.fromCode(code);
    boolean transientCode = errorCode.transientByDefault();
    boolean unclassifiedGatewayStatus =
        errorCode == ApiErrorCode.UNKNOWN && (status == 502 || status == 503 || status == 504);
    if (!transientCode && !unclassifiedGatewayStatus) return false;
    return !isNonIdempotentInferenceEndpoint(method, path);
  }

  /** Arma el {@link ApiErrorResponse} canónico para este error. */
  public ApiErrorResponse response(
      String code, String message, String traceId, String path, String method, int status) {
    ApiErrorCode errorCode = ApiErrorCode.fromCode(code);
    return new ApiErrorResponse(
        "error",
        code,
        message,
        traceId,
        path,
        method,
        Instant.now().toString(),
        errorCode.category().name(),
        resolveRetryable(code, status, method, path),
        true,
        true);
  }

  /**
   * El mismo contrato que {@link #response}, como un Map ordenado — extensible para llamantes que agregan campos
   * de dominio extra (p. ej. runId/plane/assetName).
   */
  public Map<String, Object> body(
      String code, String message, String traceId, String path, String method, int status) {
    ApiErrorResponse errorResponse = response(code, message, traceId, path, method, status);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", errorResponse.status());
    body.put("code", errorResponse.code());
    body.put("message", errorResponse.message());
    body.put("traceId", errorResponse.traceId());
    body.put("path", errorResponse.path());
    body.put("method", errorResponse.method());
    body.put("timestamp", errorResponse.timestamp());
    body.put("category", errorResponse.category());
    body.put("retryable", errorResponse.retryable());
    body.put("humanReviewRequired", errorResponse.humanReviewRequired());
    body.put("notClinicalDiagnosis", errorResponse.notClinicalDiagnosis());
    return body;
  }

  /**
   * Escritura directa a la respuesta del servlet para filtros que corren antes/por fuera de la
   * serialización propia de Spring MVC. Nunca lanza excepción.
   */
  public void writeError(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String message) {
    String traceId = traceId(request);
    Map<String, Object> body =
        body(code, message, traceId, request.getRequestURI(), request.getMethod(), status);
    response.setStatus(status);
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader(TraceIdFilter.TRACE_ID_HEADER, traceId);
    try {
      objectMapper.writeValue(response.getWriter(), body);
    } catch (IOException ex) {
      // Es probable que la respuesta ya esté confirmada/cerrada en este punto — no hay nada seguro
      // para hacer más allá de dejar constancia de que ocurrió; no debe dejar que esto escape de la cadena de filtros.
      log.warn("event=api_error_write_failed traceId={} status={}", traceId, status);
    }
  }

  private String traceId(HttpServletRequest request) {
    Object attribute = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    if (attribute instanceof String value && !value.isBlank()) {
      return value;
    }
    String header = request.getHeader(TraceIdFilter.TRACE_ID_HEADER);
    return header == null || header.isBlank() ? "unavailable" : header;
  }
}
