-- Borrador del preinforme visual (MIRA-L).
--
-- Un borrador pertenece a UNA corrida exacta de un estudio (run_id = multiplanarRunId). Guarda por separado:
--   * generated_json: lo que el sistema genero por codigo a partir de la corrida (mediciones, correcciones, hallazgos,
--     contexto, referencias de evidencia visual), tal como estaba cuando se creo o se actualizo explicitamente;
--   * edits_json: lo que el profesional decidio e escribio (que incluir, textos editados, observaciones, parametros de
--     visualizacion de cada imagen).
-- source_fingerprint es la huella de generated_json. Si las fuentes cambian (correcciones, contexto clinico) la huella
-- actual deja de coincidir y el borrador se marca "basado en datos anteriores"; nada se reescribe en silencio.
--
-- Editar un borrador NO toca predicciones, correcciones, anotaciones ni el estado de revision del estudio.
-- Aditiva: tabla nueva, sin cambios sobre las existentes.
CREATE TABLE IF NOT EXISTS domain_report_drafts (
    id UUID PRIMARY KEY,
    study_id UUID NOT NULL REFERENCES domain_studies (id) ON DELETE CASCADE,
    run_id TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    source_fingerprint TEXT NOT NULL,
    generated_json JSONB NOT NULL,
    edits_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT NOT NULL DEFAULT '',
    UNIQUE (study_id, run_id)
);

CREATE INDEX IF NOT EXISTS idx_report_drafts_study ON domain_report_drafts (study_id);
