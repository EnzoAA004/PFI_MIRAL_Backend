package ar.edu.uade.pfi.backend.web.error;

import java.util.Locale;

/**
 * Catálogo central y estable de cada valor de `code` que la API puede devolver. Los códigos existentes se
 * preservan exactamente como el frontend ya los consume — esto es un catálogo de lo que
 * ya existe más un lugar donde clasificarlo, no un ejercicio de renombrado.
 *
 * Cada entrada lleva su {@link ApiErrorCategory}, si es transitoria por defecto
 * (ver {@link ApiErrorWriter} para saber cómo eso se combina con el status HTTP y el
 * override de POST de inferencia para producir el flag final `retryable`), y un mensaje
 * público fijo usado en lugar de cualquier mensaje dinámico/derivado del upstream en un 5xx (P10-B.1 §1/§6).
 *
 * Nota de auditoría P10-B.1: este catálogo resultó incompleto tras P10-B — varios códigos
 * efectivamente lanzados por {@code RunReviewException}, {@code StudyMetadataException} y
 * {@code AiMultiplanarV2ErrorCodeMapper} faltaban, lo que hacía que {@link #fromCode} recurriera en silencio
 * a {@link #UNKNOWN} para ellos (categoría/retryable incorrectos, aunque el string
 * del código igual llegaba correctamente al body de la respuesta, ya que el body siempre usa el
 * código literal, no el nombre de este enum). Todos los códigos reales que se emiten hoy están ahora presentes
 * — ver {@code ApiErrorCodeCoverageTest}. {@code AI_TIMEOUT} y {@code RUN_REVIEW_ERROR}
 * se mantienen como entradas históricas/reservadas que hoy no emite ningún camino de código
 * (ver la nota de cada entrada) en lugar de eliminarse, ya que eliminar una constante de enum es un
 * riesgo de compatibilidad innecesario sin ningún beneficio.
 *
 * {@code UNKNOWN} es un fallback defensivo para cualquier string de código que no esté (todavía) presente en este
 * catálogo; nunca debe aparecer en el body de una respuesta real en operación normal.
 */
