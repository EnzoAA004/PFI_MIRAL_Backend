package ar.edu.uade.pfi.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AiServiceAuthorizationPropertiesTest {
    @Test
    void authModeDefaultsToNone() {
        AiServiceProperties properties = new AiServiceProperties("http://localhost:8000", 60, "v1");

        assertEquals(AiServiceAuthMode.NONE, properties.resolvedAuthMode());
    }

    @Test
    void googleIdTokenModeIsExplicit() {
        AiServiceProperties properties = new AiServiceProperties(
            "https://pfi-ai-module-7cxg46zwea-uc.a.run.app",
            60,
            "v1",
            null,
            "google-id-token",
            "https://pfi-ai-module-7cxg46zwea-uc.a.run.app"
        );

        assertEquals(AiServiceAuthMode.GOOGLE_ID_TOKEN, properties.resolvedAuthMode());
    }

    @Test
    void explicitAudienceRemovesTrailingSlash() {
        AiServiceProperties properties = new AiServiceProperties(
            "https://ignored.example.com",
            60,
            "v1",
            null,
            "google-id-token",
            "https://pfi-ai-module-7cxg46zwea-uc.a.run.app/"
        );

        assertEquals("https://pfi-ai-module-7cxg46zwea-uc.a.run.app", properties.resolvedAudience());
    }

    @Test
    void blankAudienceIsDerivedFromServiceUrlWithoutPath() {
        AiServiceProperties properties = new AiServiceProperties(
            "https://pfi-ai-module-7cxg46zwea-uc.a.run.app/",
            60,
            "v1",
            null,
            "google-id-token",
            ""
        );

        assertEquals("https://pfi-ai-module-7cxg46zwea-uc.a.run.app", properties.resolvedAudience());
    }

    @Test
    void audienceWithPathFailsClosed() {
        AiServiceProperties properties = new AiServiceProperties(
            "https://pfi-ai-module-7cxg46zwea-uc.a.run.app",
            60,
            "v1",
            null,
            "google-id-token",
            "https://pfi-ai-module-7cxg46zwea-uc.a.run.app/v1"
        );

        assertThrows(IllegalArgumentException.class, properties::resolvedAudience);
    }

    @Test
    void unknownAuthModeFailsClosed() {
        AiServiceProperties properties = new AiServiceProperties(
            "http://localhost:8000",
            60,
            "v1",
            null,
            "jwt",
            null
        );

        assertThrows(IllegalArgumentException.class, properties::resolvedAuthMode);
    }
}
