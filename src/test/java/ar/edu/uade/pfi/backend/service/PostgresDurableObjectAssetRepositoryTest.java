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
        String caseId = "case-" + UUID.randomUUID();
        DurableObjectAsset asset = new DurableObjectAsset(
            UUID.randomUUID().toString(), studyId, caseId, null, null,
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
        String caseId = "case-" + UUID.randomUUID();
        DurableObjectAsset first = new DurableObjectAsset(
            UUID.randomUUID().toString(), studyId, caseId, null, null,
            "studies/" + studyId + "/inputs/a1/study.zip", "application/zip", 10L, "aaaa",
            "study_input", "filesystem", "study.zip", Instant.now().minusSeconds(60));
        DurableObjectAsset second = new DurableObjectAsset(
            UUID.randomUUID().toString(), studyId, caseId, null, null,
            "studies/" + studyId + "/inputs/a2/study.zip", "application/zip", 20L, "bbbb",
            "study_input", "filesystem", "study.zip", Instant.now());
        repo.save(first);
        repo.save(second);

        DurableObjectAsset latest = repo.findLatestStudyInput(studyId, caseId).orElseThrow();
        assertTrue(repo.findLatestStudyInput(studyId, "other-case").isEmpty());

        assertEquals(second.assetId(), latest.assetId());
        assertTrue(latest.createdAt().isAfter(first.createdAt()));
    }

    @Test
    void repeatedRepositoryOperationsReuseThePoolInsteadOfOpeningANewConnectionEachTime() {
        PostgresDurableObjectAssetRepository repo = repository();
        String studyId = "study-pool-" + UUID.randomUUID();
        String caseId = "case-pool-" + UUID.randomUUID();

        // Cloud P0B/4B: this is the exact pattern (one DriverManager.getConnection()
        // per call) that made every real request to Cloud Run's active revision
        // exhaust the connector's per-connection handshake cost under load. 30
        // sequential save+read cycles must all succeed without the pool growing
        // connection-per-call -- see PfiHikariDataSourceFactoryTest for direct pool
        // bookkeeping assertions.
        for (int i = 0; i < 30; i++) {
            DurableObjectAsset asset = new DurableObjectAsset(
                UUID.randomUUID().toString(), studyId, caseId, null, null,
                "studies/" + studyId + "/inputs/a" + i + "/study.zip", "application/zip",
                (long) (i + 1), "hash-" + i, "study_input", "filesystem", "study.zip", Instant.now());
            repo.save(asset);
            assertTrue(repo.findById(asset.assetId()).isPresent());
        }

        assertEquals(30, repo.findByStudyId(studyId).size());
    }
}
