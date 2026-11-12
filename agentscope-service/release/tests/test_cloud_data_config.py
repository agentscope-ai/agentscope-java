# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import base64
import importlib.util
import json
from pathlib import Path
import subprocess
import unittest
from types import SimpleNamespace
from unittest import mock

import yaml

SERVICE = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('cloud_data', SERVICE / 'deploy/managed-cloud-data-config.py')
cloud = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cloud)


def credentials(migration=False):
    user = 'owner' if migration else 'control'
    data = {key: f'postgres://{user}:test-password@{cloud.POSTGRES_HOST}:5432/managed_agents?sslmode=require'
            + ('&search_path=rt' if key.endswith('STORAGE_DSN') else '') for key in cloud.DSNS}
    for component, schema in (cloud.MIGRATION if migration else cloud.BUSINESS).items():
        prefix = 'MANAGED_AGENTS_' + component + '_DB_'
        data[prefix + 'URL'] = f'jdbc:postgresql://{cloud.POSTGRES_HOST}:5432/managed_agents?currentSchema={schema}&sslmode=require'
        data[prefix + 'USER'] = 'owner' if migration else component.lower()
        data[prefix + 'PASSWORD'] = 'test-password'
    if not migration:
        data['MANAGED_AGENTS_RUNTIME_REDIS_URI'] = f'redis://ma_runtime:test-password@{cloud.REDIS_HOST}:6379/0'
    data['MANAGED_AGENTS_VAULT_MASTER_KEY'] = 'must-not-copy'
    data['MANAGED_AGENTS_RUNTIME_MQ_ENDPOINTS'] = 'must-not-copy'
    return data


class CloudDataTests(unittest.TestCase):
    def test_connection_filter_and_rejection_of_stale_or_insecure_values(self):
        for migration in (False, True):
            valid = credentials(migration)
            selected = cloud.connection_data(valid, migration)
            self.assertNotIn('MANAGED_AGENTS_VAULT_MASTER_KEY', selected)
            self.assertNotIn('MANAGED_AGENTS_RUNTIME_MQ_ENDPOINTS', selected)
            for key, value in selected.items():
                if key.endswith('DB_URL') or key in cloud.DSNS:
                    for invalid in (value.replace(cloud.POSTGRES_HOST, 'postgres'),
                                    value.replace('sslmode=require', 'sslmode=disable'),
                                    value.replace('/managed_agents', '/other')):
                        with self.subTest(key=key, invalid=invalid), self.assertRaises(ValueError):
                            cloud.connection_data(dict(valid, **{key: invalid}), migration)
            if not migration:
                key = 'MANAGED_AGENTS_RUNTIME_REDIS_URI'
                for invalid in ('redis://redis:6379', f'redis://{cloud.REDIS_HOST}:6379/0',
                                selected[key].replace('/0', '/1')):
                    with self.assertRaises(ValueError):
                        cloud.connection_data(dict(valid, **{key: invalid}))

    def test_apply_validates_both_sources_before_writing_and_excludes_other_credentials(self):
        for invalid_migration in (False, True, 'owner'):
            calls = []
            def execute(command, **kwargs):
                calls.append((command, kwargs))
                if 'get' in command:
                    migration = command[command.index('secret') + 1] == 'managed-agents-migration'
                    data = credentials(migration)
                    if invalid_migration is True and migration:
                        data['CONTROL_PLANE_PRODUCT_DSN'] = data['CONTROL_PLANE_PRODUCT_DSN'].replace(cloud.POSTGRES_HOST, 'postgres')
                    if invalid_migration == 'owner' and not migration:
                        data['MANAGED_AGENTS_AGENT_SERVICE_DB_USER'] = 'owner'
                    return SimpleNamespace(returncode=0, stdout=json.dumps({'data': {
                        key: base64.b64encode(val.encode()).decode() for key, val in data.items()}}))
                return SimpleNamespace(returncode=0, stdout='')
            with mock.patch.object(cloud.subprocess, 'run', side_effect=execute), \
                    mock.patch('sys.argv', ['config', '--apply']):
                if invalid_migration:
                    with self.assertRaises(ValueError):
                        cloud.main()
                else:
                    cloud.main()
            writes = [kwargs for command, kwargs in calls if 'apply' in command]
            self.assertEqual(len(writes), 0 if invalid_migration else 1)
            if writes:
                docs = json.loads(writes[0]['input'])['items']
                self.assertEqual([d['metadata']['name'] for d in docs],
                                 ['managed-agents-cloud-data', 'managed-agents-cloud-data-migration'])
                self.assertNotIn('must-not-copy', json.dumps(docs))

    def test_helm_cloud_connections_override_stale_workload_and_schema_secrets(self):
        command = ['helm', 'template', 'test', str(SERVICE / 'helm/managed-agents'),
                   '--set', 'imageRepository=example.com/test', '--set', 'existingSecret=old-workload',
                   '--set', 'schema.existingSecret=old-migration']
        for alternative in (False, True):
            args = command + (['--set', 'data.existingSecret=', '--set', 'schema.dataExistingSecret='] if alternative else [])
            objects = list(yaml.safe_load_all(subprocess.check_output(args, text=True)))
            jobs = 0
            for obj in objects:
                if not obj or obj['kind'] not in ('Deployment', 'Job'):
                    continue
                container = obj['spec']['template']['spec']['containers'][0]
                refs = [r['secretRef']['name'] for r in container.get('envFrom', [])]
                if obj['kind'] == 'Job':
                    jobs += 1
                    self.assertEqual(refs, ['old-migration'] if alternative else
                                     ['old-migration', 'managed-agents-cloud-data-migration'])
                elif container['name'] == 'gateway':
                    self.assertEqual(refs, [])
                else:
                    self.assertNotIn('managed-agents-cloud-data-migration', refs)
                    if not alternative:
                        self.assertEqual(refs[-1], 'managed-agents-cloud-data')
                    for env in container.get('env', []):
                        self.assertNotIn(env['name'], cloud.connection_data(credentials()))
            self.assertEqual(jobs, 4)
        # Older --reuse-values installations have neither new key; retain the cloud defaults.
        legacy = subprocess.check_output(command + ['--set-json', 'data=null',
                                                  '--set-json', 'schema.dataExistingSecret=null'], text=True)
        for obj in yaml.safe_load_all(legacy):
            if not obj or obj['kind'] not in ('Deployment', 'Job'):
                continue
            c = obj['spec']['template']['spec']['containers'][0]
            if c['name'] == 'gateway':
                continue
            expected = 'managed-agents-cloud-data-migration' if obj['kind'] == 'Job' else 'managed-agents-cloud-data'
            self.assertEqual(c['envFrom'][-1]['secretRef']['name'], expected)
        for setting in ('data.existingSecret=old-migration',
                        'schema.dataExistingSecret=old-workload',
                        'tool-worker.existingSecret=managed-agents-cloud-data-migration'):
            result = subprocess.run(command + ['--set', setting], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0, setting)


if __name__ == '__main__':
    unittest.main()
