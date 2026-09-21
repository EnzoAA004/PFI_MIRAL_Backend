package ar.edu.uade.pfi.backend.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.AiDiscLevelResult;
import ar.edu.uade.pfi.backend.domain.AiDiscLevelReview;
import ar.edu.uade.pfi.backend.domain.AiMeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.AiMeasurementResult;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRun;
import ar.edu.uade.pfi.backend.domain.AiProductAnalysisRunReview;
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
 * Stage 78: exercises the real Postgres-backed persistence path for
 * {@code domain_ai_analysis_run_reviews}/{@code domain_ai_disc_level_reviews}/
 * {@code domain_ai_measurement_corrections} — no real AI Module/FastAPI/GPU involved.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresStudyRepositoryAiProductAnalysisReviewTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("pfi_p78")
        .withUsername("pfi")
        .withPassword("pfi");

    private PostgresStudyRepository repository;
    private Study study;
    private AiProductAnalysisRun run;
    private AiDiscLevelResult discLevel;
    private AiMeasurementResult measurement;

    @BeforeEach
    void setUp() throws Exception {
        repository = new PostgresStudyRepository(
            new ObjectMapper(),
            postgres.getJdbcUrl() + "&user=" + postgres.getUsername() + "&password=" + postgres.getPassword(),
            true
        );
        truncateDomainTables();
        Instant now = Instant.now();
        study = repository.saveStudy(new Study(UUID.randomUUID().toString(), "CASE-P78-" + UUID.randomUUID(), "registered", now, now));

        run = new AiProductAnalysisRun(
            UUID.randomUUID().toString(), study.id(), study.caseId(), "pfi.ai-product-result.v1",
            "post-e50-ai-product-freeze-v1", "completed", true, true, false, "trace-1",
            "input-sag-1", "input-ax-1", List.of(), null, now, now
        );
        discLevel = new AiDiscLevelResult(
            UUID.randomUUID().toString(), run.id(), 0, "L4-L5", "localized", "67B2", true, false,
            "paired", false, true, true, "blocked_preprocessing_parity", false
        );
        measurement = new AiMeasurementResult(UUID.randomUUID().toString(), run.id(), discLevel.id(), "sagittal", "L4-L5", "disc", 12.3, 5.0, 3.0, true);
        repository.saveAiProductAnalysisRun(run, List.of(discLevel), List.of(measurement));
    }

    @Test
    void upsertRunReviewInsertsThenUpdatesInPlace() throws Exception {
        Instant firstReviewedAt = Instant.parse("2026-08-25T10:00:00Z");
        repository.upsertAiProductAnalysisRunReview(run.id(), "accepted", "ok", "doctor-1", firstReviewedAt);

        Optional<AiProductAnalysisRunReview> first = repository.findAiProductAnalysisRunReview(run.id());
        assertTrue(first.isPresent());
        assertEquals("accepted", first.get().reviewStatus());

        Instant secondReviewedAt = Instant.parse("2026-08-25T11:00:00Z");
        repository.upsertAiProductAnalysisRunReview(run.id(), "observed", "cambio de idea", "doctor-1", secondReviewedAt);

        Optional<AiProductAnalysisRunReview> second = repository.findAiProductAnalysisRunReview(run.id());
        assertEquals("observed", second.get().reviewStatus());
        assertEquals("cambio de idea", second.get().reviewComment());
        // Still exactly one row for this run -- upserted, not appended.
        assertEquals(1, countRows("domain_ai_analysis_run_reviews", "analysis_run_id", run.id()));
    }

    @Test
    void discLevelReviewIsScopedToItsOwnRow() {
        repository.upsertAiDiscLevelReview(discLevel.id(), "accepted", null, "doctor-1", Instant.now());

        List<AiDiscLevelReview> reviews = repository.findAiDiscLevelReviewsByAnalysisRunId(run.id());
        assertEquals(1, reviews.size());
        assertEquals("accepted", reviews.get(0).reviewStatus());
        assertEquals(discLevel.id(), reviews.get(0).discLevelResultId());

        // The run-level review is untouched by the disc-level review.
        assertTrue(repository.findAiProductAnalysisRunReview(run.id()).isEmpty());
    }

    @Test
    void measurementCorrectionsAreAppendOnly() {
        AiMeasurementCorrection first = new AiMeasurementCorrection(
            UUID.randomUUID().toString(), measurement.id(), run.id(), "areaMm2", 12.3, 11.8, "mm2", "primer ajuste", "doctor-1", Instant.parse("2026-08-25T10:00:00Z")
        );
        AiMeasurementCorrection second = new AiMeasurementCorrection(
            UUID.randomUUID().toString(), measurement.id(), run.id(), "areaMm2", 12.3, 11.5, "mm2", "segundo ajuste", "doctor-1", Instant.parse("2026-08-25T11:00:00Z")
        );
        repository.saveAiMeasurementCorrection(first);
        repository.saveAiMeasurementCorrection(second);

        List<AiMeasurementCorrection> corrections = repository.findAiMeasurementCorrectionsByAnalysisRunId(run.id());
        assertEquals(2, corrections.size(), "both corrections must remain on record");

        // The AI's original measurement row itself was never touched.
        AiMeasurementResult stillOriginal = repository.findAiMeasurementResultsByAnalysisRunId(run.id()).get(0);
        assertEquals(12.3, stillOriginal.areaMm2());
    }

    @Test
    void deletingTheAnalysisRunCascadesToReviewsAndCorrections() throws Exception {
        repository.upsertAiProductAnalysisRunReview(run.id(), "accepted", null, "doctor-1", Instant.now());
        repository.upsertAiDiscLevelReview(discLevel.id(), "accepted", null, "doctor-1", Instant.now());
        repository.saveAiMeasurementCorrection(new AiMeasurementCorrection(
            UUID.randomUUID().toString(), measurement.id(), run.id(), "areaMm2", 12.3, 11.8, "mm2", null, "doctor-1", Instant.now()
        ));

        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("DELETE FROM domain_ai_analysis_runs WHERE id = ?::uuid")) {
            statement.setString(1, run.id());
            statement.executeUpdate();
        }

        assertTrue(repository.findAiProductAnalysisRunReview(run.id()).isEmpty());
        assertTrue(repository.findAiDiscLevelReviewsByAnalysisRunId(run.id()).isEmpty());
        assertTrue(repository.findAiMeasurementCorrectionsByAnalysisRunId(run.id()).isEmpty());
    }

    @Test
    void invalidReviewStatusIsRejectedByTheCheckConstraint() {
        assertTrue(assertThrowsIllegalState(() -> repository.upsertAiProductAnalysisRunReview(run.id(), "diagnosed", null, "doctor-1", Instant.now())));
    }

    private boolean assertThrowsIllegalState(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalStateException ex) {
            return true;
        }
    }

    private int countRows(String table, String column, String value) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?::uuid")) {
            statement.setString(1, value);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void truncateDomainTables() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("""
                TRUNCATE domain_ai_measurement_corrections, domain_ai_disc_level_reviews, domain_ai_analysis_run_reviews,
                         domain_ai_measurement_results, domain_ai_disc_level_results, domain_ai_analysis_runs,
                         domain_audit_events, domain_review_corrections, domain_run_artifacts,
                         domain_study_runs, domain_input_resources, domain_studies
                RESTART IDENTITY CASCADE
                """);
        }
    }
}
