-- Per-study ownership and explicit grants. Existing studies are intentionally not backfilled:
-- they remain inaccessible until an authorized owner assigns them through a controlled process.
CREATE TABLE IF NOT EXISTS domain_study_access_grants (
    case_id TEXT NOT NULL,
    principal_id TEXT NOT NULL,
    permission TEXT NOT NULL CHECK (permission IN ('OWNER', 'READ', 'WRITE')),
    granted_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (case_id, principal_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_domain_study_access_one_owner
    ON domain_study_access_grants (case_id)
    WHERE permission = 'OWNER';

CREATE INDEX IF NOT EXISTS idx_domain_study_access_principal
    ON domain_study_access_grants (principal_id, case_id);

-- Patient identity records also need an owner, including before their first Study is attached.
-- Historical identities remain unowned and are visible only through explicitly assigned studies.
CREATE TABLE IF NOT EXISTS domain_patient_access_grants (
    patient_id UUID NOT NULL REFERENCES domain_patients(id) ON DELETE CASCADE,
    principal_id TEXT NOT NULL,
    permission TEXT NOT NULL CHECK (permission IN ('OWNER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (patient_id, principal_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_domain_patient_access_one_owner
    ON domain_patient_access_grants (patient_id)
    WHERE permission = 'OWNER';
