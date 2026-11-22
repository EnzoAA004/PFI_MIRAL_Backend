package ar.edu.uade.pfi.backend.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;

/**
 * {@link ObjectStorage} respaldado por filesystem. La raíz debe ser un volumen durable montado
 * fuera de la capa efímera del contenedor (un named volume de Docker en compose) — esta
 * clase solo hace cumplir el contrato de almacenamiento, no dónde se monta el volumen.
 *
 * <p>Cada objeto se escribe junto con un archivo sidecar {@code .sha256} para que un posterior
 * {@code get}/{@code stat} pueda detectar corrupción silenciosa y fallar de forma ruidosa en lugar de
 * servir contenido truncado o degradado por bit rot.
 */
public class FilesystemObjectStorage implements ObjectStorage {

    private final Path root;

    public FilesystemObjectStorage(Path root) {
        if (root == null) {
            throw new ObjectStorageException("PFI_STORAGE_LOCAL_ROOT es obligatorio para el proveedor filesystem.");
        }
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException ex) {
            throw new ObjectStorageException("No se pudo crear el root de almacenamiento durable: " + this.root, ex);
        }
    }

    @Override
    public StoredObject put(String objectKey, byte[] content, String contentType) {
        if (content == null || content.length == 0) {
            throw new ObjectStorageException("No se puede persistir un objeto vacio: " + objectKey);
        }
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, content);
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            String checksum = sha256(content);
            writeSidecar(target, checksum, contentType, content.length);
            return new StoredObject(objectKey, contentType, content.length, checksum, providerName());
        } catch (IOException ex) {
            throw new ObjectStorageException("No se pudo escribir el objeto durable: " + objectKey, ex);
        }
    }

    @Override
    public Optional<byte[]> get(String objectKey) {
        Path target = resolve(objectKey);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        try {
            byte[] content = Files.readAllBytes(target);
            verifyChecksum(target, objectKey, content);
            return Optional.of(content);
        } catch (IOException ex) {
            throw new ObjectStorageException("No se pudo leer el objeto durable: " + objectKey, ex);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        return Files.exists(resolve(objectKey));
    }

    @Override
    public Optional<StoredObject> stat(String objectKey) {
        Path target = resolve(objectKey);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        Properties sidecar = readSidecar(target);
        try {
            long size = Files.size(target);
            String checksum = sidecar.getProperty("sha256", "");
            if (!checksum.isBlank()) {
                byte[] content = Files.readAllBytes(target);
                verifyChecksum(target, objectKey, content);
            }
            return Optional.of(new StoredObject(
                objectKey,
                sidecar.getProperty("contentType", "application/octet-stream"),
                size,
                checksum,
                providerName()
            ));
        } catch (IOException ex) {
            throw new ObjectStorageException("No se pudo leer metadata del objeto durable: " + objectKey, ex);
        }
    }

    @Override
    public void delete(String objectKey) {
        Path target = resolve(objectKey);
        try {
            Files.deleteIfExists(target);
            Files.deleteIfExists(sidecarPath(target));
        } catch (IOException ex) {
            throw new ObjectStorageException("No se pudo eliminar el objeto durable: " + objectKey, ex);
        }
    }

    @Override
    public String providerName() {
        return "filesystem";
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new ObjectStorageException("objectKey vacio.");
        }
        Path candidate = root.resolve(objectKey).normalize();
        if (!candidate.startsWith(root)) {
            throw new ObjectStorageException("La clave de objeto escapa del root de almacenamiento: " + objectKey);
        }
        return candidate;
    }

    private void verifyChecksum(Path target, String objectKey, byte[] content) throws IOException {
        Properties sidecar = readSidecar(target);
        String expected = sidecar.getProperty("sha256", "");
        if (expected.isBlank()) {
            return;
        }
        String actual = sha256(content);
        if (!expected.equals(actual)) {
            throw new ObjectStorageException(
                "Checksum de objeto durable no coincide (corrupcion detectada): " + objectKey);
        }
    }

    private void writeSidecar(Path target, String checksum, String contentType, long sizeBytes) throws IOException {
        Properties sidecar = new Properties();
        sidecar.setProperty("sha256", checksum);
        sidecar.setProperty("contentType", contentType == null ? "application/octet-stream" : contentType);
        sidecar.setProperty("sizeBytes", String.valueOf(sizeBytes));
        try (var out = Files.newOutputStream(sidecarPath(target))) {
            sidecar.store(out, "PFI durable object sidecar");
        }
    }

    private Properties readSidecar(Path target) {
        Properties sidecar = new Properties();
        Path path = sidecarPath(target);
        if (Files.exists(path)) {
            try (var in = Files.newInputStream(path)) {
                sidecar.load(in);
            } catch (IOException ex) {
                throw new ObjectStorageException("No se pudo leer el sidecar de metadata: " + path, ex);
            }
        }
        return sidecar;
    }

    private Path sidecarPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".sha256");
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
