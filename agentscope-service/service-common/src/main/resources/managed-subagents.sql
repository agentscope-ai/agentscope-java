-- Host-owned child calls. A child is published through the ordinary Turn ledger.
-- A separate tree mutex avoids reversing the child -> root budget lock order.
CREATE TABLE IF NOT EXISTS ma.subagent_tree (
    root_scope_key VARCHAR(64) PRIMARY KEY
);
CREATE TABLE IF NOT EXISTS ma.subagent_call (
    parent_scope_key VARCHAR(64) NOT NULL,
    task_id VARCHAR(256) NOT NULL,
    root_scope_key VARCHAR(64) NOT NULL,
    parent_turn_id VARCHAR(256) NOT NULL,
    root_turn_id VARCHAR(256) NOT NULL,
    child_scope_key VARCHAR(64),
    child_turn_id VARCHAR(256),
    agent_id VARCHAR(256) NOT NULL,
    specification TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY(parent_scope_key,task_id),
    UNIQUE(child_scope_key,child_turn_id),
    FOREIGN KEY(parent_scope_key,task_id) REFERENCES ma.tool_task(scope_key,task_id),
    FOREIGN KEY(root_scope_key) REFERENCES ma.session(scope_key),
    FOREIGN KEY(child_scope_key,child_turn_id) REFERENCES ma.turn(scope_key,turn_id)
);
CREATE INDEX IF NOT EXISTS ma_subagent_root ON ma.subagent_call(root_scope_key,status);

-- Waiting parent calls must yield fairly to children and other ready tools.
ALTER TABLE ma.tool_task ADD COLUMN IF NOT EXISTS last_dispatched_at BIGINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS ma_tool_dispatch ON ma.tool_task(status,last_dispatched_at);
CREATE INDEX IF NOT EXISTS ma_tool_kind_dispatch ON ma.tool_task(tool_name,status,last_dispatched_at);
