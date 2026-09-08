package ar.edu.uade.pfi.backend.config;

public class AiServiceAuthorizationException extends RuntimeException {
    public AiServiceAuthorizationException() {
        super("AI service authorization failed.");
    }

    public AiServiceAuthorizationException(Throwable cause) {
        super("AI service authorization failed.", cause);
    }
}