public enum ApiErrorCode {
    AUTHENTICATION_REQUIRED(ApiErrorCategory.AUTHENTICATION, false, "Autenticacion requerida."),
    ACCESS_DENIED(ApiErrorCategory.AUTHORIZATION, false, "No tiene permisos para realizar esta operacion."),
    AUTH_STATE_UNAVAILABLE(ApiErrorCategory.AUTHENTICATION, true, "No fue posible validar el estado actual de la sesion."),
    ADMIN_ACCOUNT_PROTECTED(ApiErrorCategory.SECURITY, false, "Las cuentas administrativas no pueden modificarse mediante el flujo de profesionales."),
    LAST_ADMIN_PROTECTION(ApiErrorCategory.SECURITY, false, "La operacion dejaria al sistema sin un administrador activo."),
    VALIDATION_ERROR(ApiErrorCategory.VALIDATION, false, "Solicitud invalida."),
    BAD_REQUEST(ApiErrorCategory.VALIDATION, false, "Solicitud invalida."),
    NOT_FOUND(ApiErrorCategory.RESOURCE, false, "Recurso no encontrado."),
    STUDY_NOT_FOUND(ApiErrorCategory.RESOURCE, false, "Estudio no encontrado."),
    PATIENT_NOT_FOUND(ApiErrorCategory.RESOURCE, false, "Paciente no encontrado."),
    INVALID_PATIENT_ID(ApiErrorCategory.VALIDATION, false, "Patient UUID invalido."),
    INVALID_PATIENT_REFERENCE(ApiErrorCategory.VALIDATION, false, "Referencia de paciente invalida."),
    INVALID_PATIENT_LIMIT(ApiErrorCategory.VALIDATION, false, "Limite de busqueda invalido."),
    INVALID_REASON_CODE(ApiErrorCategory.VALIDATION, false, "Motivo de asociacion invalido."),
    DUPLICATE_PATIENT_REFERENCE(ApiErrorCategory.RESOURCE, false, "Ya existe un paciente con esa referencia."),
    PATIENT_ASSIGNMENT_CONFLICT(ApiErrorCategory.RESOURCE, false, "La asociacion del estudio cambio."),
    ASSET_CONTENT_UNAVAILABLE(ApiErrorCategory.RESOURCE, false, "Contenido del asset no disponible."),
    /** 409 genérico, p. ej. registro duplicado — no es un código de dominio más específico. */
    CONFLICT(ApiErrorCategory.RESOURCE, false, "Conflicto de estado."),
    DATABASE_UNAVAILABLE(ApiErrorCategory.DATABASE, true, "Base de datos temporalmente no disponible."),
    UPSTREAM_UNAVAILABLE(ApiErrorCategory.AI_UPSTREAM, true, "El modulo de IA no esta disponible temporalmente."),
    /** Reservado/histórico: ningún camino de código actual emite este código literal — AI_MODULE_TIMEOUT es lo que realmente usan los timeouts v2. Se mantiene por estabilidad del catálogo. */
    AI_TIMEOUT(ApiErrorCategory.AI_UPSTREAM, true, "El modulo de IA no respondio a tiempo."),
    AI_CONTRACT_VIOLATION(ApiErrorCategory.AI_CONTRACT, false, "La respuesta del modulo de IA no cumple el contrato esperado."),
    AI_MULTIPLANAR_CONTRACT_VIOLATION(ApiErrorCategory.AI_CONTRACT, false, "La respuesta multiplanar no cumple el contrato esperado."),
    /** Etapa 75: discrepancia de schemaVersion/release.releaseId, o falta una sección requerida, en POST /v1/product/analyze. */
    AI_PRODUCT_CONTRACT_VIOLATION(ApiErrorCategory.AI_CONTRACT, false, "La respuesta del analisis de producto no cumple el contrato esperado."),
    /** Etapa 75: autonomousDiagnosis=true, notClinicalDiagnosis=false o humanReviewRequired=false. Fail closed — nunca se relaja. */
    AI_PRODUCT_SAFETY_VIOLATION(ApiErrorCategory.AI_CONTRACT, false, "La respuesta del analisis de producto no cumple las condiciones de seguridad requeridas."),
    /** Etapa 76-B: el Study no tiene un input sagital/axial analizable para resolver — 422 funcional, no una falla de contrato/upstream. */
    AI_STUDY_INPUT_UNRESOLVED(ApiErrorCategory.VALIDATION, false, "El estudio no tiene entradas analizables disponibles."),

    // ---- Etapa 78: flujo de revisión / corrección profesional sobre AiProductAnalysisRun ----
    AI_PRODUCT_REVIEW_INVALID_STATUS(ApiErrorCategory.VALIDATION, false, "El estado de revision no es valido."),
    AI_PRODUCT_REVIEW_COMMENT_INVALID(ApiErrorCategory.VALIDATION, false, "El comentario de revision no es valido."),
    AI_PRODUCT_CORRECTION_INVALID_VALUE(ApiErrorCategory.VALIDATION, false, "El valor de la correccion no es valido."),

    // ---- Etapa 79: comparación longitudinal entre dos Studies del mismo paciente ----
    LONGITUDINAL_SAME_STUDY(ApiErrorCategory.VALIDATION, false, "No se puede comparar un estudio consigo mismo."),
    LONGITUDINAL_NO_USABLE_ANALYSIS_BASELINE(ApiErrorCategory.VALIDATION, false, "El estudio de referencia no tiene un analisis utilizable."),
    LONGITUDINAL_NO_USABLE_ANALYSIS_CURRENT(ApiErrorCategory.VALIDATION, false, "El estudio actual no tiene un analisis utilizable."),
    COMPARISON_SAME_STUDY(ApiErrorCategory.VALIDATION, false, "No se puede comparar un estudio consigo mismo."),
    COMPARISON_RUN_NOT_FOUND(ApiErrorCategory.RESOURCE, false, "La corrida solicitada no está disponible para este estudio."),
    COMPARISON_NO_ANALYSIS_CURRENT(ApiErrorCategory.VALIDATION, false, "El estudio actual no tiene un análisis inicial."),
    COMPARISON_NO_ANALYSIS_REFERENCE(ApiErrorCategory.VALIDATION, false, "El estudio de referencia no tiene un análisis inicial."),
    COMPARISON_NO_USABLE_MEASUREMENTS_CURRENT(ApiErrorCategory.VALIDATION, false, "El análisis inicial del estudio actual no tiene mediciones utilizables."),
    COMPARISON_NO_USABLE_MEASUREMENTS_REFERENCE(ApiErrorCategory.VALIDATION, false, "El análisis inicial del estudio de referencia no tiene mediciones utilizables."),

