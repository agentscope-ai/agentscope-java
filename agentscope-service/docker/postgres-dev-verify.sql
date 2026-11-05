\set ON_ERROR_STOP on

-- Fail startup if a plane silently used public or if a migration stopped
-- before the complete v4 Issue/orchestration schema was installed.
DO $$
DECLARE
    missing_relations TEXT;
BEGIN
    SELECT string_agg(expected.schema_name || '.' || expected.relation_name, ', ' ORDER BY 1)
      INTO missing_relations
      FROM (VALUES
          ('cp', 'users'),
          ('cp', 'agents'),
          ('cp', 'sessions'),
          ('cp', 'product_schema_migrations'),
          ('rt', 'schema_migrations'),
          ('rt', 'issues'),
          ('rt', 'comments'),
          ('rt', 'comment_mentions'),
          ('rt', 'comment_routes'),
          ('rt', 'agent_tasks'),
          ('rt', 'agent_task_inputs'),
          ('rt', 'teams'),
          ('rt', 'team_members'),
          ('rt', 'artifacts'),
          ('rt', 'artifact_links'),
          ('rt', 'issue_subscribers'),
          ('rt', 'approvals'),
          ('rt', 'inbox_items'),
          ('rt', 'activity_log'),
          ('rt', 'automations'),
          ('rt', 'automation_runs'),
          ('rt', 'execution_attempts'),
          ('rt', 'orchestration_definitions'),
          ('rt', 'orchestration_revisions'),
          ('rt', 'orchestration_runs'),
          ('rt', 'orchestration_run_nodes'),
          ('rt', 'orchestration_run_edges'),
          ('rt', 'orchestration_run_team_snapshots'),
          ('rt', 'orchestration_run_events'),
          ('rt', 'agent_runtime_policies'),
          ('rt', 'control_outbox'),
          ('portal', 'builder_schema_migration'),
          ('portal', 'builder_portal_session'),
          ('portal', 'builder_portal_turn'),
          ('portal', 'builder_portal_event'),
          ('portal', 'builder_portal_artifact_publication'),
          ('dp', 'builder_schema_migration'),
          ('dp', 'builder_user'),
          ('dp', 'builder_agent'),
          ('dp', 'builder_session'),
          ('dp', 'builder_session_event'),
          ('dp', 'agentscope_store'),
          ('ma', 'builder_schema_migration'),
          ('ma', 'session'),
          ('ma', 'turn'),
          ('ma', 'input'),
          ('ma', 'state_checkpoint'),
          ('ma', 'native_event'),
          ('ma', 'private_object'),
          ('ma', 'tool_task'),
          ('ma', 'tool_command_outbox'),
          ('ma', 'turn_suspension'),
          ('ma', 'turn_resume'),
          ('ma', 'hands_execution'),
          ('ma', 'hands_tool_result_outbox')
      ) AS expected(schema_name, relation_name)
     WHERE to_regclass(format('%I.%I', expected.schema_name, expected.relation_name)) IS NULL;

    IF missing_relations IS NOT NULL THEN
        RAISE EXCEPTION 'missing required development relations: %', missing_relations;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM rt.schema_migrations
         WHERE version = '0125_portal_runtime_artifacts'
    ) THEN
        RAISE EXCEPTION 'runtime schema migrations did not reach 0125_portal_runtime_artifacts';
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_tables
         WHERE schemaname = 'public'
           AND tablename IN ('issues', 'comments', 'agent_tasks', 'execution_attempts', 'orchestration_runs')
    ) THEN
        RAISE EXCEPTION 'v4 orchestration relations must not be created in public';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM cp.product_schema_migrations
                   WHERE version='0007_integrated_scheduler') OR
       NOT EXISTS (SELECT 1 FROM ma.builder_schema_migration
                   WHERE component='managed' AND version='managed-tool-submission.sql') THEN
        RAISE EXCEPTION 'Control or Managed schema migrations are incomplete';
    END IF;
END $$;
