-- Contexto clinico aditivo, propio de cada estudio. Se guarda en domain_studies y no se deriva
-- del paciente: editar al paciente despues no reescribe el contexto historico del estudio
-- (la edad queda guardada como valor, con su procedencia). Los estudios existentes siguen
-- siendo legibles con NULL en estas columnas.
ALTER TABLE domain_studies
    ADD COLUMN IF NOT EXISTS age_at_study INTEGER NULL,
    ADD COLUMN IF NOT EXISTS age_provenance TEXT NULL,
    ADD COLUMN IF NOT EXISTS clinical_reason TEXT NULL,
    ADD COLUMN IF NOT EXISTS symptoms TEXT NULL,
    ADD COLUMN IF NOT EXISTS symptom_duration TEXT NULL,
    ADD COLUMN IF NOT EXISTS relevant_history TEXT NULL,
    ADD COLUMN IF NOT EXISTS context_version TEXT NOT NULL DEFAULT 'pfi.study-context.v1';

DO $$
BEGIN
    ALTER TABLE domain_studies
        ADD CONSTRAINT chk_domain_studies_age_at_study
        CHECK (age_at_study IS NULL OR (age_at_study >= 0 AND age_at_study <= 130));
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$
BEGIN
    ALTER TABLE domain_studies
        ADD CONSTRAINT chk_domain_studies_age_provenance
        CHECK (
            (age_at_study IS NULL AND age_provenance IS NULL)
            OR (age_at_study IS NOT NULL AND age_provenance IN ('calculated', 'reported'))
        );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
