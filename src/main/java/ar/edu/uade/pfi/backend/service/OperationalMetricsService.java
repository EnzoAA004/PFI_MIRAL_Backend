package ar.edu.uade.pfi.backend.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * Contadores operativos en proceso — solo claves fijas y de baja cardinalidad. Es una solución provisoria hasta que GCP
 * (Cloud Monitoring) esté conectado; ver docs/P10_B_ERRORS_AUDIT_OBSERVABILITY.md para las limitaciones
 * reconocidas (en memoria, se reinicia con redeploy/restart, instancia única, no es un exporter de
 * Prometheus/OTel).
 *
 * <p>Deliberadamente nunca se indexa por traceId/caseId/runId/email/userId/un path arbitrario, ni por un
 * mensaje de excepción — cada contador acá es un campo fijo y enumerable, no un mapa dinámico indexado por
 * datos del request.
 */
@Component
public class OperationalMetricsService {
  private final Instant startedAt = Instant.now();

  private final LongAdder httpRequestsTotal = new LongAdder();
  private final LongAdder httpResponses2xx = new LongAdder();
  private final LongAdder httpResponses4xx = new LongAdder();
  private final LongAdder httpResponses5xx = new LongAdder();
  private final LongAdder authenticationFailures = new LongAdder();
  private final LongAdder authorizationDenials = new LongAdder();
  private final LongAdder authStateUnavailable = new LongAdder();
  private final LongAdder databaseUnavailable = new LongAdder();
  private final LongAdder aiCallsTotal = new LongAdder();
  private final LongAdder aiCallsSucceeded = new LongAdder();
  private final LongAdder aiCallsFailed = new LongAdder();
  private final LongAdder aiContractViolations = new LongAdder();
  private final LongAdder reviewsSaved = new LongAdder();
  private final LongAdder auditWriteFailures = new LongAdder();

  private final LongAdder totalRequestDurationMs = new LongAdder();
  private final LongAdder aiCallDurationMs = new LongAdder();
  private final LongAdder aiCallCountForAverage = new LongAdder();

  public void recordHttpRequest(int status, long durationMs) {
    httpRequestsTotal.increment();
    totalRequestDurationMs.add(Math.max(0, durationMs));
    if (status >= 200 && status < 300) {
      httpResponses2xx.increment();
    } else if (status >= 400 && status < 500) {
      httpResponses4xx.increment();
    } else if (status >= 500) {
      httpResponses5xx.increment();
    }
  }

  public void incrementAuthenticationFailures() {
    authenticationFailures.increment();
  }

  public void incrementAuthorizationDenials() {
    authorizationDenials.increment();
  }

  public void incrementAuthStateUnavailable() {
    authStateUnavailable.increment();
  }

  public void incrementDatabaseUnavailable() {
    databaseUnavailable.increment();
  }

  public void recordAiCall(boolean succeeded, long durationMs) {
    aiCallsTotal.increment();
    aiCallDurationMs.add(Math.max(0, durationMs));
    aiCallCountForAverage.increment();
    if (succeeded) {
      aiCallsSucceeded.increment();
    } else {
      aiCallsFailed.increment();
    }
  }

  public void incrementAiContractViolations() {
    aiContractViolations.increment();
  }

  public void incrementReviewsSaved() {
    reviewsSaved.increment();
  }

  public void incrementAuditWriteFailures() {
    auditWriteFailures.increment();
  }

  public long uptimeSeconds() {
    return Math.max(0, Instant.now().getEpochSecond() - startedAt.getEpochSecond());
  }

  /** Snapshot sanitizado y de forma fija, apto para el endpoint de diagnóstico de ADMIN. */
  public Map<String, Object> snapshot() {
    Map<String, Object> counters = new LinkedHashMap<>();
    counters.put("httpRequestsTotal", httpRequestsTotal.sum());
    counters.put("httpResponses2xx", httpResponses2xx.sum());
    counters.put("httpResponses4xx", httpResponses4xx.sum());
    counters.put("httpResponses5xx", httpResponses5xx.sum());
    counters.put("authenticationFailures", authenticationFailures.sum());
    counters.put("authorizationDenials", authorizationDenials.sum());
    counters.put("authStateUnavailable", authStateUnavailable.sum());
    counters.put("databaseUnavailable", databaseUnavailable.sum());
    counters.put("aiCallsTotal", aiCallsTotal.sum());
    counters.put("aiCallsSucceeded", aiCallsSucceeded.sum());
    counters.put("aiCallsFailed", aiCallsFailed.sum());
    counters.put("aiContractViolations", aiContractViolations.sum());
    counters.put("reviewsSaved", reviewsSaved.sum());
    counters.put("auditWriteFailures", auditWriteFailures.sum());

    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("uptimeSeconds", uptimeSeconds());
    snapshot.put("counters", counters);
    snapshot.put(
        "averageRequestDurationMs", average(totalRequestDurationMs.sum(), httpRequestsTotal.sum()));
    snapshot.put(
        "averageAiCallDurationMs", average(aiCallDurationMs.sum(), aiCallCountForAverage.sum()));
    snapshot.put("auditWriteFailures", auditWriteFailures.sum());
    snapshot.put("timestamp", Instant.now().toString());
    return snapshot;
  }

  private long average(long totalMs, long count) {
    return count <= 0 ? 0 : totalMs / count;
  }
}
