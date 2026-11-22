package ar.edu.uade.pfi.backend.web.error;
import ar.edu.uade.pfi.backend.web.filter.TraceIdFilter;
import ar.edu.uade.pfi.backend.util.SafeLogSanitizer;

import ar.edu.uade.pfi.backend.auth.exception.AdminAccountProtectedException;
import ar.edu.uade.pfi.backend.auth.exception.LastAdminProtectionException;

import ar.edu.uade.pfi.backend.service.exception.AiContractViolationException;
import ar.edu.uade.pfi.backend.client.exception.AiMultiplanarContractViolationException;
import ar.edu.uade.pfi.backend.client.exception.AiMultiplanarUpstreamException;
import ar.edu.uade.pfi.backend.service.AiProductContractViolationException;
import ar.edu.uade.pfi.backend.service.AiProductSafetyViolationException;
import ar.edu.uade.pfi.backend.service.AiProductUpstreamException;
import ar.edu.uade.pfi.backend.service.AiStudyInputUnresolvedException;
import ar.edu.uade.pfi.backend.service.exception.AssetContentUnavailableException;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.exception.DatabaseUnavailableException;
import ar.edu.uade.pfi.backend.service.OperationalMetricsService;
import ar.edu.uade.pfi.backend.service.exception.RunReviewException;
import ar.edu.uade.pfi.backend.service.exception.StudyMetadataException;
import ar.edu.uade.pfi.backend.service.exception.StudyNotFoundException;
import ar.edu.uade.pfi.backend.service.exception.PatientDomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Único punto de mapeo desde cualquier excepción al único contrato de error de la API
 * (ver {@code ApiErrorResponse}/{@code ApiErrorWriter}). Nunca loguea ni devuelve el mensaje crudo de
 * la excepción en un 5xx, el nombre de clase completamente calificado de la excepción, SQL, una URL
 * JDBC, una URL del módulo de IA, un path local del filesystem ni un stack trace en el body de la respuesta.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final AuditService auditService;
    private final OperationalMetricsService metrics;
    private final ApiErrorWriter apiErrorWriter;

    public ApiExceptionHandler() {
        this(null, null);
    }

    public ApiExceptionHandler(AuditService auditService) {
        this(auditService, null);
    }

    @Autowired
    public ApiExceptionHandler(@Nullable AuditService auditService, @Nullable OperationalMetricsService metrics) {
        this.auditService = auditService;
        this.metrics = metrics;
        this.apiErrorWriter = new ApiErrorWriter(new ObjectMapper());
    }

    /**
     * El string de motivo propio de una ResponseStatusException está redactado por el backend en cada
     * sitio de llamada actual (auditado para P10-B.1 §2 — ninguno interpola contenido
     * upstream/controlado por el usuario), pero igual se pasa por SafeLogSanitizer como defensa en profundidad para 4xx,
     * y nunca se confía en él para 5xx — ahí se usa en cambio un mensaje fijo del catálogo,
     * igual que en todos los demás handlers de 5xx de esta clase (P10-B.1 §1).
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String code = codeForStatus(status);
        String message = status.is5xxServerError()
            ? ApiErrorCode.fromCode(code).publicMessage()
            : safeMessage(SafeLogSanitizer.sanitizeMessage(ex.getReason()), status);
        return buildError(status, code, message, request, ex);
    }

    @ExceptionHandler(AiContractViolationException.class)
    public ResponseEntity<Map<String, Object>> handleAiContractViolation(AiContractViolationException ex, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_GATEWAY, "AI_CONTRACT_VIOLATION", ApiErrorCode.AI_CONTRACT_VIOLATION.publicMessage(), request, ex);
    }

    @ExceptionHandler(AiMultiplanarContractViolationException.class)
    public ResponseEntity<Map<String, Object>> handleAiMultiplanarContractViolation(AiMultiplanarContractViolationException ex, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_GATEWAY, "AI_MULTIPLANAR_CONTRACT_VIOLATION", ApiErrorCode.AI_MULTIPLANAR_CONTRACT_VIOLATION.publicMessage(), request, ex);
    }

    /**
     * AiServiceClient ya construye esta excepción con un mensaje fijo del catálogo
     * (P10-B.1 §3) — este handler igual vuelve a derivar el mensaje de {@code ex.code()},
     * en lugar de confiar directamente en {@code ex.getMessage()}, de modo que un futuro sitio de llamada que
     * olvide esa disciplina igual no pueda filtrar texto upstream por acá.
     */
    @ExceptionHandler(AiMultiplanarUpstreamException.class)
    public ResponseEntity<Map<String, Object>> handleAiMultiplanarUpstream(AiMultiplanarUpstreamException ex, HttpServletRequest request) {
        return buildError(ex.status(), ex.code(), ApiErrorCode.fromCode(ex.code()).publicMessage(), request, ex);
    }

    /** Etapa 75: discrepancia de schemaVersion/release.releaseId en POST /v1/product/analyze — nunca se expone como un resultado válido. */
    @ExceptionHandler(AiProductContractViolationException.class)
    public ResponseEntity<Map<String, Object>> handleAiProductContractViolation(AiProductContractViolationException ex, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_GATEWAY, "AI_PRODUCT_CONTRACT_VIOLATION", ApiErrorCode.AI_PRODUCT_CONTRACT_VIOLATION.publicMessage(), request, ex);
    }

    /** Etapa 75: violación del contrato de seguridad fail-closed (autonomousDiagnosis/notClinicalDiagnosis/humanReviewRequired). */
    @ExceptionHandler(AiProductSafetyViolationException.class)
    public ResponseEntity<Map<String, Object>> handleAiProductSafetyViolation(AiProductSafetyViolationException ex, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_GATEWAY, "AI_PRODUCT_SAFETY_VIOLATION", ApiErrorCode.AI_PRODUCT_SAFETY_VIOLATION.publicMessage(), request, ex);
    }

    @ExceptionHandler(AiProductUpstreamException.class)
    public ResponseEntity<Map<String, Object>> handleAiProductUpstream(AiProductUpstreamException ex, HttpServletRequest request) {
        return buildError(ex.status(), ex.code(), ApiErrorCode.fromCode(ex.code()).publicMessage(), request, ex);
    }

    @ExceptionHandler(DatabaseUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleDatabaseUnavailable(DatabaseUnavailableException ex, HttpServletRequest request) {
        return buildError(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", ApiErrorCode.DATABASE_UNAVAILABLE.publicMessage(), request, ex);
    }

    @ExceptionHandler(StudyNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleStudyNotFound(StudyNotFoundException ex, HttpServletRequest request) {
        return buildError(HttpStatus.NOT_FOUND, "STUDY_NOT_FOUND", "Estudio no encontrado", request, ex);
    }

    /** PATIENT-PR2: el dominio de paciente ya trae su propio status/code publico. */
    @ExceptionHandler(PatientDomainException.class)
    public ResponseEntity<Map<String, Object>> handlePatientDomain(PatientDomainException ex, HttpServletRequest request) {
        return buildError(ex.status(), ex.code(), ex.getMessage(), request, ex);
    }

    /** Etapa 76-B: el Study existe pero no tiene un input sagital/axial analizable para resolver — funcional, no un 5xx. */
    @ExceptionHandler(AiStudyInputUnresolvedException.class)
    public ResponseEntity<Map<String, Object>> handleAiStudyInputUnresolved(AiStudyInputUnresolvedException ex, HttpServletRequest request) {
        return buildError(HttpStatus.UNPROCESSABLE_ENTITY, "AI_STUDY_INPUT_UNRESOLVED", ApiErrorCode.AI_STUDY_INPUT_UNRESOLVED.publicMessage(), request, ex);
    }

    @ExceptionHandler(AssetContentUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleAssetContentUnavailable(AssetContentUnavailableException ex, HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> response = buildError(HttpStatus.NOT_FOUND, "ASSET_CONTENT_UNAVAILABLE", ex.getMessage(), request, ex);
        response.getBody().put("runId", ex.runId());
        response.getBody().put("plane", ex.plane());
        response.getBody().put("assetName", ex.assetName());
        return response;
    }

    @ExceptionHandler(RunReviewException.class)
    public ResponseEntity<Map<String, Object>> handleRunReview(RunReviewException ex, HttpServletRequest request) {
        return buildError(ex.status(), ex.code(), ex.getMessage(), request, ex);
    }

    @ExceptionHandler(StudyMetadataException.class)
    public ResponseEntity<Map<String, Object>> handleStudyMetadata(StudyMetadataException ex, HttpServletRequest request) {
        return buildError(ex.status(), ex.code(), ex.getMessage(), request, ex);
    }

    @ExceptionHandler(LastAdminProtectionException.class)
    public ResponseEntity<Map<String, Object>> handleLastAdminProtection(LastAdminProtectionException ex, HttpServletRequest request) {
        return buildError(ex.status(), ex.code(), ex.getMessage(), request, ex);
    }

    @ExceptionHandler(AdminAccountProtectedException.class)
    public ResponseEntity<Map<String, Object>> handleAdminAccountProtected(AdminAccountProtectedException ex, HttpServletRequest request) {
        return buildError(ex.status(), ex.code(), ex.getMessage(), request, ex);
    }

    /**
     * JSON malformado o, notablemente, un DTO estricto (JsonIgnoreProperties(ignoreUnknown =
     * false)) que rechaza un campo inesperado — p. ej. un payload de activación profesional
     * que intentó colar "roles" o "admin". 400 sanitizado, nunca el error de parseo crudo de Jackson
     * (que de otro modo podría devolver nombres/valores de campos del request).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableMessage(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Solicitud invalida", request, ex);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", safeMessage(ex.getMessage(), HttpStatus.BAD_REQUEST), request, ex);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .orElse("Payload invalido");
        return buildError(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request, ex);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return buildError(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "INPUT_TOO_LARGE",
            "El archivo medico supera el tamano maximo permitido para carga. Limite por defecto: 200MB.",
            request,
            ex
        );
    }

    /**
     * Catch-all. Nunca expone ex.getMessage() (que podría contener SQL, una URL JDBC, una
     * URL del módulo de IA o un path local) ni en el body ni en la línea de log — solo el
     * mensaje público fijo sanitizado y el nombre simple de clase de la excepción.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException ex, HttpServletRequest request) {
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", ApiErrorCode.INTERNAL_ERROR.publicMessage(), request, ex);
    }

    private ResponseEntity<Map<String, Object>> buildError(
        HttpStatus status,
        String code,
        String message,
        HttpServletRequest request,
        Exception ex
    ) {
        String traceId = traceId(request);
        Map<String, Object> body = apiErrorWriter.body(code, message, traceId, request.getRequestURI(), request.getMethod(), status.value());

        logError(status, code, traceId, request, ex, (Boolean) body.get("retryable"));
        recordMetrics(status, code);
        auditError(traceId, code, status, request);
        return ResponseEntity.status(status)
            .header(TraceIdFilter.TRACE_ID_HEADER, traceId)
            .body(body);
    }

    /**
     * Línea de log estructurada y sanitizada — nunca ex.getMessage() y nunca el nombre
     * completamente calificado de la excepción (paquete + clase), solo su nombre simple de clase. Un stack
     * sanitizado solo se emite en DEBUG (nunca en el body público, nunca en WARN/ERROR por
     * defecto) para que siga disponible para troubleshooting local sin convertirse en la
     * superficie de volumen/filtración de logs de producción por defecto.
     */
    private void logError(HttpStatus status, String code, String traceId, HttpServletRequest request, Exception ex, boolean retryable) {
        String exceptionType = ex.getClass().getSimpleName();
        String category = ApiErrorCode.fromCode(code).category().name();
        String line = "event=api_error traceId={} code={} category={} status={} method={} path={} exceptionType={} retryable={}";
        Object[] args = {traceId, code, category, status.value(), request.getMethod(), request.getRequestURI(), exceptionType, retryable};
        if (status.is5xxServerError()) {
            log.error(line, args);
        } else {
            log.warn(line, args);
        }
        if (log.isDebugEnabled()) {
            log.debug("event=api_error_detail traceId={} exceptionType={} message={}", traceId, exceptionType,
                SafeLogSanitizer.sanitizeMessage(ex.getMessage()));
        }
    }

    /**
     * P10-B.1 §13: AI_MULTIPLANAR_CONTRACT_VIOLATION deliberadamente NO se cuenta acá —
     * AiServiceClient es la única autoridad para ese código (es el único lugar que puede
     * ver todo el pipeline HTTP+adaptador+validador de una corrida multiplanar e incrementar
     * exactamente una vez), por lo que contarlo de nuevo acá duplicaría cada una de esas violaciones.
     * AI_CONTRACT_VIOLATION (el camino más viejo, de un solo plano/pipeline, lanzado desde
     * AiBackendService en lugar de AiServiceClient) no tiene ningún otro contador, así que se queda
     * acá.
     */
    private void recordMetrics(HttpStatus status, String code) {
        if (metrics == null) return;
        if (status == HttpStatus.UNAUTHORIZED) {
            metrics.incrementAuthenticationFailures();
        } else if (status == HttpStatus.FORBIDDEN) {
            metrics.incrementAuthorizationDenials();
        } else if ("DATABASE_UNAVAILABLE".equals(code)) {
            metrics.incrementDatabaseUnavailable();
        } else if ("AI_CONTRACT_VIOLATION".equals(code)) {
            metrics.incrementAiContractViolations();
        }
    }

    private void auditError(String traceId, String code, HttpStatus status, HttpServletRequest request) {
        if (auditService == null) return;
        auditService.record("backend", "error.http", request.getRequestURI(), traceId, Map.of(
            "code", code,
            "status", status.value(),
            "method", request.getMethod(),
            "path", request.getRequestURI()
        ));
    }

    private String traceId(HttpServletRequest request) {
        Object attribute = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (attribute instanceof String value && !value.isBlank()) {
            return value;
        }
        String header = request.getHeader(TraceIdFilter.TRACE_ID_HEADER);
        return header == null || header.isBlank() ? "unavailable" : header;
    }

    private String codeForStatus(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "BAD_REQUEST";
            case UNAUTHORIZED -> "AUTHENTICATION_REQUIRED";
            case FORBIDDEN -> "ACCESS_DENIED";
            case NOT_FOUND -> "NOT_FOUND";
            case CONFLICT -> "CONFLICT";
            case SERVICE_UNAVAILABLE, BAD_GATEWAY, GATEWAY_TIMEOUT -> "UPSTREAM_UNAVAILABLE";
            default -> status.is4xxClientError() ? "CLIENT_ERROR" : "INTERNAL_ERROR";
        };
    }

    private String safeMessage(String value, HttpStatus status) {
        if (value != null && !value.isBlank()) {
            return value;
        }
        return switch (status) {
            case BAD_REQUEST -> "Solicitud invalida";
            case UNAUTHORIZED -> "Autenticacion requerida.";
            case FORBIDDEN -> "No tiene permisos para realizar esta operacion.";
            case NOT_FOUND -> "Recurso no encontrado";
            case CONFLICT -> "Conflicto de estado";
            default -> status.is5xxServerError() ? "Error interno del backend" : status.getReasonPhrase();
        };
    }
}
