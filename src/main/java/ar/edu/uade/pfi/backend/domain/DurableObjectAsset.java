package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Metadata row for a binary persisted through {@code ObjectStorageService}. The binary
 * itself lives at {@code objectKey} in whichever provider {@code storageProvider} names
 * -- never in this row, and never in Postgres.
 */
public record DurableObjectAsset(
    String assetId,
    String studyId,
    String caseId,
    String runId,
    String reportId,
    String objectKey,
    String contentType,
    long sizeBytes,
    String sha256,
    String artifactType,
    String storageProvider,
    String originalFilename,
    Instant createdAt
) {
    public DurableObjectAsset {
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(studyId, "studyId");
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(artifactType, "artifactType");
        Objects.requireNonNull(storageProvider, "storageProvider");
        Objects.requireNonNull(createdAt, "createdAt");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes debe ser positivo");
        }
    }
}
