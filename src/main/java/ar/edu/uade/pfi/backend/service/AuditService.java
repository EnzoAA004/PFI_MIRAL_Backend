package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.util.SafeLogSanitizer;
import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.dto.AuditEventResponseDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final Set<String> SENSITIVE_KEY_PARTS = Set.of(
        "token", "secret", "password", "credential", "authorization", "path", "filename", "file", "email", "patient"
    );
    /**
     * PATIENT-PR2: estas tres claves llevan el UUID de-identificado del paciente, no un
     * identificador directo, y deben poder auditarse. Sin esta allowlist quedarian redactadas
     * porque SENSITIVE_KEY_PARTS incluye "patient".
     */
    private static final Set<String> SAFE_PATIENT_ID_KEYS = Set.of("patientid", "frompatientid", "topatientid");
    /** Topes de defensa en profundidad contra el crecimiento accidental de metadata — ver P10-B §11. */
    private static final int MAX_METADATA_ENTRIES = 40;
    private static final int MAX_DEPTH = 5;
    private static final int MAX_LIST_ELEMENTS = 20;
    private static final int MAX_STRING_LENGTH = 160;
    /** P10-B.1 §10: los mismos controles a nivel de campo ahora aplican a actor/entityId/traceId/action, no solo a metadata. */
    private static final int MAX_IDENTIFIER_LENGTH = 100;
    private static final int MAX_TRACE_ID_LENGTH = 96;
    private static final Pattern SAFE_ACTION_PATTERN = Pattern.compile("^[A-Za-z0-9_.]{1,80}$");

    private final StudyRepository repository;
    private final Clock clock;
    private final OperationalMetricsService metrics;

    @Autowired
    public AuditService(StudyRepository repository, @Nullable OperationalMetricsService metrics) {
        this(repository, Clock.systemUTC(), metrics);
    }

    public AuditService(StudyRepository repository) {
        this(repository, Clock.systemUTC(), null);
    }

    AuditService(StudyRepository repository, Clock clock) {
        this(repository, clock, null);
    }

    AuditService(StudyRepository repository, Clock clock, OperationalMetricsService metrics) {
        this.repository = repository;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * Nunca deja que una falla de persistencia de auditoría enmascare la operación real de quien llama: ante cualquier
     * falla loguea una línea sanitizada {@code event=audit_write_failed}, incrementa el
     * contador {@code auditWriteFailures} y devuelve {@code null} en lugar de lanzar excepción —
     * quienes llaman deben tratar la auditoría como best-effort. Esto centraliza lo que antes era un
     * patrón try/catch inconsistente repetido (o, en algunos sitios de llamada, directamente ausente)
     * en cada llamador.
     */
    public AuditEventResponseDto record(String actor, String action, String entityId, String traceId, Map<String, Object> metadata) {
        String safeActor = sanitizeIdentifier(actor, "system");
        String safeAction = sanitizeAction(action);
        String safeEntityId = sanitizeIdentifier(entityId, "");
        String safeTraceId = sanitizeTraceId(traceId);
        try {
            DomainAuditEvent event = repository.saveAuditEvent(new DomainAuditEvent(
                UUID.randomUUID().toString(),
                safeActor,
                safeAction,
                safeEntityId,
                safeTraceId,
                clock.instant(),
                sanitize(metadata)
            ));
            return toResponse(event);
        } catch (RuntimeException ex) {
            if (metrics != null) metrics.incrementAuditWriteFailures();
            log.warn("event=audit_write_failed traceId={} action={} exceptionType={}", safeTraceId, safeAction, ex.getClass().getSimpleName());
            return null;
        }
    }

    public List<AuditEventResponseDto> findByTraceId(String traceId) {
        return repository.findAuditEventsByTraceId(traceId).stream().map(this::toResponse).toList();
    }

    public List<AuditEventResponseDto> findByEntityId(String entityId) {
        return repository.findAuditEventsByEntityId(entityId).stream().map(this::toResponse).toList();
    }

    public Map<String, Object> sanitize(Map<String, Object> metadata) {
        return sanitize(metadata, 0);
    }

    private Map<String, Object> sanitize(Map<String, Object> metadata, int depth) {
        if (metadata == null || metadata.isEmpty()) return Map.of();
        if (depth >= MAX_DEPTH) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (count >= MAX_METADATA_ENTRIES) break;
            String key = entry.getKey() == null ? "" : entry.getKey();
            if (isSensitiveKey(key)) continue;
            safe.put(key, sanitizeValue(entry.getValue(), depth));
            count++;
        }
        return safe;
    }

    private Object sanitizeValue(Object value, int depth) {
        if (value == null) return "";
        if (value instanceof Number || value instanceof Boolean) return value;
        if (depth >= MAX_DEPTH) return "[redacted]";
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : nested.entrySet()) {
                typed.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return sanitize(typed, depth + 1);
        }
        if (value instanceof Iterable<?> values) {
            return toSafeList(values, depth);
        }
        String text = String.valueOf(value);
        // Detección basada en el valor: un Bearer token/JWT/password/path/email puede aparecer en un
        // valor de metadata incluso bajo una clave inocua (p. ej. metadata["value"]) — el filtrado por
        // nombre de clave de arriba no alcanza por sí solo.
        if (looksLikePath(text) || SafeLogSanitizer.isSensitive(text)) return "[redacted]";
        return text.length() > MAX_STRING_LENGTH ? text.substring(0, MAX_STRING_LENGTH) : text;
    }

    private List<Object> toSafeList(Iterable<?> values, int depth) {
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        int count = 0;
        for (Object value : values) {
            if (count >= MAX_LIST_ELEMENTS) break;
            result.add(sanitizeValue(value, depth + 1));
            count++;
        }
        return result;
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        if (SAFE_PATIENT_ID_KEYS.contains(normalized)) return false;
        return SENSITIVE_KEY_PARTS.stream().anyMatch(normalized::contains);
    }

    private boolean looksLikePath(String value) {
        return value.contains("\\") || value.startsWith("/") || value.matches("^[A-Za-z]:\\\\.*") || value.contains("../") || value.contains("..\\");
    }

    private String safeText(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim();
    }

    /**
     * actor/entityId: nunca un email, token, path ni URL — el chequeo de valor completo de {@link SafeLogSanitizer}
     * redacta el identificador entero en lugar de intentar enmascararlo
     * parcialmente (un identificador a medio redactar sigue sin ser un id técnico seguro).
     * Con tope de longitud independiente del tope de string de metadata.
     */
    private String sanitizeIdentifier(String value, String fallback) {
        String text = safeText(value, fallback);
        if (!text.isEmpty() && SafeLogSanitizer.isSensitive(text)) return "[redacted]";
        return text.length() > MAX_IDENTIFIER_LENGTH ? text.substring(0, MAX_IDENTIFIER_LENGTH) : text;
    }

    /** action: solo un formato técnico seguro (letras/dígitos/guion bajo/punto) — cualquier otra cosa tiene sus caracteres inseguros eliminados, nunca se descarta/rechaza de plano. */
    private String sanitizeAction(String value) {
        String text = safeText(value, "unknown");
        if (SAFE_ACTION_PATTERN.matcher(text).matches()) return text;
        String stripped = text.replaceAll("[^A-Za-z0-9_.]", "-");
        if (stripped.isBlank()) return "unknown";
        return stripped.length() > 80 ? stripped.substring(0, 80) : stripped;
    }

    /** El mismo contrato de sanitizar-y-acotar que el manejo del header entrante de TraceIdFilter — se mantiene consistente para que un traceId auditado siempre coincida con el que quien llama podría buscar en los logs. */
    private String sanitizeTraceId(String value) {
        if (value == null || value.isBlank()) return "";
        String sanitized = value.trim().replaceAll("[^a-zA-Z0-9._:-]", "-");
        return sanitized.length() > MAX_TRACE_ID_LENGTH ? sanitized.substring(0, MAX_TRACE_ID_LENGTH) : sanitized;
    }

    private AuditEventResponseDto toResponse(DomainAuditEvent event) {
        return new AuditEventResponseDto(
            event.id(),
            event.actor(),
            event.action(),
            event.entityId(),
            event.traceId(),
            event.timestamp(),
            event.metadata()
        );
    }
}
