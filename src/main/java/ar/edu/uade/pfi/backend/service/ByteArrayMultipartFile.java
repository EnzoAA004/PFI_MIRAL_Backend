package ar.edu.uade.pfi.backend.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.springframework.lang.NonNull;
import org.springframework.web.multipart.MultipartFile;

/**
 * Wraps bytes read back from durable storage as a {@link MultipartFile} so a
 * reprocess request can reuse {@code AiServiceOperations.uploadStudy} unchanged,
 * exactly as if the client had uploaded the file again.
 */
final class ByteArrayMultipartFile implements MultipartFile {

    private final String name;
    private final String originalFilename;
    private final String contentType;
    private final byte[] content;

    ByteArrayMultipartFile(String name, String originalFilename, String contentType, byte[] content) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.content = content;
    }

    @Override
    @NonNull
    public String getName() {
        return name;
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return content.length == 0;
    }

    @Override
    public long getSize() {
        return content.length;
    }

    @Override
    @NonNull
    public byte[] getBytes() {
        return content;
    }

    @Override
    @NonNull
    public InputStream getInputStream() {
        return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(@NonNull java.io.File dest) throws IOException {
        try (OutputStream out = new java.io.FileOutputStream(dest)) {
            out.write(content);
        }
    }
}
