-- EXT-07D: structured capture of professional feedback -- CAPTURE only, never training.
--
-- This table is a read-oriented CAPTURE of corrections/new findings that already exist in
-- domain_ai_measurement_corrections / domain_ai_evidence_regions / domain_ai_evidence_region_corrections
-- (all append-only, all pre-existing). It never mutates those tables, and it is never written to
-- by any automatic retraining/online-learning process -- there is none in this product.
--
-- `status` starts at 'captured' and only ever moves by an explicit human-issued API call
-- (never automatically): 'captured' -> 'eligible_for_curation' | 'excluded', and
-- 'eligible_for_curation' -> 'curated' | 'excluded'. There is deliberately no 'ready_for_training'
-- value -- eligibility for curation is a human judgment call, not an automatic promotion, and
-- "curated" still only means "a person looked at this and organized it," never "used to retrain."
--
-- UNIQUE(source, correction_id) makes capture idempotent: capturing the same correction (or the
-- same professional-authored region, for source='professional_new_finding') twice is a no-op,
-- never a duplicate row.

CREATE TABLE domain_professional_feedback_candidates (
    id UUID PRIMARY KEY,
    study_id UUID NOT NULL REFERENCES domain_studies (id) ON DELETE CASCADE,
    case_id TEXT NOT NULL,
    analysis_run_id UUID NOT NULL REFERENCES domain_ai_analysis_runs (id) ON DELETE CASCADE,
    -- 'measurement': a domain_ai_measurement_corrections row. 'evidence_region': a
    -- domain_ai_evidence_region_corrections row. 'professional_new_finding': a
    -- domain_ai_evidence_regions row with origin='professional' and no AI original at all.
    source TEXT NOT NULL CHECK (source IN ('measurement', 'evidence_region', 'professional_new_finding')),
    -- Points at the correction row (or, for professional_new_finding, the region row itself) that
    -- this candidate was captured from -- not a DB-level FK, since the target table differs by
    -- source (same light-touch convention already used elsewhere in this schema).
    correction_id UUID NOT NULL,
    level TEXT NULL,
    plane TEXT NULL,
    structure TEXT NULL,
    -- Populated for source='measurement' only (areaMm2|majorAxisMm|minorAxisMm).
    metric TEXT NULL,
    -- Populated for source='evidence_region'/'professional_new_finding' only.
    geometry_type TEXT NULL CHECK (geometry_type IS NULL OR geometry_type IN ('BBOX', 'ELLIPSE', 'POLYGON', 'MASK_REFERENCE')),
    ai_original_value DOUBLE PRECISION NULL,
    ai_original_geometry JSONB NULL,
    professional_value DOUBLE PRECISION NULL,
    professional_geometry JSONB NULL,
    -- AI provenance, when this candidate originated from an AI-authored region/measurement --
    -- never fabricated when the source had none (e.g. professional_new_finding).
    model_id TEXT NULL,
    model_sha256 TEXT NULL,
    pipeline_version TEXT NULL,
    -- Internal only -- same rule the rest of this product already applies to reviewer identity
    -- (EXT-04A): never serialized in the public feedback-candidate API, verifiable only at the
    -- database layer for audit purposes.
    reviewed_by TEXT NULL,
    note TEXT NULL,
    status TEXT NOT NULL DEFAULT 'captured' CHECK (status IN ('captured', 'eligible_for_curation', 'excluded', 'curated')),
    -- When the underlying correction/new-finding itself happened (never "now").
    corrected_at TIMESTAMPTZ NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT domain_professional_feedback_candidates_unique_source UNIQUE (source, correction_id)
);

CREATE INDEX idx_professional_feedback_candidates_study ON domain_professional_feedback_candidates (study_id);
CREATE INDEX idx_professional_feedback_candidates_run ON domain_professional_feedback_candidates (analysis_run_id);
CREATE INDEX idx_professional_feedback_candidates_status ON domain_professional_feedback_candidates (status);
