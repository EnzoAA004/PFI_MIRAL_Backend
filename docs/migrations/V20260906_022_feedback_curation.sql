-- EXT-05: curation of already-captured professional feedback (EXT-07D/EXT-04B5). Adds the
-- *current* curation state fields to the candidate row (cheap to read/filter) plus a separate
-- append-only history table for every status change -- never an UPDATE-only mutation, same
-- convention as domain_ai_evidence_region_corrections.

ALTER TABLE domain_professional_feedback_candidates
    ADD COLUMN curator_id TEXT NULL,
    ADD COLUMN curated_at TIMESTAMPTZ NULL,
    ADD COLUMN exclusion_reason TEXT NULL;

CREATE TABLE domain_professional_feedback_status_changes (
    id UUID PRIMARY KEY,
    candidate_id UUID NOT NULL REFERENCES domain_professional_feedback_candidates (id) ON DELETE CASCADE,
    from_status TEXT NOT NULL,
    to_status TEXT NOT NULL CHECK (to_status IN ('captured', 'eligible_for_curation', 'excluded', 'curated')),
    -- Internal only -- never serialized to any API response, same rule as reviewed_by elsewhere.
    curator_id TEXT NULL,
    reason TEXT NULL,
    note TEXT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_feedback_status_changes_candidate ON domain_professional_feedback_status_changes (candidate_id);
