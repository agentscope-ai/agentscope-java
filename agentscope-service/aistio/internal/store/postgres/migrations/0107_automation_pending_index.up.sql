CREATE INDEX IF NOT EXISTS automation_runs_pending ON automation_runs ((runtime->'details'->>'updatedAt'), id) WHERE status IN ('queued','dispatching','running','waiting') AND runtime IS NOT NULL;
