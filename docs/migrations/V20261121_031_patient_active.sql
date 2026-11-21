-- Additive: a patient can be disabled (archived) without deleting anything.
-- Existing patients stay active; studies and history remain linked to a disabled patient.
ALTER TABLE domain_patients
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;
