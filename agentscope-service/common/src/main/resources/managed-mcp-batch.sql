-- Internal per-call state shares the parent's sealed configuration and owner lease.
ALTER TABLE ma.hands_execution ADD COLUMN IF NOT EXISTS batch_parent_id VARCHAR(256);
ALTER TABLE ma.hands_execution ADD COLUMN IF NOT EXISTS batch_ordinal INTEGER;
CREATE INDEX IF NOT EXISTS ma_hands_batch_calls ON ma.hands_execution(scope_key,batch_parent_id,batch_ordinal);
-- Runtime-side identity and public per-call metadata, never a schedulable ToolTask.
CREATE TABLE IF NOT EXISTS ma.mcp_batch_call (
    scope_key VARCHAR(64) NOT NULL,
    turn_id VARCHAR(256) NOT NULL,
    task_id VARCHAR(256) NOT NULL,
    parent_task_id VARCHAR(256) NOT NULL,
    native_tool_call_id VARCHAR(256) NOT NULL,
    ordinal INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ready',
    has_result BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY(scope_key,task_id),
    UNIQUE(scope_key,turn_id,native_tool_call_id),
    UNIQUE(scope_key,parent_task_id,ordinal),
    FOREIGN KEY(scope_key,parent_task_id) REFERENCES ma.tool_task(scope_key,task_id),
    FOREIGN KEY(scope_key,turn_id) REFERENCES ma.turn(scope_key,turn_id)
);
CREATE INDEX IF NOT EXISTS ma_mcp_batch_parent ON ma.mcp_batch_call(scope_key,parent_task_id);
