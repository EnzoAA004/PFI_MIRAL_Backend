package ar.edu.uade.pfi.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LlmServiceStartupValidatorTest {
    private static final String ORIGIN = "https://pfi-llm-qwen-e2e-7cxg46zwea-uc.a.run.app";

    @Test
    void noneModeRemainsAllowedWhenPrivateIsNotRequired() {
        assertDoesNotThrow(() -> validator("none", "", false).validate());
    }

    @Test
    void privateRequiredWithNoneFailsClosed() {
        assertThrows(IllegalStateException.class, () -> validator("none", "", true).validate());
    }

    @Test
    void privateRequiredWithGoogleIdTokenAndOriginPasses() {
        assertDoesNotThrow(() -> validator("google-id-token", ORIGIN, true).validate());
    }

    @Test
    void googleIdTokenWithoutAudienceFails() {
        assertThrows(IllegalStateException.class, () -> validator("google-id-token", "", false).validate());
    }

    @Test
    void invalidAudienceFails() {
        assertThrows(IllegalStateException.class,
            () -> validator("google-id-token", ORIGIN + "/v1/models", true).validate());
    }

    @Test
    void unsupportedModeFails() {
        assertThrows(IllegalStateException.class, () -> validator("bearer", ORIGIN, false).validate());
    }

    private LlmServiceStartupValidator validator(String mode, String audience, boolean privateRequired) {
        return new LlmServiceStartupValidator(mode, audience, privateRequired);
    }
}
