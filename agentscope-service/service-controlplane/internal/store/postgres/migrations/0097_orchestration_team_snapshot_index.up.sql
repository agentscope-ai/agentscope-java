-- +migrate NoTransaction
CREATE UNIQUE INDEX CONCURRENTLY orchestration_team_snapshot_idx ON orchestration_run_team_snapshots (run_id, team_id);
