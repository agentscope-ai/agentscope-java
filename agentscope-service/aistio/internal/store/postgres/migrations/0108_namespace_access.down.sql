DROP FUNCTION IF EXISTS target_work_access_allowed(TEXT,TEXT,TEXT[]);
DROP FUNCTION IF EXISTS issue_access_allowed(UUID,TEXT[],BOOLEAN);
ALTER TABLE issues DROP COLUMN access_policy;
DROP TABLE access_namespace_audit;
DROP TABLE access_namespaces;
