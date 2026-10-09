DROP INDEX IF EXISTS sessions_pending_conversation_idx;
UPDATE chat_conversations SET status='archived' WHERE status='deleted';
ALTER TABLE chat_conversations DROP CONSTRAINT chat_conversations_status_check;
ALTER TABLE chat_conversations ADD CONSTRAINT chat_conversations_status_check
    CHECK (status IN ('active', 'archived'));