    // ---- Etapa 80: chat contextual / LLM autoalojado ----
    CHAT_QUESTION_REQUIRED(ApiErrorCategory.VALIDATION, false, "La pregunta no puede estar vacia."),
    CHAT_QUESTION_TOO_LONG(ApiErrorCategory.VALIDATION, false, "La pregunta supera el largo maximo permitido."),
    LLM_PROVIDER_DISABLED(ApiErrorCategory.AI_UPSTREAM, false, "El asistente conversacional no esta habilitado en este entorno."),
    /** No se reintenta automáticamente (ver el override de POST no idempotente de ApiErrorWriter) -- una llamada de chat no es idempotente desde la perspectiva del usuario. */
    LLM_PROVIDER_UNAVAILABLE(ApiErrorCategory.AI_UPSTREAM, true, "El asistente conversacional no esta disponible en este momento."),
    LLM_PROVIDER_TIMEOUT(ApiErrorCategory.AI_UPSTREAM, true, "El asistente conversacional no respondio a tiempo."),
    LLM_CONTRACT_VIOLATION(ApiErrorCategory.AI_CONTRACT, false, "La respuesta del asistente conversacional no cumple el contrato esperado."),
    CHAT_CONVERSATION_NOT_FOUND(ApiErrorCategory.RESOURCE, false, "Conversacion no encontrada."),
    INPUT_TOO_LARGE(ApiErrorCategory.VALIDATION, false, "El archivo medico supera el tamano maximo permitido para carga."),
    /** Reservado/histórico: ningún camino de código actual emite este código literal — RunReviewException siempre lleva un código más específico (RUN_NOT_FOUND, INVALID_REVIEW_STATUS, ...). Se mantiene como bucket de fallback genérico. */
    RUN_REVIEW_ERROR(ApiErrorCategory.RESOURCE, false, "No fue posible procesar la revision."),
    /** Fallback genérico para una ResponseStatusException con un status 4xx sin mapear — el mensaje lo calcula dinámicamente ApiExceptionHandler, no este catálogo. */
    CLIENT_ERROR(ApiErrorCategory.VALIDATION, false, "Solicitud invalida."),
    INTERNAL_ERROR(ApiErrorCategory.INTERNAL, false, "Error interno del backend."),

    // ---- RunReviewService (RunReviewException) ----
    RUN_NOT_FOUND(ApiErrorCategory.RESOURCE, false, "Run no encontrado."),
    INVALID_REVIEW_STATUS(ApiErrorCategory.VALIDATION, false, "reviewStatus invalido."),
    REVIEWER_REQUIRED(ApiErrorCategory.VALIDATION, false, "Reviewer es obligatorio."),
    REVIEW_COMMENT_REQUIRED(ApiErrorCategory.VALIDATION, false, "El estado requiere un comentario profesional descriptivo."),

    // ---- StudyMetadataService/StudyRunService/PatientHistoryService (StudyMetadataException) ----
    INVALID_SUBJECT_REFERENCE(ApiErrorCategory.VALIDATION, false, "subjectRef invalido."),
    SUBJECT_REFERENCE_CONFLICT(ApiErrorCategory.RESOURCE, false, "El estudio ya tiene una referencia de-identificada distinta."),
    INVALID_REVIEW_PRIORITY(ApiErrorCategory.VALIDATION, false, "reviewPriority invalido."),

