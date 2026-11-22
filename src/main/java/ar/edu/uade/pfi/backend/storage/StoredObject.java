package ar.edu.uade.pfi.backend.storage;

/** Metadatos devueltos tras una escritura durable, o por {@link ObjectStorage#stat}. */
public record StoredObject(
    String objectKey,
    String contentType,
    long sizeBytes,
    String sha256,
    String storageProvider
) {
}
