package ar.edu.uade.pfi.backend.service;

import org.springframework.http.HttpStatus;

/**
 * Una falla a nivel de transporte al llamar a POST /v1/product/analyze (4xx/5xx/timeout/conexión).
 * Replica la forma de {@link AiMultiplanarUpstreamException} para un subsistema distinto, de modo que una
 * falla de product-analyze nunca se loguee/clasifique como una multiplanar. Nunca lleva el
 * body upstream crudo — el mensaje público siempre sale de {@code ApiErrorCode.publicMessage()}.
 */
public class AiProductUpstreamException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String aiTraceId;

    public AiProductUpstreamException(HttpStatus status, String code, String message, String aiTraceId) {
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
