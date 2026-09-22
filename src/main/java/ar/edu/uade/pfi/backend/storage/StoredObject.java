package ar.edu.uade.pfi.backend.storage;

/** Metadata returned after a durable write, or by {@link ObjectStorage#stat}. */
public record StoredObject(
    String objectKey,
    String contentType,
    long sizeBytes,
    String sha256,
    String storageProvider
) {
}
