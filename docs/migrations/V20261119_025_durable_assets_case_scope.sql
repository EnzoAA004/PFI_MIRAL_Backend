-- Scope newly persisted source studies to their owning MIRA·L case.
-- Existing historical assets remain NULL and are intentionally not backfilled because
-- no reliable case-to-source mapping exists for them.
ALTER TABLE domain_durable_object_assets
    ADD COLUMN IF NOT EXISTS case_id TEXT NULL;

CREATE INDEX IF NOT EXISTS idx_domain_durable_object_assets_case_input
    ON domain_durable_object_assets (study_id, case_id, created_at)
    WHERE artifact_type = 'study_input';
