package ar.edu.uade.pfi.backend.config;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Fail-closed validation for private self-hosted LLM runtimes. */
@Component
public class LlmServiceStartupValidator {
    private final String authMode;
    private final String audience;
    private final boolean privateRequired;

    public LlmServiceStartupValidator(
            @Value("${pfi.llm.auth-mode:}") String authMode,
            @Value("${pfi.llm.audience:}") String audience,
            @Value("${pfi.llm.private-required:false}") boolean privateRequired) {
        this.authMode = authMode;
        this.audience = audience;
        this.privateRequired = privateRequired;
    }

    @PostConstruct
    void validate() {
        AiServiceAuthMode mode;
        try {
            mode = AiServiceAuthMode.fromConfigValue(authMode);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("pfi.llm.auth-mode (PFI_LLM_AUTH_MODE) must be none or google-id-token", ex);
        }
        if (privateRequired && mode != AiServiceAuthMode.GOOGLE_ID_TOKEN) {
            throw new IllegalStateException("pfi.llm.auth-mode must be google-id-token when PFI_LLM_PRIVATE_REQUIRED=true");
        }
        if (mode != AiServiceAuthMode.GOOGLE_ID_TOKEN) return;
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException("pfi.llm.audience (PFI_LLM_SERVICE_AUDIENCE) is required for google-id-token auth");
        }
        try {
            URI uri = new URI(audience.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath();
            if (uri.getHost() == null || (!scheme.equals("http") && !scheme.equals("https"))
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (path != null && !path.isBlank() && !path.equals("/"))) {
                throw new IllegalArgumentException();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("pfi.llm.audience must be an absolute HTTP(S) origin without path, query or fragment", ex);
        }
    }
}
