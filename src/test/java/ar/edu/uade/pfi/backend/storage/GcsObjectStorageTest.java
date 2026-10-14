package ar.edu.uade.pfi.backend.storage;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * No real GCS calls in this suite (no credentials, no network dependency, no writes to
 * a real bucket) -- only the fail-closed misconfiguration guard, which is exactly the
 * "code-ready, not deployed" contract this provider must uphold.
 */
class GcsObjectStorageTest {

    @Test
    void failsClosedWhenBucketIsBlank() {
        ObjectStorageException ex = assertThrows(ObjectStorageException.class, () -> new GcsObjectStorage(""));
        assertTrue(ex.getMessage().contains("PFI_STORAGE_GCS_BUCKET"));
    }

    @Test
    void failsClosedWhenBucketIsNull() {
        assertThrows(ObjectStorageException.class, () -> new GcsObjectStorage(null));
    }
}
