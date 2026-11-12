CREATE INDEX IF NOT EXISTS idx_execution_attempts_portal_cursor
    ON execution_attempts ((runtime_binding->>'portalCommandId'), created_at, id)
    WHERE runtime_binding->>'portalCommandId' IS NOT NULL;
