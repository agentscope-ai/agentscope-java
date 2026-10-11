ALTER TABLE chat_conversations DROP CONSTRAINT chat_conversations_status_check;
ALTER TABLE chat_conversations ADD CONSTRAINT chat_conversations_status_check
    CHECK (status IN ('active', 'archived', 'deleted'));

CREATE INDEX sessions_pending_conversation_idx ON sessions (created_at DESC)
    WHERE task_context->'conversationTurn'->>'state' IN ('dispatching', 'running');
