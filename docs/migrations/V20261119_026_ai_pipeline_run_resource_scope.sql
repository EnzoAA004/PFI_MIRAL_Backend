-- Bind direct AI pipeline run IDs to an existing MIRA·L study so run assets
-- can be authorized through the same case-scoped interceptor as other resources.
-- Historical runs are deliberately not backfilled because ownership cannot be inferred safely.
CREATE TABLE IF NOT EXISTS domain_ai_pipeline_run_resources (
    run_id VARCHAR(128) PRIMARY KEY,
    study_id UUID NOT NULL REFERENCES domain_studies(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_domain_ai_pipeline_run_resources_study
    ON domain_ai_pipeline_run_resources (study_id);
