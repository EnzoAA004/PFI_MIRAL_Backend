package ar.edu.uade.pfi.backend.service;

import org.springframework.http.HttpStatus;

/**
 * A transport-level failure calling POST /v1/product/analyze (4xx/5xx/timeout/connection).
 * Mirrors {@link AiMultiplanarUpstreamException}'s shape for a different subsystem so a
 * product-analyze failure is never logged/classified as a multiplanar one. Never carries the
 * raw upstream body — the public message always comes from {@code ApiErrorCode.publicMessage()}.
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
