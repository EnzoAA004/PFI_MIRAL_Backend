package ar.edu.uade.pfi.backend.config;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-closed startup gate for production profiles. This never runs (and never blocks
 * `mvn test`) unless SPRING_PROFILES_ACTIVE explicitly contains "production"/"prod" —
 * default/dev/test runs are unaffected. In production it refuses to let the context
 * finish starting if any of: the JWT signing key, auth being enabled, demo mode being
 * disabled, dev verification codes being hidden, PostgreSQL persistence being enabled,
 * or CORS being pinned to real HTTPS origins (no wildcard) does not hold.
 */
@Component
public class SecurityStartupValidator {
    /** Must match TokenService's own default so we can detect "nobody configured a real secret". */
    static final String DEMO_DEFAULT_SECRET = "pfi-demo-change-me-2026";
    static final int MIN_SECRET_BYTES = 32;

    private final Environment environment;
    private final String jwtSecret;
    private final boolean authEnabled;
    private final boolean demoEnabled;
    private final boolean exposeDevCodes;
    private final String persistenceMode;
    private final String corsAllowedOrigins;
    private final String corsAllowedOriginPatterns;
    private final boolean allowPreviewPatterns;
    private final String aiAuthMode;
    private final String aiAudience;
    private final String aiBaseUrl;
    private final boolean privateAiRequired;

    /**
     * Sobrecarga de compatibilidad para los llamadores que no configuran el acceso al modulo de
     * IA privado. Declara {@code none} explicitamente -que es lo que hace application.properties
     * por defecto- y no exige IA privada: equivale a un runtime cuyo modulo de IA no pide
     * credencial.
     */
    public SecurityStartupValidator(
        Environment environment,
        String jwtSecret,
        boolean authEnabled,
        boolean demoEnabled,
        boolean exposeDevCodes,
        String persistenceMode,
        String corsAllowedOrigins,
        String corsAllowedOriginPatterns,
        boolean allowPreviewPatterns
    ) {
        this(environment, jwtSecret, authEnabled, demoEnabled, exposeDevCodes, persistenceMode,
            corsAllowedOrigins, corsAllowedOriginPatterns, allowPreviewPatterns, "none", null, null, false);
    }

