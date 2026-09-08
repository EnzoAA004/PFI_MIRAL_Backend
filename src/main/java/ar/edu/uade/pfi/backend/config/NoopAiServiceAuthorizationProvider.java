package ar.edu.uade.pfi.backend.config;

import java.util.Optional;

public class NoopAiServiceAuthorizationProvider implements AiServiceAuthorizationProvider {
    @Override
    public Optional<String> authorizationHeader() {
        return Optional.empty();
    }
}
