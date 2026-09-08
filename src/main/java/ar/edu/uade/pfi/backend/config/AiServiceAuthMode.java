package ar.edu.uade.pfi.backend.config;

public enum AiServiceAuthMode {
    NONE,
    GOOGLE_ID_TOKEN;

    public static AiServiceAuthMode fromConfigValue(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("none")) {
            return NONE;
        }
        if (value.equalsIgnoreCase("google-id-token")) {
            return GOOGLE_ID_TOKEN;
        }
        throw new IllegalArgumentException("Unsupported AI service auth mode.");
    }
}
