CREATE INDEX IF NOT EXISTS orchestration_run_signal_lookup
 ON orchestration_run_events(run_id,type,node_id,sequence);
