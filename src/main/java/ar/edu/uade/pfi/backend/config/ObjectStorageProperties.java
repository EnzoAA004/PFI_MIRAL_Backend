package ar.edu.uade.pfi.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * {@code PFI_STORAGE_PROVIDER} / {@code PFI_STORAGE_LOCAL_ROOT} / {@code PFI_STORAGE_GCS_BUCKET}.
 * Replica los nombres de variables de entorno del propio módulo de IA para que un operador configure ambos servicios
 * de la misma forma (docs/CLOUD_ENVIRONMENT_VARIABLES.md en el repo de IA).
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
