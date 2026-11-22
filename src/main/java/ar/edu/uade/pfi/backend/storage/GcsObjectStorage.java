package ar.edu.uade.pfi.backend.storage;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Proveedor de Cloud Storage. Listo a nivel de código, no desplegado: ningún camino de código de este backend
 * lo llama todavía en producción, y esta clase no realiza llamadas a GCS hasta que un request
 * realmente necesite el proveedor {@code gcs}.
 *
 * <p>La autenticación es solo con Application Default Credentials — nunca un archivo de clave JSON
 * de service account — vía {@link StorageOptions#getDefaultInstance()}. El constructor falla de forma cerrada:
 * un nombre de bucket en blanco lanza excepción de inmediato en lugar de recurrir en silencio al
 * proveedor de filesystem, de modo que un despliegue mal configurado no pueda pasar los chequeos de durabilidad
 * mientras en realidad escribe en ningún lugar durable.
 */
public class GcsObjectStorage implements ObjectStorage {

    private final String bucketName;
    private final Storage client;

    public GcsObjectStorage(String bucketName) {
        if (bucketName == null || bucketName.isBlank()) {
            throw new ObjectStorageException(
                "PFI_STORAGE_GCS_BUCKET es obligatorio cuando PFI_STORAGE_PROVIDER=gcs.");
        }
        this.bucketName = bucketName;
        try {
            this.client = StorageOptions.getDefaultInstance().getService();
        } catch (RuntimeException ex) {
            throw new ObjectStorageException(
                "No se pudieron resolver credenciales ADC para Google Cloud Storage.", ex);
        }
    }

    @Override
    public StoredObject put(String objectKey, byte[] content, String contentType) {
        if (content == null || content.length == 0) {
            throw new ObjectStorageException("No se puede persistir un objeto vacio: " + objectKey);
        }
        try {
            BlobId blobId = BlobId.of(bucketName, objectKey);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(contentType == null ? "application/octet-stream" : contentType)
                .build();
            Blob blob = client.create(blobInfo, content);
            return new StoredObject(objectKey, blob.getContentType(), content.length, sha256(content), providerName());
        } catch (StorageException ex) {
            throw new ObjectStorageException("No se pudo escribir en Google Cloud Storage: " + objectKey, ex);
        }
    }

    @Override
    public Optional<byte[]> get(String objectKey) {
        try {
            Blob blob = client.get(BlobId.of(bucketName, objectKey));
            if (blob == null || !blob.exists()) {
                return Optional.empty();
            }
            byte[] content = blob.getContent();
            verifyChecksum(blob, objectKey, content);
            return Optional.of(content);
        } catch (StorageException ex) {
            throw new ObjectStorageException("No se pudo leer de Google Cloud Storage: " + objectKey, ex);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            Blob blob = client.get(BlobId.of(bucketName, objectKey));
            return blob != null && blob.exists();
        } catch (StorageException ex) {
            throw new ObjectStorageException("No se pudo consultar Google Cloud Storage: " + objectKey, ex);
        }
    }

    @Override
    public Optional<StoredObject> stat(String objectKey) {
        try {
            Blob blob = client.get(BlobId.of(bucketName, objectKey));
            if (blob == null || !blob.exists()) {
                return Optional.empty();
            }
            byte[] content = blob.getContent();
            String checksum = sha256(content);
            return Optional.of(new StoredObject(objectKey, blob.getContentType(), blob.getSize(), checksum, providerName()));
        } catch (StorageException ex) {
            throw new ObjectStorageException("No se pudo leer metadata de Google Cloud Storage: " + objectKey, ex);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.delete(BlobId.of(bucketName, objectKey));
        } catch (StorageException ex) {
            throw new ObjectStorageException("No se pudo eliminar de Google Cloud Storage: " + objectKey, ex);
        }
    }

    @Override
    public String providerName() {
        return "gcs";
    }

    private void verifyChecksum(Blob blob, String objectKey, byte[] content) {
        String crc32c = blob.getCrc32c();
        if (crc32c == null || crc32c.isBlank()) {
            return;
        }
        java.util.zip.CRC32C computed = new java.util.zip.CRC32C();
        computed.update(content);
        String actual = Base64.getEncoder().encodeToString(
            java.nio.ByteBuffer.allocate(4).putInt((int) computed.getValue()).array());
        if (!crc32c.equals(actual)) {
            throw new ObjectStorageException(
                "Checksum de objeto GCS no coincide (corrupcion detectada): " + objectKey);
        }
    }

    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new ObjectStorageException("SHA-256 no disponible en este runtime.", ex);
        }
    }
}
