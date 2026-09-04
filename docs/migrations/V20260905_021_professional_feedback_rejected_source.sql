-- EXT-04B5: a professional REJECTING an AI-suggested evidence region (with no correction) is
-- also feedback in its own right -- a negative signal, distinct from "never reviewed at all".
-- Widens the existing `source` CHECK on domain_professional_feedback_candidates (EXT-07D) to
-- allow this fourth value. No row is rewritten; existing rows are untouched (this only relaxes
-- what future INSERTs may use).

ALTER TABLE domain_professional_feedback_candidates
    DROP CONSTRAINT IF EXISTS domain_professional_feedback_candidates_source_check;

ALTER TABLE domain_professional_feedback_candidates
    ADD CONSTRAINT domain_professional_feedback_candidates_source_check
    CHECK (source IN ('measurement', 'evidence_region', 'professional_new_finding', 'rejected_ai_suggestion'));
