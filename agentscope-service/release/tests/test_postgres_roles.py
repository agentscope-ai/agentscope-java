# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Run with AGENTSCOPE_ROLES_TEST_CONTAINER pointing to a dedicated local PostgreSQL fixture."""
import os
from pathlib import Path
import subprocess
import unittest
import uuid

SERVICE = Path(__file__).resolve().parents[2]
CONTAINER = os.environ.get('AGENTSCOPE_ROLES_TEST_CONTAINER')


@unittest.skipUnless(CONTAINER, 'requires a dedicated local PostgreSQL container')
class PostgresRolesTest(unittest.TestCase):
    def sql(self, statement, *, database=None, check=True):
        return subprocess.run(['docker', 'exec', '-i', CONTAINER, 'psql', '-X',
                               '-v', 'ON_ERROR_STOP=1', '-U', 'managed',
                               '-d', database or self.database],
                              input=statement, text=True, capture_output=True, check=check)

    def test_hands_can_check_commits_without_reading_private_state_or_writing_authorization(self):
        suffix = uuid.uuid4().hex
        self.database = 'as_role_test_' + suffix
        roles = {name: 'as_' + name + '_' + suffix for name in ('control', 'portal', 'data', 'hands')}
        self.sql('CREATE DATABASE ' + self.database, database='postgres')
        try:
            setup = ''.join('CREATE SCHEMA ' + name + ';' for name in ('cp', 'rt', 'portal', 'dp'))
            setup += ''.join('CREATE TABLE ' + name + '(id INT);' for name in (
                'cp.product_schema_migrations', 'rt.schema_migrations',
                'portal.builder_schema_migration', 'dp.builder_schema_migration'))
            resources = SERVICE / 'service-common/src/main/resources'
            for name in ('managed-runtime-v2.sql', 'managed-subagents.sql', 'managed-private-objects.sql',
                         'managed-hands.sql', 'managed-session-protocol.sql', 'managed-turn-ack.sql',
                         'managed-mcp-batch.sql', 'managed-tool-publication-lease.sql', 'managed-tool-submission.sql'):
                setup += (resources / name).read_text()
            setup += 'CREATE TABLE ma.builder_schema_migration(version TEXT);'
            setup += "INSERT INTO ma.admission_quota(quota_key) VALUES('q');"
            setup += ("INSERT INTO ma.session(scope_key,tenant,namespace,session_id,configuration,quota_key)"
                      " VALUES('s','t','n','s','{}','q');")
            self.sql(setup)
            args = ['docker', 'exec', '-i', '-e', 'PGUSER=managed', '-e', 'PGDATABASE=' + self.database]
            for name, role in roles.items():
                args += ['-e', name.upper() + '_DB_USER=' + role,
                         '-e', name.upper() + '_DB_PASSWORD=local-fixture-password']
            args += [CONTAINER, 'sh', '-s']
            subprocess.run(args, input=(SERVICE / 'deploy/postgres-roles.sh').read_text(),
                           text=True, capture_output=True, check=True)
            worker = 'SET ROLE ' + roles['hands'] + ';'
            self.sql(worker + "BEGIN; SELECT execution_epoch FROM ma.session WHERE scope_key='s' FOR UPDATE;"
                     "SELECT payload FROM ma.tool_command_outbox WHERE command_id='c' AND scope_key='s' AND authorized=TRUE;"
                     "SELECT COUNT(*) FROM ma.tool_task WHERE scope_key='s' AND task_id='t';"
                     "SELECT COUNT(*) FROM ma.admin_tool_operation WHERE scope_key='s' AND task_id='t';"
                     "SELECT COUNT(*) FROM ma.session s WHERE s.scope_key='s' AND (s.lifecycle<>'active' OR EXISTS("
                     "SELECT 1 FROM ma.input i WHERE i.scope_key=s.scope_key AND i.turn_id='t' AND i.type='CANCEL' AND i.status<>'rejected'));"
                     "SELECT status,execution_epoch,execution_token FROM ma.turn WHERE scope_key='s' AND turn_id='t'; ROLLBACK;")
            for statement in ('SELECT * FROM ma.state_checkpoint', 'SELECT * FROM ma.native_event',
                              'SELECT configuration FROM ma.session',
                              'UPDATE ma.tool_command_outbox SET authorized=TRUE',
                              "UPDATE ma.session SET lifecycle='deleted'", 'DELETE FROM ma.turn',
                              "UPDATE ma.builder_schema_migration SET version='bad'"):
                with self.subTest(statement=statement):
                    result = self.sql(worker + statement, check=False)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn('permission denied', result.stderr)
            for runtime in ('portal', 'data'):
                denied = self.sql('SET ROLE ' + roles[runtime] + '; SELECT * FROM ma.hands_execution', check=False)
                self.assertNotEqual(denied.returncode, 0)
                self.assertIn('permission denied', denied.stderr)
        finally:
            self.sql('DROP DATABASE ' + self.database + ' WITH (FORCE)', database='postgres')
            for role in roles.values():
                self.sql('DROP ROLE IF EXISTS ' + role, database='postgres')


if __name__ == '__main__':
    unittest.main()
