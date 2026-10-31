DROP TABLE automation_deliveries;
ALTER TABLE automation_runs DROP COLUMN runtime;
ALTER TABLE automations DROP COLUMN execution, DROP COLUMN triggers;
