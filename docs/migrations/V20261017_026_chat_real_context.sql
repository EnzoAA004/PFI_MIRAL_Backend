-- Asistente sobre los resultados REALES (corrida multiplanar que muestra el visor).
--
-- Antes la conversacion colgaba del estudio y del circuito de demostracion. Ahora cada conversacion
-- pertenece a un ambito: una corrida de un estudio, o el par (corrida A, corrida B) del visor dual.
-- Las conversaciones anteriores quedan con scope_key NULL: se conservan, pero no se mezclan con ningun
-- ambito real (ni se listan en el visor).
--
-- Aditiva: sin backfill, sin tocar filas existentes. run_id / reference_run_id son los ids de la
-- corrida multiplanar (texto, tal como los publica el visor); no hay FK porque no son del circuito de
-- demostracion y una corrida nunca se borra por esta via.
ALTER TABLE domain_chat_conversations
    ADD COLUMN IF NOT EXISTS scope_key TEXT NULL,
    ADD COLUMN IF NOT EXISTS run_id TEXT NULL,
    ADD COLUMN IF NOT EXISTS reference_study_id UUID NULL REFERENCES domain_studies (id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS reference_run_id TEXT NULL;

CREATE INDEX IF NOT EXISTS idx_chat_conversations_scope ON domain_chat_conversations (study_id, scope_key);

-- Huella del contexto con que se respondio cada turno (hash de los datos enviados al modelo) y las
-- referencias a elementos del contexto que la respuesta cito y que se verificaron en el servidor.
ALTER TABLE domain_chat_messages
    ADD COLUMN IF NOT EXISTS context_fingerprint TEXT NULL,
    ADD COLUMN IF NOT EXISTS references_json TEXT NULL;
