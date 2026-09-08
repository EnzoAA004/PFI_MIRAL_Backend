package ar.edu.uade.pfi.backend.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;
import java.io.IOException;
import java.util.Optional;

public class GoogleIdTokenAiServiceAuthorizationProvider implements AiServiceAuthorizationProvider {
    private final IdTokenCredentials credentials;

    public GoogleIdTokenAiServiceAuthorizationProvider(String audience) {
        this(loadApplicationDefaultIdTokenCredentials(audience));
    }

    GoogleIdTokenAiServiceAuthorizationProvider(IdTokenCredentials credentials) {
        this.credentials = credentials;
    }

    @Override
    public Optional<String> authorizationHeader() {
        try {
            credentials.refreshIfExpired();
            String token = credentials.getAccessToken() == null ? null : credentials.getAccessToken().getTokenValue();
            if (token == null || token.isBlank()) {
                throw new AiServiceAuthorizationException();
            }
            return Optional.of("Bearer " + token);
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof AiServiceAuthorizationException authorizationException) {
                throw authorizationException;
            }
            throw new AiServiceAuthorizationException(ex);
        }
    }

    private static IdTokenCredentials loadApplicationDefaultIdTokenCredentials(String audience) {
        try {
            GoogleCredentials adc = GoogleCredentials.getApplicationDefault();
            if (!(adc instanceof IdTokenProvider idTokenProvider)) {
                throw new AiServiceAuthorizationException();
            }
            return IdTokenCredentials.newBuilder()
                .setIdTokenProvider(idTokenProvider)
                .setTargetAudience(audience)
                .build();
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof AiServiceAuthorizationException authorizationException) {
                throw authorizationException;
            }
            throw new AiServiceAuthorizationException(ex);
        }
    }
}
