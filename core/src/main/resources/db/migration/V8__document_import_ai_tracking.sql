-- V8: remembers whether AI reading was already tried for a photo upload that is still waiting for confirmation,
-- so resuming it (picking the same photo again before Save) does not pay for a second AI call every retry.
ALTER TABLE document_imports
    ADD COLUMN ai_attempted BOOLEAN NOT NULL DEFAULT FALSE;