    @Autowired
    public SecurityStartupValidator(
        Environment environment,
        @Value("${pfi.auth.jwt-secret:pfi-demo-change-me-2026}") String jwtSecret,
        @Value("${pfi.auth.enabled:true}") boolean authEnabled,
        @Value("${pfi.auth.demo-enabled:false}") boolean demoEnabled,
        @Value("${pfi.auth.expose-dev-codes:false}") boolean exposeDevCodes,
        @Value("${pfi.persistence.mode:memory}") String persistenceMode,
        @Value("${pfi.cors.allowed-origins:}") String corsAllowedOrigins,
        @Value("${pfi.cors.allowed-origin-patterns:}") String corsAllowedOriginPatterns,
        @Value("${pfi.cors.allow-preview-patterns:false}") boolean allowPreviewPatterns,
        @Value("${pfi.ai-service.auth-mode:}") String aiAuthMode,
        @Value("${pfi.ai-service.audience:}") String aiAudience,
        @Value("${pfi.ai-service.base-url:}") String aiBaseUrl,
        @Value("${pfi.ai-service.private-ai-required:false}") boolean privateAiRequired
    ) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
        this.authEnabled = authEnabled;
        this.demoEnabled = demoEnabled;
        this.exposeDevCodes = exposeDevCodes;
        this.persistenceMode = persistenceMode;
        this.corsAllowedOrigins = corsAllowedOrigins;
        this.corsAllowedOriginPatterns = corsAllowedOriginPatterns;
        this.allowPreviewPatterns = allowPreviewPatterns;
        this.aiAuthMode = aiAuthMode;
        this.aiAudience = aiAudience;
        this.aiBaseUrl = aiBaseUrl;
        this.privateAiRequired = privateAiRequired;
    }

    @PostConstruct
    void validate() {
        // Corre antes del corte por perfil: un runtime cloud/demo puede exigir IA privada sin
        // estar marcado como "production".
        validateAiServiceAuthorization();
        if (!isProductionProfile()) {
            return;
        }
        validateJwtSecret();
        validateAuthEnabled();
        validateDemoDisabled();
        validateDevCodesHidden();
        validatePostgresPersistence();
        validateCors();
    }

    private void validateJwtSecret() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            fail("pfi.auth.jwt-secret (PFI_AUTH_JWT_SECRET) is required in production and must not be blank");
        }
        if (DEMO_DEFAULT_SECRET.equals(jwtSecret)) {
            fail("pfi.auth.jwt-secret must not use the repository's demo default value in production");
        }
        int effectiveBytes = jwtSecret.getBytes(StandardCharsets.UTF_8).length;
        if (effectiveBytes < MIN_SECRET_BYTES) {
            fail("pfi.auth.jwt-secret is too short for HS256 in production; need at least "
                + MIN_SECRET_BYTES + " bytes, got " + effectiveBytes);
        }
    }

    private void validateAuthEnabled() {
        if (!authEnabled) {
            fail("pfi.auth.enabled (PFI_AUTH_ENABLED) must be true in production; "
                + "running without AuthFilter is not permitted outside tests/local");
        }
    }

    private void validateDemoDisabled() {
        if (demoEnabled) {
            fail("pfi.auth.demo-enabled (PFI_AUTH_DEMO_ENABLED) must be false in production");
        }
    }

    private void validateDevCodesHidden() {
        if (exposeDevCodes) {
            fail("pfi.auth.expose-dev-codes (PFI_AUTH_EXPOSE_DEV_CODES) must be false in production; "
                + "verification codes must never be returned to clients");
        }
    }

    private void validatePostgresPersistence() {
        if (!"postgres".equalsIgnoreCase(persistenceMode == null ? "" : persistenceMode.trim())) {
            fail("pfi.persistence.mode (PFI_PERSISTENCE_MODE) must be postgres in production");
        }
    }

    private void validateCors() {
        List<String> origins = splitTrimmed(corsAllowedOrigins);
        if (origins.isEmpty()) {
            fail("pfi.cors.allowed-origins (PFI_CORS_ALLOWED_ORIGINS) must configure at least one exact HTTPS origin in production");
        }
        for (String origin : origins) {
            if ("*".equals(origin)) {
                fail("pfi.cors.allowed-origins must not contain a literal \"*\" in production");
            }
            if (!origin.toLowerCase(Locale.ROOT).startsWith("https://")) {
                fail("pfi.cors.allowed-origins must be exact HTTPS origins in production, found a non-HTTPS entry");
            }
        }
        List<String> patterns = splitTrimmed(corsAllowedOriginPatterns);
        if (!patterns.isEmpty() && !allowPreviewPatterns) {
            fail("pfi.cors.allowed-origin-patterns must be empty in production unless "
                + "pfi.cors.allow-preview-patterns (PFI_CORS_ALLOW_PREVIEW_PATTERNS) is explicitly true");
        }
    }

    /**
     * Cierra el hueco fail-open del acceso al modulo de IA privado.
     *
     * <p>Antes, con {@code PFI_AI_AUTH_MODE} sin definir en un runtime cloud/demo, el backend
     * caia en silencio al proveedor Noop y llamaba al modulo de IA sin cabecera Authorization:
     * un despliegue que se creia autenticado no lo estaba, y solo se notaba por los 401/403 del
     * upstream. Ahora un runtime cloud/demo tiene que declarar el modo explicitamente.
     *
     * <p>Un runtime es cloud/demo si el perfil activo es production/prod, o si se declara
     * {@code pfi.ai-service.private-ai-required=true} (PFI_AI_PRIVATE_REQUIRED). Fuera de eso
     * -local y dev- el modo {@code none} sigue siendo valido y no se exige nada.
     */
    private void validateAiServiceAuthorization() {
        String declaredMode = aiAuthMode == null ? "" : aiAuthMode.trim();
        AiServiceAuthMode mode;
        try {
            mode = AiServiceAuthMode.fromConfigValue(declaredMode);
        } catch (IllegalArgumentException ex) {
            fail("pfi.ai-service.auth-mode (PFI_AI_AUTH_MODE) is not a supported value; "
                + "use \"none\" for a local runtime or \"google-id-token\" for a private AI module");
            return;
        }

        boolean cloudRuntime = privateAiRequired || isProductionProfile();

        // En un runtime cloud/demo el modo se declara a mano. Dejarlo sin definir es justamente
        // el caso que caia en Noop sin que nadie se enterara; "none" explicito sigue siendo una
        // eleccion valida, pero tiene que ser una eleccion.
        if (cloudRuntime && declaredMode.isBlank()) {
            fail("pfi.ai-service.auth-mode (PFI_AI_AUTH_MODE) must be declared explicitly in a "
                + "cloud/demo runtime -- \"google-id-token\" for a private AI module, or \"none\" to "
                + "state on purpose that the AI module needs no credential; leaving it unset would "
                + "call the AI module with no Authorization header instead of failing");
        }

        // La bandera de IA privada es la que exige credencial: declarar que se necesita y quedarse
        // en "none" es una configuracion contradictoria, no un default aceptable.
        if (privateAiRequired && mode != AiServiceAuthMode.GOOGLE_ID_TOKEN) {
            fail("pfi.ai-service.auth-mode (PFI_AI_AUTH_MODE) must be \"google-id-token\" when "
                + "pfi.ai-service.private-ai-required (PFI_AI_PRIVATE_REQUIRED) is true; "
                + "\"none\" is only valid for a runtime whose AI module needs no credential");
        }

        if (mode != AiServiceAuthMode.GOOGLE_ID_TOKEN) {
            return;
        }

        // En modo google-id-token la audience tiene que resolver a un origen valido. Se exige
        // declarada y no derivada del base-url: derivarla en la nube esconde un error de config.
        String declaredAudience = aiAudience == null ? "" : aiAudience.trim();
        if (declaredAudience.isBlank()) {
            if (cloudRuntime) {
                fail("pfi.ai-service.audience (PFI_AI_SERVICE_AUDIENCE) is required when "
                    + "PFI_AI_AUTH_MODE=google-id-token in a cloud/demo runtime, and must be the "
                    + "AI module's own origin");
            }
            declaredAudience = aiBaseUrl == null ? "" : aiBaseUrl.trim();
            if (declaredAudience.isBlank()) {
                fail("pfi.ai-service.audience (PFI_AI_SERVICE_AUDIENCE) is required when "
                    + "PFI_AI_AUTH_MODE=google-id-token and no base-url is configured to derive it from");
            }
        }

        try {
            // Reutiliza la normalizacion fail-closed ya probada en AiServiceProperties en vez de
            // duplicar una segunda regla de validacion de audience.
            new AiServiceProperties(null, null, null, null, "google-id-token", declaredAudience).resolvedAudience();
        } catch (IllegalArgumentException ex) {
            fail("pfi.ai-service.audience (PFI_AI_SERVICE_AUDIENCE) must be an absolute HTTP(S) origin "
                + "with no path, query or fragment");
        }
    }

    private List<String> splitTrimmed(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isBlank()).toList();
    }

    private boolean isProductionProfile() {
        for (String profile : environment.getActiveProfiles()) {
            String normalized = profile.toLowerCase(Locale.ROOT);
            if (normalized.equals("production") || normalized.equals("prod")) {
                return true;
            }
        }
        return false;
    }

    private void fail(String sanitizedMessage) {
        throw new IllegalStateException(sanitizedMessage);
    }
}
