# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Render the real Compose inheritance and interpolation used by installers."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SERVICE = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which('docker'), 'requires Docker Compose CLI')
class ComposeConfigurationTests(unittest.TestCase):
    def render(self, source=False, missing_mq=False):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / 'init-env.sh'
            shutil.copy2(SERVICE / 'deploy/init-env.sh', script)
            subprocess.run(['bash', str(script), '2.1.0-test', 'example.com/team'],
                           check=True, capture_output=True)
            env = {k: v for k, v in os.environ.items()
                   if not k.startswith(('BUILDER_', 'CONTROL_PLANE_', 'POSTGRES_'))}
            env.update(BUILDER_MANAGED_REDIS_URI='redis://redis:6379',
                       BUILDER_MANAGED_MQ_ENDPOINTS='' if missing_mq else 'proxy:8081',
                       BUILDER_MANAGED_MQ_ACCESS_KEY='fixture-user',
                       BUILDER_MANAGED_MQ_SECRET_KEY='fixture-password',
                       BUILDER_USER_FILES_ENABLED='false',
                       BUILDER_MANAGED_TOOL_CONCURRENCY='7',
                       BUILDER_RUNTIME_NATIVE_LOG_CONNECTIONS='5')
            if source:
                env.update(BUILDER_LOCAL_DEV='true', BUILDER_ALLOW_LOCAL_ENVIRONMENT='true')
            command = ['docker', 'compose', '--env-file', str(script.parent / '.env'),
                       '-f', str(SERVICE / ('docker-compose.yml' if source else 'deploy/compose.yaml')),
                       'config', '--format', 'json']
            result = subprocess.run(command, env=env, capture_output=True, text=True)
            if missing_mq:
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('BUILDER_MANAGED_MQ_ENDPOINTS', result.stderr)
                return
            self.assertEqual(result.returncode, 0, result.stderr)
            return json.loads(result.stdout)['services']

    def test_installer_forwards_mq_auth_and_execution_pool_settings(self):
        services = self.render()
        for role in ['portal', 'data', 'hands']:
            env = services[role]['environment']
            self.assertEqual(env['BUILDER_MANAGED_MQ_ACCESS_KEY'], 'fixture-user')
            self.assertEqual(env['BUILDER_MANAGED_MQ_SECRET_KEY'], 'fixture-password')
            self.assertEqual(env['BUILDER_MANAGED_MQ_TOOL_EXECUTE_TOPIC'], 'ma_tool_execute_v1')
            self.assertEqual(env['BUILDER_MANAGED_MQ_TOOL_CONTROL_TOPIC'], 'ma_tool_control_v1')
        self.assertEqual(services['hands']['environment']['BUILDER_MANAGED_TOOL_CONCURRENCY'], '7')
        self.assertEqual(services['data']['environment']['BUILDER_RUNTIME_NATIVE_LOG_CONNECTIONS'], '5')
        self.assertEqual(services['gateway']['environment']['BUILDER_LOCAL_DEV'], 'false')
        for role in ['control', 'gateway', 'portal', 'data', 'hands']:
            self.assertEqual(services[role]['environment']['BUILDER_USER_FILES_ENABLED'], 'false')

    def test_source_local_mode_reaches_all_five_roles(self):
        services = self.render(source=True)
        for role in ['control', 'gateway', 'portal', 'data', 'hands']:
            self.assertEqual(services[role]['environment']['BUILDER_LOCAL_DEV'], 'true')
        self.assertEqual(services['control']['environment']['BUILDER_ALLOW_LOCAL_ENVIRONMENT'], 'true')
        self.assertEqual(services['control']['build']['args']['VERSION'], '2.1.0-test')

    def test_missing_mq_endpoint_stops_before_service_start(self):
        self.render(missing_mq=True)
