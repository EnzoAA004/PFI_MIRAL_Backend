-- Additive patient profile fields. Existing de-identified patients remain valid with NULL profile data.
ALTER TABLE domain_patients
    ADD COLUMN IF NOT EXISTS first_name TEXT NULL,
    ADD COLUMN IF NOT EXISTS last_name TEXT NULL,
    ADD COLUMN IF NOT EXISTS date_of_birth DATE NULL;
