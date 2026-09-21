-- Stage 80: contextual chat over already-persisted Study/analysis data. New tables only -- no
-- existing table/column is altered or dropped. The chat context is never persisted in full (it
-- is reconstructible on demand from domain_ai_analysis_runs and friends); only the question,
-- the answer, and minimal technical/traceability metadata are stored (stage 80-T).

CREATE TABLE domain_chat_conversations (
    id UUID PRIMARY KEY,
    study_id UUID NOT NULL REFERENCES domain_studies (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_conversations_study_id ON domain_chat_conversations (study_id);
CREATE INDEX idx_chat_conversations_created_at ON domain_chat_conversations (created_at);

CREATE TABLE domain_chat_messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES domain_chat_conversations (id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    model TEXT NULL,
    provider TEXT NULL,
    prompt_version TEXT NULL,
    analysis_run_id UUID NULL REFERENCES domain_ai_analysis_runs (id) ON DELETE SET NULL,
    baseline_study_id UUID NULL REFERENCES domain_studies (id) ON DELETE SET NULL
);

CREATE INDEX idx_chat_messages_conversation_id ON domain_chat_messages (conversation_id);
CREATE INDEX idx_chat_messages_created_at ON domain_chat_messages (created_at);
