package ar.edu.uade.pfi.backend.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.Study;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Stage 76-Y: exercises the real Postgres-backed persistence path for
 * {@code domain_ai_analysis_runs}/{@code domain_ai_disc_level_results}/{@code domain_ai_measurement_results}
 * — no real AI Module/FastAPI/GPU is involved, only Testcontainers Postgres and the migration
 * already shipped in {@code docs/migrations}.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresStudyRepositoryAiProductAnalysisTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("pfi_p76")
        .withUsername("pfi")
        .withPassword("pfi");

    private PostgresStudyRepository repository;
    private Study study;

    @BeforeEach
    void setUp() throws Exception {
        repository = new PostgresStudyRepository(
            new ObjectMapper(),
            postgres.getJdbcUrl() + "&user=" + postgres.getUsername() + "&password=" + postgres.getPassword(),
            true
        );
        truncateDomainTables();
        Instant now = Instant.now();
        study = repository.saveStudy(new Study(UUID.randomUUID().toString(), "CASE-P76-" + UUID.randomUUID(), "registered", now, now));
    }

    private AiProductAnalysisRun buildRun(String status) {
        return buildRun(status, Instant.now());
    }

    /**
     * {@code createdAt} is accepted explicitly so tests asserting ORDER BY created_at DESC
     * (e.g. {@link #multipleRunsAllowedForTheSameStudyNoUniqueConstraintOnStudyId}) don't rely on
     * two back-to-back {@code Instant.now()} calls landing in different timestamp buckets.
     */
    private AiProductAnalysisRun buildRun(String status, Instant createdAt) {
        return new AiProductAnalysisRun(
            UUID.randomUUID().toString(), study.id(), study.caseId(), "pfi.ai-product-result.v1",
            "post-e50-ai-product-freeze-v1", status, true, true, false, "trace-1",
            "input-sag-1", "input-ax-1", List.of("67B2_RUNTIME_PARITY_NOT_PROVEN"), null, createdAt, createdAt
        );
    }

    @Test
    void persistsAndReadsBackRunWithDiscLevelsAndMeasurements() {
        AiProductAnalysisRun run = buildRun("completed");
        AiDiscLevelResult level = new AiDiscLevelResult(
            UUID.randomUUID().toString(), run.id(), 0, "L4-L5", "localized", "67B2", true, false,
            "paired", true, false, false, "blocked_preprocessing_parity", false
        );
        AiMeasurementResult measurement = new AiMeasurementResult(
            UUID.randomUUID().toString(), run.id(), level.id(), "sagittal", "L4-L5", "disc", 120.5, 12.1, 8.4, true
        );

        repository.saveAiProductAnalysisRun(run, List.of(level), List.of(measurement));

        Optional<AiProductAnalysisRun> persisted = repository.findAiProductAnalysisRunById(run.id());
        assertTrue(persisted.isPresent());
        assertEquals("completed", persisted.get().status());
        assertEquals(List.of("67B2_RUNTIME_PARITY_NOT_PROVEN"), persisted.get().warnings());

        List<AiDiscLevelResult> levels = repository.findAiDiscLevelResultsByAnalysisRunId(run.id());
        assertEquals(1, levels.size());
        assertEquals("L4-L5", levels.get(0).level());

        List<AiMeasurementResult> measurements = repository.findAiMeasurementResultsByAnalysisRunId(run.id());
        assertEquals(1, measurements.size());
        assertEquals(120.5, measurements.get(0).areaMm2());
    }

    @Test
    void nullLevelRoundTripsAsNullNeverAsPlaceholder() {
        AiProductAnalysisRun run = buildRun("partial");
        AiDiscLevelResult abstainedLevel = new AiDiscLevelResult(
            UUID.randomUUID().toString(), run.id(), 0, null, "abstained", "67B2", true, true,
            "not_requested", false, false, false, null, false
        );

        repository.saveAiProductAnalysisRun(run, List.of(abstainedLevel), List.of());

        AiDiscLevelResult persisted = repository.findAiDiscLevelResultsByAnalysisRunId(run.id()).get(0);
        assertNull(persisted.level());
        assertEquals("abstained", persisted.localizationStatus());
        assertTrue(persisted.abstentionPreserved());
    }

    @Test
    void multipleRunsAllowedForTheSameStudyNoUniqueConstraintOnStudyId() {
        AiProductAnalysisRun first = buildRun("completed", Instant.parse("2026-08-25T10:00:00Z"));
        AiProductAnalysisRun second = buildRun("partial", Instant.parse("2026-08-25T11:00:00Z"));

        repository.saveAiProductAnalysisRun(first, List.of(), List.of());
        repository.saveAiProductAnalysisRun(second, List.of(), List.of());

        List<AiProductAnalysisRun> runs = repository.findAiProductAnalysisRunsByStudyId(study.id());
        assertEquals(2, runs.size());
        assertEquals(second.id(), runs.get(0).id());
    }

    @Test
    void failedStatusIsAllowedByTheCheckConstraint() {
        AiProductAnalysisRun run = buildRun("failed");

        repository.saveAiProductAnalysisRun(run, List.of(), List.of());

        assertEquals("failed", repository.findAiProductAnalysisRunById(run.id()).orElseThrow().status());
    }

    @Test
    void invalidStatusIsRejectedByTheCheckConstraint() {
        AiProductAnalysisRun invalid = buildRun("not_a_real_status");

        assertThrows(IllegalStateException.class, () -> repository.saveAiProductAnalysisRun(invalid, List.of(), List.of()));
    }

    @Test
    void aChildInsertFailureRollsBackTheWholeRunNeverHalfPersisted() {
        AiProductAnalysisRun run = buildRun("completed");
        AiMeasurementResult orphanMeasurement = new AiMeasurementResult(
            UUID.randomUUID().toString(), run.id(), UUID.randomUUID().toString(), "sagittal", "L4-L5", "disc", 1.0, 1.0, 1.0, true
        );

        assertThrows(IllegalStateException.class, () -> repository.saveAiProductAnalysisRun(run, List.of(), List.of(orphanMeasurement)));

        assertTrue(repository.findAiProductAnalysisRunById(run.id()).isEmpty());
    }

    @Test
    void deletingTheStudyCascadesToItsAnalysisRuns() throws Exception {
        AiProductAnalysisRun run = buildRun("completed");
        repository.saveAiProductAnalysisRun(run, List.of(), List.of());

        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("DELETE FROM domain_studies WHERE id = ?::uuid")) {
            statement.setString(1, study.id());
            statement.executeUpdate();
        }

        assertTrue(repository.findAiProductAnalysisRunById(run.id()).isEmpty());
    }

    @Test
    void warningsDefaultToEmptyListWhenNoneReported() {
        AiProductAnalysisRun run = new AiProductAnalysisRun(
            UUID.randomUUID().toString(), study.id(), study.caseId(), "pfi.ai-product-result.v1",
            "post-e50-ai-product-freeze-v1", "completed", true, true, false, "trace-1",
            "input-sag-1", null, List.of(), null, Instant.now(), Instant.now()
        );

        repository.saveAiProductAnalysisRun(run, List.of(), List.of());

        assertEquals(List.of(), repository.findAiProductAnalysisRunById(run.id()).orElseThrow().warnings());
    }

    @Test
    void sixtySevenCParityFlagsPersistedLiterallyNeverCleanedUp() {
        AiProductAnalysisRun run = buildRun("partial");

        repository.saveAiProductAnalysisRun(run, List.of(), List.of());

        AiProductAnalysisRun persisted = repository.findAiProductAnalysisRunById(run.id()).orElseThrow();
        assertTrue(persisted.humanReviewRequired());
        assertTrue(persisted.notClinicalDiagnosis());
        assertFalse(persisted.autonomousDiagnosis());
    }

    private void truncateDomainTables() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("""
                TRUNCATE domain_ai_measurement_results, domain_ai_disc_level_results, domain_ai_analysis_runs,
                         domain_audit_events, domain_review_corrections, domain_run_artifacts,
                         domain_study_runs, domain_input_resources, domain_studies
                RESTART IDENTITY CASCADE
                """);
        }
    }
}
