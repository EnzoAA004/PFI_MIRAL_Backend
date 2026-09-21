-- EXT-04B2: visual evidence regions (bbox/ellipse/polygon/mask reference) layered on top of the
-- stage-76/78 pfi.ai-product-result.v1 persistence. New tables only -- domain_ai_analysis_runs,
-- domain_ai_disc_level_results and domain_ai_measurement_results (and their columns) are never
-- altered, exactly like V20260825_017 did for measurement corrections.
--
-- Geometry is stored in the normalised 0..256 basis already documented on
-- domain_reviewer_annotations ("the same basis shared with masks and landmarks"), never DICOM
-- physical coordinates -- see docs/extensions/EXT_04B_VISUAL_EVIDENCE_DESIGN.md section 8.
--
-- Review state is deliberately NOT duplicated here: whether an AI-suggested region is
-- pending/accepted/observed/rejected is answered by the existing domain_ai_disc_level_reviews
-- (when disc_level_result_id is set) or domain_ai_analysis_run_reviews (run-level fallback).
-- Only "who produced the CURRENT geometry" (origin) and "does a correction exist" live here.

CREATE TABLE domain_ai_evidence_regions (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL REFERENCES domain_ai_analysis_runs (id) ON DELETE CASCADE,
    disc_level_result_id UUID NULL REFERENCES domain_ai_disc_level_results (id) ON DELETE CASCADE,
    measurement_id UUID NULL REFERENCES domain_ai_measurement_results (id) ON DELETE CASCADE,
    plane TEXT NULL,
    level TEXT NULL,
    structure TEXT NULL,
    slice_index INTEGER NULL,
    origin TEXT NOT NULL CHECK (origin IN ('ai', 'professional')),
    geometry_type TEXT NOT NULL CHECK (geometry_type IN ('BBOX', 'ELLIPSE', 'POLYGON', 'MASK_REFERENCE')),
    -- Points/coordinates in the normalised 0..256 basis; for MASK_REFERENCE, only
    -- {runId, plane, assetName} -- never inline pixel data.
    geometry JSONB NOT NULL,
    -- origin='ai' only: the AI's own per-slice pixel frame, kept for audit/reproducibility of
    -- the EvidenceCoordinateNormalizer conversion -- never the basis `geometry` itself uses.
    coordinate_space_ref JSONB NULL,
    model_id TEXT NULL,
    model_sha256 TEXT NULL,
    pipeline_version TEXT NULL,
    -- Only when the source model genuinely emits one -- never fabricated.
    confidence DOUBLE PRECISION NULL CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    -- True for geometry sourced from an unvalidated/experimental capability (e.g. 67C axial
    -- pairing). P10.7 (blocked_preprocessing_parity) never reaches this table at all -- see
    -- EvidenceIngestionGuard.
    experimental BOOLEAN NOT NULL DEFAULT false,
    label TEXT NOT NULL DEFAULT 'Región de interés',
    comment TEXT NULL,
    -- Internal only; who drew this region directly when origin='professional'. Never exposed in
    -- the structured report (same rule EXT-04A already applies to reviewer identity).
    authored_by TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT domain_ai_evidence_regions_plane_check
        CHECK (plane IS NULL OR plane IN ('sagittal', 'axial')),
    CONSTRAINT domain_ai_evidence_regions_slice_index_check
        CHECK (slice_index IS NULL OR slice_index >= 0)
);

CREATE INDEX idx_ai_evidence_regions_run ON domain_ai_evidence_regions (analysis_run_id);
CREATE INDEX idx_ai_evidence_regions_disc_level ON domain_ai_evidence_regions (disc_level_result_id);
CREATE INDEX idx_ai_evidence_regions_measurement ON domain_ai_evidence_regions (measurement_id);

-- Append-only: domain_ai_evidence_regions is never UPDATEd by a correction. Each correction is
-- its own row carrying both the AI's original geometry (snapshot at correction time) and the
-- corrected geometry; the "current" corrected geometry for a region is the latest row by
-- created_at. Mirrors domain_ai_measurement_corrections exactly.
CREATE TABLE domain_ai_evidence_region_corrections (
    id UUID PRIMARY KEY,
    evidence_region_id UUID NOT NULL REFERENCES domain_ai_evidence_regions (id) ON DELETE CASCADE,
    analysis_run_id UUID NOT NULL REFERENCES domain_ai_analysis_runs (id) ON DELETE CASCADE,
    original_geometry_type TEXT NULL CHECK (original_geometry_type IS NULL OR original_geometry_type IN ('BBOX', 'ELLIPSE', 'POLYGON', 'MASK_REFERENCE')),
    original_geometry JSONB NULL,
    corrected_geometry_type TEXT NOT NULL CHECK (corrected_geometry_type IN ('BBOX', 'ELLIPSE', 'POLYGON', 'MASK_REFERENCE')),
    corrected_geometry JSONB NOT NULL,
    comment TEXT NULL,
    reviewed_by TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_evidence_region_corrections_region ON domain_ai_evidence_region_corrections (evidence_region_id);
CREATE INDEX idx_ai_evidence_region_corrections_run ON domain_ai_evidence_region_corrections (analysis_run_id);
