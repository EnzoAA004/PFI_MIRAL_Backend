-- Cloud P0B: metadata for the durable object storage abstraction (ObjectStorageService).
-- Binaries never live here -- only the object key that resolves them in the configured
-- provider (filesystem today, GCS when deployed). This is deliberately a single table
-- for inputs/outputs/reports (artifact_type discriminates) rather than three tables,
-- since the row shape (who owns it, where the bytes live, how to verify them) is
-- identical across all three; see docs/cloud/CLOUD_DURABLE_STORAGE_DESIGN.md.

CREATE TABLE IF NOT EXISTS domain_durable_object_assets (
    asset_id UUID PRIMARY KEY,
    study_id TEXT NOT NULL,
    run_id TEXT NULL,
    report_id TEXT NULL,
    object_key TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 TEXT NOT NULL,
    artifact_type TEXT NOT NULL CHECK (artifact_type IN ('study_input', 'run_output', 'report')),
    storage_provider TEXT NOT NULL CHECK (storage_provider IN ('filesystem', 'gcs')),
    original_filename TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (size_bytes > 0),
    CHECK (length(trim(sha256)) > 0),
    CHECK (length(trim(object_key)) > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_domain_durable_object_assets_object_key
    ON domain_durable_object_assets (object_key);

CREATE INDEX IF NOT EXISTS idx_domain_durable_object_assets_study
    ON domain_durable_object_assets (study_id, artifact_type, created_at);
