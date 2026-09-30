-- Stage 78: professional review/correction workflow layered on top of the stage-76
-- pfi.ai-product-result.v1 persistence. New tables only -- domain_ai_analysis_runs,
-- domain_ai_disc_level_results and domain_ai_measurement_results (and their columns) are never
-- altered: the AI's original output stays immutable, review/correction data lives separately.

-- One row per analysis run: the profession's current decision on that run (upserted, not
-- appended). Change history is covered by domain_audit_events (action =
-- 'ai_product_review.run_status_changed'), not by this table.
CREATE TABLE domain_ai_analysis_run_reviews (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL UNIQUE REFERENCES domain_ai_analysis_runs (id) ON DELETE CASCADE,
    review_status TEXT NOT NULL DEFAULT 'pending' CHECK (review_status IN ('pending', 'accepted', 'observed', 'rejected')),
    review_comment TEXT NULL,
    reviewed_by TEXT NULL,
    reviewed_at TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per disc-level result: same shape and semantics as the run-level review, at finer
-- granularity (stage 78-C). Never touches domain_ai_disc_level_results' own AI-output columns.
CREATE TABLE domain_ai_disc_level_reviews (
    id UUID PRIMARY KEY,
    disc_level_result_id UUID NOT NULL UNIQUE REFERENCES domain_ai_disc_level_results (id) ON DELETE CASCADE,
    review_status TEXT NOT NULL DEFAULT 'pending' CHECK (review_status IN ('pending', 'accepted', 'observed', 'rejected')),
    review_comment TEXT NULL,
    reviewed_by TEXT NULL,
    reviewed_at TIMESTAMPTZ NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Append-only: domain_ai_measurement_results is never UPDATEd by a correction. Each correction
-- is its own row carrying both the AI's original value (snapshot at correction time) and the
-- corrected value; the "current" corrected value for a measurement is the latest row by
-- created_at. No unique constraint -- a measurement can be corrected more than once and every
-- correction stays on record.
CREATE TABLE domain_ai_measurement_corrections (
    id UUID PRIMARY KEY,
    measurement_id UUID NOT NULL REFERENCES domain_ai_measurement_results (id) ON DELETE CASCADE,
    analysis_run_id UUID NOT NULL REFERENCES domain_ai_analysis_runs (id) ON DELETE CASCADE,
    field TEXT NOT NULL CHECK (field IN ('areaMm2', 'majorAxisMm', 'minorAxisMm')),
    original_value DOUBLE PRECISION NULL,
    corrected_value DOUBLE PRECISION NOT NULL,
    unit TEXT NOT NULL,
    comment TEXT NULL,
    reviewed_by TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_measurement_corrections_measurement ON domain_ai_measurement_corrections (measurement_id);
CREATE INDEX idx_ai_measurement_corrections_run ON domain_ai_measurement_corrections (analysis_run_id);
