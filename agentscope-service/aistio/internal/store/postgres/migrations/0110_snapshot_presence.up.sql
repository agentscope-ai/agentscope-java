ALTER TABLE session_snapshots
    ADD COLUMN token_usage_reported BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN context_pressure_reported BOOLEAN NOT NULL DEFAULT FALSE;
