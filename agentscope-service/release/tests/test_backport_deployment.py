# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Regression coverage for portable deployment defaults after the runtime backport."""
import subprocess
import unittest
from pathlib import Path

import yaml

SERVICE = Path(__file__).resolve().parents[2]
CHART = SERVICE / 'helm/agentscope-service'


class BackportDeploymentTests(unittest.TestCase):
    def render(self, *values):
        args = ['helm', 'template', 'test', str(CHART),
                '--set', 'imageRepository=registry.example.com/agentscope',
                '--set', 'existingSecret=business',
                '--set', 'schema.existingSecret=migration']
        for value in values:
            args += ['--set', value]
        return list(yaml.safe_load_all(subprocess.check_output(args, text=True)))

    def deployments(self, *values):
        return {obj['metadata']['labels']['app.kubernetes.io/component']: obj
                for obj in self.render(*values) if obj and obj['kind'] == 'Deployment'}

    def test_self_hosted_defaults_have_no_external_secret_dependencies(self):
        for role, obj in self.deployments().items():
            container = obj['spec']['template']['spec']['containers'][0]
            refs = [item['secretRef']['name'] for item in container.get('envFrom', [])]
            self.assertEqual(refs, [] if role == 'gateway' else ['business'])
            env = {item['name']: item.get('value') for item in container['env']}
            self.assertEqual(env['BUILDER_LOCAL_DEV'], 'false')
            self.assertNotIn('BUILDER_MANAGED_CHILD_TURN_CALLBACKS', env)
            self.assertNotIn('MANAGED_AGENTS_RUNTIME_MQ_ENDPOINTS', env)
        for obj in self.render():
            if obj and obj['kind'] == 'Job':
                refs = obj['spec']['template']['spec']['containers'][0]['envFrom']
                self.assertEqual(refs, [{'secretRef': {'name': 'migration'}}])

    def test_optional_secrets_are_explicit_and_scoped(self):
        values = ('managed.mq.existingSecret=my-mq', 'data.existingSecret=my-data',
                  'schema.dataExistingSecret=my-owner')
        for role, obj in self.deployments(*values).items():
            container = obj['spec']['template']['spec']['containers'][0]
            refs = [item['secretRef']['name'] for item in container.get('envFrom', [])]
            expected = ([] if role == 'gateway' else
                        ['business', 'my-mq', 'my-data'] if role in ('portal', 'dataplane', 'hands')
                        else ['business', 'my-data'])
            self.assertEqual(refs, expected)
        for obj in self.render(*values):
            if obj and obj['kind'] == 'Job':
                refs = obj['spec']['template']['spec']['containers'][0]['envFrom']
                self.assertEqual(refs, [{'secretRef': {'name': 'migration'}},
                                        {'secretRef': {'name': 'my-owner'}}])

    def test_worker_image_override_only_changes_worker_image(self):
        baseline = self.deployments('imageTag=baseline')
        updated = self.deployments('imageTag=baseline', 'hands.imageTag=worker-fix')
        baseline['hands']['spec']['template']['spec']['containers'][0]['image'] = (
            'registry.example.com/agentscope/as-hands:worker-fix')
        self.assertEqual(baseline, updated)

    def test_gateway_and_portal_drain_and_keep_existing_idle_pool_settings(self):
        for role, obj in self.deployments().items():
            pod = obj['spec']['template']['spec']
            container = pod['containers'][0]
            if role in ('gateway', 'portal'):
                self.assertEqual(pod['terminationGracePeriodSeconds'], 45)
                self.assertEqual(container['lifecycle']['preStop']['exec']['command'],
                                 ['sh', '-c', 'sleep 10'])
            else:
                self.assertNotIn('lifecycle', container)
        gateway = self.deployments()['gateway']['spec']['template']['spec']['containers'][0]
        env = {item['name']: item.get('value') for item in gateway['env']}
        self.assertEqual(env['SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_POOL_MAXIDLETIME'], '10s')
        self.assertEqual(env['SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_POOL_EVICTIONINTERVAL'], '5s')

    def test_local_development_remains_explicit(self):
        for obj in self.deployments('localDev=true').values():
            env = obj['spec']['template']['spec']['containers'][0]['env']
            self.assertEqual(next(item['value'] for item in env if item['name'] == 'BUILDER_LOCAL_DEV'), 'true')

    def test_optional_migration_connections_cannot_be_business_connections(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.render('data.existingSecret=shared', 'schema.dataExistingSecret=shared')
        for values in [('managed.mq.existingSecret=migration',),
                       ('managed.mq.existingSecret=shared', 'schema.dataExistingSecret=shared')]:
            with self.subTest(values=values), self.assertRaises(subprocess.CalledProcessError):
                self.render(*values)

    def test_templates_and_packages_do_not_embed_a_cloud_installation(self):
        for relative in ('deploy/kubernetes.env.example', 'deploy/kubernetes-migration.env.example',
                         'deploy/cloud-mq.env.example', 'helm/agentscope-service/values.yaml'):
            text = (SERVICE / relative).read_text()
            self.assertNotRegex(text, r'(?:pgm-|rmq-cn-|r-bp)[a-z0-9-]+')
            self.assertNotIn('.aliyuncs.com', text)
        self.assertNotIn('managed-cloud-data-config.py', (SERVICE / 'release/release.py').read_text())


if __name__ == '__main__':
    unittest.main()
