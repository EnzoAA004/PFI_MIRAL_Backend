package ar.edu.uade.pfi.backend.storage;

import ar.edu.uade.pfi.backend.config.ObjectStorageProperties;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the single {@link ObjectStorage} bean for the whole backend from
 * {@code pfi.storage.*}. No fallback: an unknown or misconfigured provider fails
 * application startup rather than silently defaulting to filesystem — see
 * {@link #objectStorage(ObjectStorageProperties)}.
 */
@Configuration
public class ObjectStorageConfig {

    @Bean
    public ObjectStorage objectStorage(ObjectStorageProperties properties) {
        return build(properties);
    }

    static ObjectStorage build(ObjectStorageProperties properties) {
        String provider = properties.resolvedProvider();
        return switch (provider) {
            case "filesystem", "local" -> new FilesystemObjectStorage(resolveLocalRoot(properties));
            case "gcs" -> new GcsObjectStorage(properties.gcsBucket());
            default -> throw new ObjectStorageException(
                "PFI_STORAGE_PROVIDER invalido: " + provider + ". Use filesystem o gcs.");
        };
    }

    private static Path resolveLocalRoot(ObjectStorageProperties properties) {
        String localRoot = properties.localRoot();
        return Path.of(localRoot == null || localRoot.isBlank() ? "/app/storage/object_storage" : localRoot);
    }
}
