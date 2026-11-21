-- Preserve the context version used by each generated assistant draft.
-- Existing conversations remain readable and are treated as legacy, not silently current.
ALTER TABLE domain_chat_messages ADD COLUMN context_version TEXT NULL;
