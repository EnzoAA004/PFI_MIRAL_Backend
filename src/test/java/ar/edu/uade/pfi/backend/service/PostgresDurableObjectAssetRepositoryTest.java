package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.DurableObjectAsset;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresDurableObjectAssetRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("pfi_p0b_durable_assets")
        .withUsername("pfi")
        .withPassword("pfi");

    private PostgresDurableObjectAssetRepository repository() {
        return new PostgresDurableObjectAssetRepository(jdbcUrl(), true);
    }

    private String jdbcUrl() {
        return postgres.getJdbcUrl() + "&user=" + postgres.getUsername() + "&password=" + postgres.getPassword();
    }

    @Test
    void savesAndFindsByAssetIdAndByStudyId() {
        PostgresDurableObjectAssetRepository repo = repository();
        String studyId = "study-" + UUID.randomUUID();
        DurableObjectAsset asset = new DurableObjectAsset(
            UUID.randomUUID().toString(), studyId, null, null,
            "studies/" + studyId + "/inputs/a1/study.zip", "application/zip", 42L, "deadbeef",
            "study_input", "filesystem", "study.zip", Instant.now());

        repo.save(asset);

        DurableObjectAsset found = repo.findById(asset.assetId()).orElseThrow();
        assertEquals(asset.objectKey(), found.objectKey());
        assertEquals(asset.sha256(), found.sha256());

        List<DurableObjectAsset> byStudy = repo.findByStudyId(studyId);
        assertEquals(1, byStudy.size());
    }

    @Test
    void findsLatestStudyInputAmongMultipleUploadsForSameStudy() throws InterruptedException {
        PostgresDurableObjectAssetRepository repo = repository();
        String studyId = "study-" + UUID.randomUUID();
        DurableObjectAsset first = new DurableObjectAsset(
            UUID.randomUUID().toString(), studyId, null, null,
            "studies/" + studyId + "/inputs/a1/study.zip", "application/zip", 10L, "aaaa",
            "study_input", "filesystem", "study.zip", Instant.now().minusSeconds(60));
        DurableObjectAsset second = new DurableObjectAsset(
            UUID.randomUUID().toString(), studyId, null, null,
            "studies/" + studyId + "/inputs/a2/study.zip", "application/zip", 20L, "bbbb",
            "study_input", "filesystem", "study.zip", Instant.now());
        repo.save(first);
        repo.save(second);

        DurableObjectAsset latest = repo.findLatestStudyInput(studyId).orElseThrow();

        assertEquals(second.assetId(), latest.assetId());
        assertTrue(latest.createdAt().isAfter(first.createdAt()));
    }
}
