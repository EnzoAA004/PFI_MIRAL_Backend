package ar.edu.uade.pfi.backend.storage;

import java.util.Optional;

/**
 * Durable binary object storage contract used for raw study inputs, AI outputs and
 * reports. Object keys never carry PHI (patient name, DNI): only studyId/runId/reportId
 * plus a generated assetId, per {@link ObjectKeys}.
 *
 * <p>Mirrors the AI module's {@code ObjectStorage} (ai_service/pfi_ai_service/object_storage.py)
 * so both services share one storage contract and can be restart-tested the same way.
 */
public interface ObjectStorage {

    StoredObject put(String objectKey, byte[] content, String contentType);

    Optional<byte[]> get(String objectKey);

    boolean exists(String objectKey);

    Optional<StoredObject> stat(String objectKey);

    void delete(String objectKey);

    /** Provider identifier persisted alongside metadata: {@code filesystem} or {@code gcs}. */
    String providerName();
}
