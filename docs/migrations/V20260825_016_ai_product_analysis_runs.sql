-- Stage 76: structured persistence for POST /v1/product/analyze (pfi.ai-product-result.v1)
-- results, tied to domain_studies. New tables only -- no existing table/column is altered or
-- dropped.

CREATE TABLE domain_ai_analysis_runs (
    id UUID PRIMARY KEY,
    study_id UUID NOT NULL REFERENCES domain_studies(id) ON DELETE CASCADE,
    case_id TEXT NOT NULL,
    schema_version TEXT NOT NULL,
    release_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('completed', 'partial', 'failed')),
    human_review_required BOOLEAN NOT NULL,
    not_clinical_diagnosis BOOLEAN NOT NULL,
    autonomous_diagnosis BOOLEAN NOT NULL,
    trace_id TEXT NOT NULL DEFAULT '',
    sagittal_input_id TEXT NULL,
    axial_input_id TEXT NULL,
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    raw_response JSONB NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ NULL
);

CREATE INDEX idx_ai_analysis_runs_study_id ON domain_ai_analysis_runs (study_id);
CREATE INDEX idx_ai_analysis_runs_created_at ON domain_ai_analysis_runs (created_at);

CREATE TABLE domain_ai_disc_level_results (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL REFERENCES domain_ai_analysis_runs (id) ON DELETE CASCADE,
    rank_index INTEGER NOT NULL,
    level TEXT NULL,
    localization_status TEXT NOT NULL,
    localization_source TEXT NULL,
    localization_validated BOOLEAN NOT NULL DEFAULT false,
    abstention_preserved BOOLEAN NOT NULL DEFAULT false,
    axial_pairing_status TEXT NOT NULL,
    axial_pairing_validated BOOLEAN NOT NULL DEFAULT false,
    axial_pairing_experimental BOOLEAN NOT NULL DEFAULT false,
    axial_pairing_review_required BOOLEAN NOT NULL DEFAULT false,
    degenerative_findings_status TEXT NULL,
    automatic_inference_enabled BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX idx_ai_disc_level_results_run_id ON domain_ai_disc_level_results (analysis_run_id);

CREATE TABLE domain_ai_measurement_results (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL REFERENCES domain_ai_analysis_runs (id) ON DELETE CASCADE,
    disc_level_result_id UUID NOT NULL REFERENCES domain_ai_disc_level_results (id) ON DELETE CASCADE,
    plane TEXT NULL,
    level TEXT NULL,
    structure TEXT NOT NULL,
    area_mm2 DOUBLE PRECISION NULL,
    major_axis_mm DOUBLE PRECISION NULL,
    minor_axis_mm DOUBLE PRECISION NULL,
    review_required BOOLEAN NOT NULL DEFAULT true
);

CREATE INDEX idx_ai_measurement_results_run_id ON domain_ai_measurement_results (analysis_run_id);