    // ---- Errores estructurados del módulo de IA (AiMultiplanarV2ErrorCodeMapper → AiMultiplanarUpstreamException) ----
    AI_INVALID_REQUEST(ApiErrorCategory.VALIDATION, false, "La solicitud enviada al modulo de IA es invalida."),
    AI_NO_PLANE_REQUESTED(ApiErrorCategory.VALIDATION, false, "No se especifico ningun plano para la inferencia."),
    AI_INPUT_NOT_FOUND(ApiErrorCategory.RESOURCE, false, "El input solicitado no esta disponible."),
    AI_MODEL_NOT_FOUND(ApiErrorCategory.RESOURCE, false, "El modelo solicitado no esta disponible."),
    /**
     * Elección de clasificación documentada: AI_CONTRACT en lugar de VALIDATION — una
     * discrepancia de modelo/plano refleja que el propio registro de modelos del módulo de IA no coincide con
     * el contrato que se le pidió cumplir, no un request malformado de quien llama.
     */
    AI_MODEL_PLANE_MISMATCH(ApiErrorCategory.AI_CONTRACT, false, "El modelo solicitado no corresponde al plano solicitado."),
    AI_MODEL_NOT_READY(ApiErrorCategory.AI_UPSTREAM, false, "El modelo solicitado no esta listo para inferencia."),
    AI_CONTRACT_FALLBACK_DISABLED(ApiErrorCategory.AI_CONTRACT, false, "El modulo de IA no permite degradar el contrato solicitado."),
    /** No se reintenta automáticamente en POST — ver el override de POST de inferencia no idempotente de ApiErrorWriter. */
    AI_REAL_INFERENCE_FAILED(ApiErrorCategory.AI_UPSTREAM, false, "La inferencia real del modulo de IA fallo."),
    AI_INVALID_RESPONSE(ApiErrorCategory.AI_CONTRACT, false, "La respuesta del modulo de IA es invalida."),
    AI_UNSUPPORTED_INFERENCE_MODE(ApiErrorCategory.VALIDATION, false, "El modo de inferencia solicitado no esta soportado."),
    // ---- Clasificador subarticular (AiSubarticularErrorCodeMapper) ----
    /**
     * El checkpoint congelado no esta configurado o el artefacto no esta en el entorno.
     * Es transitorio en el sentido operativo —se resuelve montando el .pt— y por eso el
     * modulo de IA responde 503 y no 500.
     */
    AI_SUBARTICULAR_UNAVAILABLE(ApiErrorCategory.AI_UPSTREAM, true, "El clasificador de hallazgos degenerativos no esta disponible."),
    AI_SUBARTICULAR_INVALID_INPUT(ApiErrorCategory.VALIDATION, false, "La coordenada solicitada al clasificador es invalida."),
    /**
     * El artefacto esta pero no es el que el codigo espera: hash distinto del declarado, o
     * state_dict incompatible. Es una falla de contrato entre el release del modelo y el
     * del codigo, no un problema de disponibilidad, y reintentarlo no cambia nada.
     */
    AI_SUBARTICULAR_CHECKPOINT_INVALID(ApiErrorCategory.AI_CONTRACT, false, "El modelo de hallazgos degenerativos no es el esperado."),
    AI_SUBARTICULAR_RUNTIME_ERROR(ApiErrorCategory.AI_UPSTREAM, false, "El clasificador de hallazgos degenerativos fallo."),

    /** Transitorio por naturaleza, pero retryable igual se fuerza a false en un POST de inferencia no idempotente — ver ApiErrorWriter. */
    AI_MODULE_ERROR(ApiErrorCategory.AI_UPSTREAM, true, "El modulo de IA respondio con un error."),
    AI_MODULE_TIMEOUT(ApiErrorCategory.AI_UPSTREAM, true, "El modulo de IA no respondio a tiempo."),

    /** Solo fallback defensivo — nunca debería observarse en una respuesta real. */
    UNKNOWN(ApiErrorCategory.INTERNAL, false, "Error interno del backend.");

    private final ApiErrorCategory category;
    private final boolean transientByDefault;
    private final String publicMessage;

    ApiErrorCode(ApiErrorCategory category, boolean transientByDefault, String publicMessage) {
        this.category = category;
        this.transientByDefault = transientByDefault;
        this.publicMessage = publicMessage;
    }

    public ApiErrorCategory category() {
        return category;
    }

    /** Si este código es transitorio por naturaleza, antes de que apliquen los overrides del status HTTP y del POST de inferencia. */
    public boolean transientByDefault() {
        return transientByDefault;
    }

    /** Mensaje público fijo — nunca derivado del mensaje propio de una excepción, de un body upstream ni de un stack trace. */
    public String publicMessage() {
        return publicMessage;
    }

    public static ApiErrorCode fromCode(String code) {
        if (code == null || code.isBlank()) return UNKNOWN;
        try {
            return valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
