-- Decision del profesional sobre cada hallazgo degenerativo propuesto por la IA (MIRA-L).
--
-- Un hallazgo se identifica dentro de UNA corrida exacta (run_id = multiplanarRunId) por finding_key (nivel + tipo, definido
-- por el cliente). La decision es del profesional: aceptado, rechazado o dudoso. Nunca modifica la prediccion de la IA ni
-- el estado de revision del estudio; solo registra quien decidio, cuando y con que nota. Sin fila = todavia sin revisar.
-- Aditiva: tabla nueva, sin cambios sobre las existentes.
CREATE TABLE IF NOT EXISTS domain_finding_decisions (
    study_id UUID NOT NULL REFERENCES domain_studies (id) ON DELETE CASCADE,
    run_id TEXT NOT NULL,
    finding_key TEXT NOT NULL,
    decision TEXT NOT NULL CHECK (decision IN ('aceptado', 'rechazado', 'dudoso')),
    note TEXT NOT NULL DEFAULT '',
    decided_by TEXT NOT NULL DEFAULT '',
    decided_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (study_id, run_id, finding_key)
);

CREATE INDEX IF NOT EXISTS idx_finding_decisions_run ON domain_finding_decisions (study_id, run_id);
