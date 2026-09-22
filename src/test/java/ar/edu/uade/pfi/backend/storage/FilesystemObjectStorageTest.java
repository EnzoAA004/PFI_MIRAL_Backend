package ar.edu.uade.pfi.backend.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemObjectStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void putGetRoundTripPreservesBytesAndComputesChecksum() {
        FilesystemObjectStorage storage = new FilesystemObjectStorage(tempDir);
        byte[] content = "synthetic-dicom-bytes".getBytes(StandardCharsets.UTF_8);

        StoredObject stored = storage.put("studies/s1/inputs/a1/study.zip", content, "application/zip");

        assertEquals("filesystem", stored.storageProvider());
        assertEquals(content.length, stored.sizeBytes());
        assertFalse(stored.sha256().isBlank());
        assertTrue(storage.exists("studies/s1/inputs/a1/study.zip"));
        assertArrayEquals(content, storage.get("studies/s1/inputs/a1/study.zip").orElseThrow());
    }

    @Test
    void statReturnsPersistedMetadata() {
        FilesystemObjectStorage storage = new FilesystemObjectStorage(tempDir);
        byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
        StoredObject stored = storage.put("studies/s1/outputs/r1/mask.png", content, "image/png");

        StoredObject statted = storage.stat("studies/s1/outputs/r1/mask.png").orElseThrow();

        assertEquals(stored.sha256(), statted.sha256());
        assertEquals("image/png", statted.contentType());
        assertEquals(3, statted.sizeBytes());
    }

    @Test
    void getReturnsEmptyForMissingObject() {
        FilesystemObjectStorage storage = new FilesystemObjectStorage(tempDir);
        assertTrue(storage.get("studies/none/inputs/none/none.zip").isEmpty());
        assertFalse(storage.exists("studies/none/inputs/none/none.zip"));
    }

    @Test
    void deleteRemovesObjectAndSidecar() {
        FilesystemObjectStorage storage = new FilesystemObjectStorage(tempDir);
        storage.put("studies/s1/inputs/a1/study.zip", "x".getBytes(StandardCharsets.UTF_8), "application/zip");

        storage.delete("studies/s1/inputs/a1/study.zip");

        assertFalse(storage.exists("studies/s1/inputs/a1/study.zip"));
    }

    @Test
    void rejectsObjectKeyEscapingRoot() {
        FilesystemObjectStorage storage = new FilesystemObjectStorage(tempDir);
        assertThrows(ObjectStorageException.class,
            () -> storage.put("../escape.zip", "x".getBytes(StandardCharsets.UTF_8), "application/zip"));
    }

    @Test
    void detectsCorruptionOnReadAfterExternalOverwrite() throws Exception {
        FilesystemObjectStorage storage = new FilesystemObjectStorage(tempDir);
        storage.put("studies/s1/inputs/a1/study.zip", "original".getBytes(StandardCharsets.UTF_8), "application/zip");
        Path onDisk = tempDir.resolve("studies/s1/inputs/a1/study.zip");
        Files.write(onDisk, "tampered".getBytes(StandardCharsets.UTF_8));

        ObjectStorageException ex = assertThrows(ObjectStorageException.class,
            () -> storage.get("studies/s1/inputs/a1/study.zip"));
        assertTrue(ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("checksum"));
    }

    @Test
    void rehydratesAfterSimulatedRestartUsingANewStorageInstanceOnTheSameRoot() {
        FilesystemObjectStorage first = new FilesystemObjectStorage(tempDir);
        byte[] content = "restart-me".getBytes(StandardCharsets.UTF_8);
        StoredObject stored = first.put("studies/s1/inputs/a1/study.zip", content, "application/zip");

        // A brand-new instance over the same durable root simulates a process restart:
        // nothing in FilesystemObjectStorage itself is in-memory state.
        FilesystemObjectStorage afterRestart = new FilesystemObjectStorage(tempDir);

        assertArrayEquals(content, afterRestart.get("studies/s1/inputs/a1/study.zip").orElseThrow());
        assertEquals(stored.sha256(), afterRestart.stat("studies/s1/inputs/a1/study.zip").orElseThrow().sha256());
    }
}
