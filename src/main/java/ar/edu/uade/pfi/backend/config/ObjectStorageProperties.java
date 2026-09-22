package ar.edu.uade.pfi.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * {@code PFI_STORAGE_PROVIDER} / {@code PFI_STORAGE_LOCAL_ROOT} / {@code PFI_STORAGE_GCS_BUCKET}.
 * Mirrors the AI module's own env var names so an operator configures both services the
 * same way (docs/CLOUD_ENVIRONMENT_VARIABLES.md in the AI repo).
 */
@ConfigurationProperties(prefix = "pfi.storage")
public record ObjectStorageProperties(String provider, String localRoot, String gcsBucket) {

    @ConstructorBinding
    public ObjectStorageProperties {
    }

    public String resolvedProvider() {
        return provider == null || provider.isBlank() ? "filesystem" : provider.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
