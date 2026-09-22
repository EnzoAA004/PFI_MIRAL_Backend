package ar.edu.uade.pfi.backend.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ar.edu.uade.pfi.backend.config.ObjectStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class ObjectStorageConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsToFilesystemProvider() {
        ObjectStorage storage = ObjectStorageConfig.build(
            new ObjectStorageProperties(null, tempDir.toString(), null));
        assertInstanceOf(FilesystemObjectStorage.class, storage);
        assertEquals("filesystem", storage.providerName());
    }

    @Test
    void buildsGcsProviderWhenExplicitlyConfigured() {
        ObjectStorageException ex = assertThrows(ObjectStorageException.class,
            () -> ObjectStorageConfig.build(new ObjectStorageProperties("gcs", null, "")));
        assertEquals(true, ex.getMessage().contains("PFI_STORAGE_GCS_BUCKET"));
    }

    @Test
    void rejectsUnknownProviderRatherThanFallingBackSilently() {
        assertThrows(ObjectStorageException.class,
            () -> ObjectStorageConfig.build(new ObjectStorageProperties("azure-blob", tempDir.toString(), null)));
    }
}
