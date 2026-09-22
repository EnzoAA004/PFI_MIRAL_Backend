package ar.edu.uade.pfi.backend.storage;

/** Raised on misconfiguration, corruption (checksum mismatch) or an invalid object key. */
public class ObjectStorageException extends RuntimeException {

    public ObjectStorageException(String message) {
        super(message);
    }

    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
