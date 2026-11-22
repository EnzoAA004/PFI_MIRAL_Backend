package ar.edu.uade.pfi.backend.storage;

import java.util.Optional;

/**
 * Contrato de almacenamiento durable de objetos binarios usado para inputs de estudios crudos, salidas de IA y
 * reportes. Las object keys nunca llevan PHI (nombre del paciente, DNI): solo studyId/runId/reportId
 * más un assetId generado, según {@link ObjectKeys}.
 *
 * <p>Replica el {@code ObjectStorage} del módulo de IA (ai_service/pfi_ai_service/object_storage.py)
 * para que ambos servicios compartan un único contrato de almacenamiento y puedan testearse tras un reinicio de la misma forma.
 */
public interface ObjectStorage {

    StoredObject put(String objectKey, byte[] content, String contentType);

    Optional<byte[]> get(String objectKey);

    boolean exists(String objectKey);

    Optional<StoredObject> stat(String objectKey);

    void delete(String objectKey);

    /** Identificador del proveedor persistido junto a los metadatos: {@code filesystem} o {@code gcs}. */
    String providerName();
}
