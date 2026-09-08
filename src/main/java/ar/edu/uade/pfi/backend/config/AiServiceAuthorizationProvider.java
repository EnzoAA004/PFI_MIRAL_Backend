package ar.edu.uade.pfi.backend.config;

import java.util.Optional;

public interface AiServiceAuthorizationProvider {
    Optional<String> authorizationHeader();
}
