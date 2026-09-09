package ar.edu.uade.pfi.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SecurityStartupValidatorTest {
    private static final String STRONG_SECRET = "a-sufficiently-long-random-production-secret-value-1234";
    private static final String EXACT_ORIGIN = "https://pfi-mvp-test-enzo-frontend.vercel.app";

    @Test
    void nonProductionProfileNeverBlocksStartupRegardlessOfConfig() {
        MockEnvironment env = new MockEnvironment();
        assertDoesNotThrow(() -> validator(env, "", false, true, true, "memory", "", "*", true).validate());
    }

    @Test
    void productionWithBlankSecretFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), "", true, false, false, "postgres", EXACT_ORIGIN, "", false).validate());
    }

    @Test
    void productionWithDemoDefaultSecretFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), "pfi-demo-change-me-2026", true, false, false, "postgres", EXACT_ORIGIN, "", false).validate());
    }

    @Test
    void productionWithWeakShortSecretFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), "too-short", true, false, false, "postgres", EXACT_ORIGIN, "", false).validate());
    }

    @Test
    void prodAliasIsAlsoTreatedAsProduction() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class,
            () -> validator(env, "", true, false, false, "postgres", EXACT_ORIGIN, "", false).validate());
    }

    @Test
    void productionWithAuthDisabledFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), STRONG_SECRET, false, false, false, "postgres", EXACT_ORIGIN, "", false).validate());
    }

    @Test
    void productionWithDemoEnabledFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), STRONG_SECRET, true, true, false, "postgres", EXACT_ORIGIN, "", false).validate());
    }

    @Test
    void productionWithExposeDevCodesTrueFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), STRONG_SECRET, true, false, true, "postgres", EXACT_ORIGIN, "", false).validate());
    }

    @Test
    void productionWithExposeDevCodesFalseStartsCleanly() {
        assertDoesNotThrow(
            () -> validator(production(), STRONG_SECRET, true, false, false, "postgres", EXACT_ORIGIN, "", false).validate());
    }

    @Test
    void productionWithoutPostgresPersistenceFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), STRONG_SECRET, true, false, false, "memory", EXACT_ORIGIN, "", false).validate());
    }

    @Test
    void productionWithoutHttpsOriginFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), STRONG_SECRET, true, false, false, "postgres", "", "", false).validate());
    }

    @Test
    void productionWithLiteralWildcardOriginFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), STRONG_SECRET, true, false, false, "postgres", "*", "", false).validate());
    }

    @Test
    void productionWithPatternsButPreviewNotAllowedFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> validator(production(), STRONG_SECRET, true, false, false, "postgres", EXACT_ORIGIN, "https://*.vercel.app", false).validate());
    }

    @Test
    void productionWithPatternsAndPreviewExplicitlyAllowedStartsCleanly() {
        assertDoesNotThrow(() -> validator(
            production(), STRONG_SECRET, true, false, false, "postgres", EXACT_ORIGIN, "https://*.vercel.app", true).validate());
    }

    @Test
    void productionWithExactHttpsOriginAndStrongSecretStartsCleanly() {
        assertDoesNotThrow(
            () -> validator(production(), STRONG_SECRET, true, false, false, "postgres", EXACT_ORIGIN, "", false).validate());
    }

    // --- Acceso al modulo de IA privado (cierre del hueco fail-open de PFI_AI_AUTH_MODE) ---

    private static final String AI_ORIGIN = "https://pfi-ai-module-abc123-uc.a.run.app";

    @Test
    void localRuntimeWithExplicitNoopIsAllowed() {
        assertDoesNotThrow(() -> aiValidator(new MockEnvironment(), "none", "", false).validate());
    }

    @Test
    void googleIdTokenWithValidAudiencePasses() {
        assertDoesNotThrow(() -> aiValidator(new MockEnvironment(), "google-id-token", AI_ORIGIN, true).validate());
    }

    @Test
    void googleIdTokenInCloudRuntimeRequiresAudience() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> aiValidator(new MockEnvironment(), "google-id-token", "", true).validate());
        assertTrue(ex.getMessage().contains("PFI_AI_SERVICE_AUDIENCE"), ex.getMessage());
    }

    @Test
    void googleIdTokenWithAudienceCarryingAPathFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> aiValidator(new MockEnvironment(), "google-id-token", AI_ORIGIN + "/v1/product", true).validate());
    }

    /** El hueco original: modo sin declarar en un runtime cloud/demo caia en Noop en silencio. */
    @Test
    void cloudRuntimeWithUndeclaredAuthModeFailsStartup() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> aiValidator(new MockEnvironment(), "", "", true).validate());
        assertTrue(ex.getMessage().contains("PFI_AI_AUTH_MODE"), ex.getMessage());
    }

    @Test
    void productionWithUndeclaredAuthModeFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> aiValidator(production(), "", "", false).validate());
    }

    /** Configuracion contradictoria: se exige IA privada pero se deja el modo en "none". */
    @Test
    void cloudRuntimeRequiringPrivateAiButDeclaringNoneFailsStartup() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> aiValidator(new MockEnvironment(), "none", "", true).validate());
        assertTrue(ex.getMessage().contains("google-id-token"), ex.getMessage());
    }

    @Test
    void unsupportedAuthModeFailsStartup() {
        assertThrows(IllegalStateException.class,
            () -> aiValidator(new MockEnvironment(), "mutual-tls", "", false).validate());
    }

    /**
     * Ningun mensaje de arranque puede filtrar el valor configurado: el operador ve que falta o
     * que es invalido, nunca el secreto ni la audience concreta.
     */
    @Test
    void aiAuthFailureMessagesNeverEchoConfiguredValues() {
        String secretish = "super-secret-audience-value";
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> aiValidator(new MockEnvironment(), "google-id-token", secretish, true).validate());
        assertFalse(ex.getMessage().contains(secretish), ex.getMessage());
    }

    private SecurityStartupValidator aiValidator(
        MockEnvironment env, String aiAuthMode, String aiAudience, boolean privateAiRequired
    ) {
        return new SecurityStartupValidator(
            env, STRONG_SECRET, true, false, false, "postgres", EXACT_ORIGIN, "", false,
            aiAuthMode, aiAudience, "http://localhost:8000", privateAiRequired
        );
    }

    private MockEnvironment production() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        return env;
    }

    private SecurityStartupValidator validator(
        MockEnvironment env,
        String jwtSecret,
        boolean authEnabled,
        boolean demoEnabled,
        boolean exposeDevCodes,
        String persistenceMode,
        String corsAllowedOrigins,
        String corsAllowedOriginPatterns,
        boolean allowPreviewPatterns
    ) {
        return new SecurityStartupValidator(
            env, jwtSecret, authEnabled, demoEnabled, exposeDevCodes,
            persistenceMode, corsAllowedOrigins, corsAllowedOriginPatterns, allowPreviewPatterns
        );
    }
}
