package ar.edu.uade.pfi.backend.storage;

/** Se lanza ante una mala configuración, corrupción (checksum que no coincide) o una object key inválida. */
public class ObjectStorageException extends RuntimeException {

    public ObjectStorageException(String message) {
        super(message);
    }

    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
