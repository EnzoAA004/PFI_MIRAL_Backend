package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * EXT-04B2: content assertions on the migration file, same pattern
 * {@link StudyRunMigrationContractTest} already established for this repo (no Testcontainers
 * dependency, so it always runs, unlike {@code PostgresStudyRepositoryTest}). A real,
 * clean-database apply was additionally exercised manually against a throwaway Postgres
 * container — see docs/extensions/EXT_04B_BACKEND_FOUNDATION.md, "Migration".
 */
class AiEvidenceRegionMigrationContractTest {
    @Test
    void migrationDefinesEvidenceRegionsAndCorrectionsWithoutAlteringExistingTables() throws Exception {
        String sql = Files.readString(Path.of("docs/migrations/V20260903_019_ai_evidence_regions.sql")).toLowerCase();

        assertTrue(sql.contains("create table domain_ai_evidence_regions"));
        assertTrue(sql.contains("create table domain_ai_evidence_region_corrections"));
        assertTrue(sql.contains("origin text not null check (origin in ('ai', 'professional'))"));
        assertTrue(sql.contains("geometry_type text not null check (geometry_type in ('bbox', 'ellipse', 'polygon', 'mask_reference'))"));
        assertTrue(sql.contains("geometry jsonb not null"));
        assertTrue(sql.contains("coordinate_space_ref jsonb null"));
        assertTrue(sql.contains("confidence double precision null check (confidence is null or (confidence >= 0 and confidence <= 1))"));
        assertTrue(sql.contains("experimental boolean not null default false"));
        assertTrue(sql.contains("references domain_ai_analysis_runs (id) on delete cascade"));
        assertTrue(sql.contains("references domain_ai_disc_level_results (id) on delete cascade"));
        assertTrue(sql.contains("references domain_ai_measurement_results (id) on delete cascade"));
        assertTrue(sql.contains("original_geometry jsonb null"));
        assertTrue(sql.contains("corrected_geometry jsonb not null"));
        assertTrue(sql.contains("idx_ai_evidence_regions_run"));
        assertTrue(sql.contains("idx_ai_evidence_region_corrections_region"));

        // Never a mutable/updatable geometry column, never a raw blob, never a filesystem-shaped
        // column name -- masks only ever travel by asset reference (runId/plane/assetName).
        assertFalse(sql.matches("(?s).*\\b(bytea|blob)\\b.*"));
        assertFalse(sql.contains("file_path"));
        assertFalse(sql.contains("bucket_path"));

        // Existing tables are never touched -- this migration only creates new ones.
        assertFalse(sql.contains("alter table"));
        assertFalse(sql.contains("drop table"));
        assertFalse(sql.contains("drop column"));
    }
}
