# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.

import copy
from pathlib import Path
import shutil
import subprocess
import unittest

import yaml


CHART = Path(__file__).resolve().parents[2] / 'helm/managed-agents'


@unittest.skipUnless(shutil.which('helm'), 'helm is required for chart rendering')
class HelmLocalDevTests(unittest.TestCase):
    def render(self, *settings):
        command = ['helm', 'template', 'test', str(CHART), '--set', 'imageRepository=example.com/test',
                   '--set', 'existingSecret=workload', '--set', 'schema.existingSecret=migration', '--set', 'queryStore.existingSecret=query-credentials']
        for setting in settings:
            command += ['--set', setting]
        result = subprocess.run(command, check=True, capture_output=True, text=True)
        return {d['metadata']['name']: d for d in yaml.safe_load_all(result.stdout)
                if d and d['kind'] == 'Deployment'}

    def test_publication_capacity_is_role_scoped_and_maintenance_is_independent(self):
        rows = self.render('agent-harness-runtime.toolPublicationConcurrency=128',
                           'agent-harness-runtime.projectionNotificationConcurrency=64',
                           'agent-harness-runtime.concurrency=1024',
                           'agent-harness-runtime.renewalThreads=64',
                           'agent-harness-runtime.controlThreads=64')
        for name, deployment in rows.items():
            env = {e['name']: e.get('value') for e in deployment['spec']['template']['spec']['containers'][0]['env']}
            if name.endswith('agent-harness-runtime'):
                self.assertEqual(env['MANAGED_AGENTS_RUNTIME_TOOL_PUBLICATION_CONCURRENCY'], '128')
                self.assertEqual(env['MANAGED_AGENTS_RUNTIME_TOOL_PUBLICATION_REPAIR_INTERVAL'], '30s')
                self.assertEqual(env['MANAGED_AGENTS_RUNTIME_PROJECTION_NOTIFICATION_CONCURRENCY'], '64')
                self.assertEqual(env['MANAGED_AGENTS_RUNTIME_CONCURRENCY'], '1024')
                self.assertEqual(env['MANAGED_AGENTS_RUNTIME_RENEWAL_THREADS'], '64')
                self.assertEqual(env['MANAGED_AGENTS_RUNTIME_CONTROL_THREADS'], '64')
            else:
                self.assertNotIn('MANAGED_AGENTS_RUNTIME_TOOL_PUBLICATION_CONCURRENCY', env)
            if name.endswith('tool-worker'):
                self.assertEqual(env['MANAGED_AGENTS_RUNTIME_MCP_WORKER_THREADS'], '700')

    def test_pressure_overlay_scales_only_brain_and_disables_execution_limit(self):
        profile = CHART.parents[1] / 'tests/pts/values-publication-10000.yaml'
        command = ['helm', 'template', 'pressure', str(CHART), '-f', str(profile),
                   '--set', 'imageRepository=example.com/test', '--set', 'existingSecret=workload',
                   '--set', 'schema.existingSecret=migration', '--set', 'queryStore.existingSecret=query-credentials']
        deployments = [d for d in yaml.safe_load_all(subprocess.check_output(command, text=True))
                       if d and d['kind'] == 'Deployment']
        self.assertEqual(len(deployments), 5)
        for d in deployments:
            expected_replicas = 4 if d['metadata']['name'].endswith('agent-harness-runtime') else 2
            self.assertEqual(d['spec']['replicas'], expected_replicas)
            env = {e['name']: e.get('value') for e in d['spec']['template']['spec']['containers'][0]['env']}
            name = d['metadata']['name']
            if name.endswith(('agent-harness-runtime', 'agent-service', 'tool-worker')):
                self.assertEqual(env['MANAGED_AGENTS_OBJECT_STORE_MAX_INFLIGHT_BYTES'], '1073741824')
            if name.endswith('agent-service'):
                self.assertEqual(env['MANAGED_AGENTS_PROJECTION_OBJECT_MAX_INFLIGHT_BYTES'], '268435456')
            if name.endswith('agent-harness-runtime'):
                self.assertEqual(env['MANAGED_AGENTS_RUNTIME_TOOL_PUBLICATION_CONCURRENCY'], '128')
                self.assertEqual(env['MANAGED_AGENTS_RUNTIME_CONCURRENCY'], '0')
                self.assertEqual(env['MANAGED_AGENTS_RUNTIME_TOOL_EVENT_CONCURRENCY'], '128')
                self.assertEqual(env['MANAGED_AGENTS_RUNTIME_RESUME_HANDOFF_CAPACITY'], '64')
                self.assertEqual(env['MANAGED_AGENTS_RUNTIME_INLINE_TOOL_WAIT_SECONDS'], '1')
            elif name.endswith('tool-worker'):
                self.assertEqual(env['MANAGED_AGENTS_RUNTIME_TOOL_CALLBACKS'], '128')
                self.assertEqual(env['MANAGED_AGENTS_TOOL_WORKER_DB_POOL_SIZE'], '32')
            elif name.endswith('agent-service'):
                self.assertEqual(env['MANAGED_AGENTS_PROJECTION_CONCURRENCY'], '32')
                self.assertNotIn('MANAGED_AGENTS_PROJECTION_DB_POOL_SIZE',env)

    def test_session_queue_is_opt_in_and_shared_between_admission_and_execution(self):
        for enabled in ('false', 'true'):
            deployments = self.render('runtime.sessionQueue.enabled=' + enabled)
            for name, deployment in deployments.items():
                if name.endswith(('agent-service', 'agent-harness-runtime')):
                    env = {item['name']: item.get('value') for item in deployment['spec']['template']['spec']['containers'][0]['env']}
                    self.assertEqual(env['MANAGED_AGENTS_RUNTIME_SESSION_QUEUE_ENABLED'], enabled)
                    self.assertEqual(env['MANAGED_AGENTS_RUNTIME_MQ_SESSION_READY_TOPIC'], 'ma_session_ready_v1')
                    self.assertEqual(env['MANAGED_AGENTS_RUNTIME_MQ_SESSION_READY_GROUP'], 'ma_session_ready_v1')

    def test_object_credentials_and_projection_budgets_are_scoped_to_owning_roles(self):
        deployments=self.render('objectStore.existingSecret=objects','runtime.mq.projectionTopic=custom_facts',
            'runtime.mq.projectionGroup=custom_readers','agent-service.projection.concurrency=4')
        for name,deployment in deployments.items():
            container=deployment['spec']['template']['spec']['containers'][0]
            refs=[r['secretRef']['name'] for r in container.get('envFrom',[])]
            env={e['name']:e.get('value') for e in container['env']}
            java=name.endswith(('agent-service','agent-harness-runtime','tool-worker'))
            self.assertEqual('objects' in refs,java)
            if java:self.assertEqual(env['MANAGED_AGENTS_RUNTIME_MQ_PROJECTION_TOPIC'],'custom_facts')
            if name.endswith('agent-service'):
                self.assertNotIn('MANAGED_AGENTS_PROJECTION_DB_POOL_SIZE',env)
                self.assertEqual(env['MANAGED_AGENTS_PROJECTION_CONCURRENCY'],'4')
                self.assertEqual(env['MANAGED_AGENTS_PROJECTION_ROLE'],'both')
            else:self.assertNotIn('MANAGED_AGENTS_PROJECTION_DB_POOL_SIZE',env)

    def test_schema_and_workload_images_use_the_same_component_override(self):
        command=['helm','template','test',str(CHART),'--set','imageRepository=example.com/test',
            '--set','existingSecret=workload','--set','schema.existingSecret=migration', '--set', 'queryStore.existingSecret=query-credentials',
            '--set','imageTag=new-java','--set','control-plane.imageTag=retained-control',
            '--set','agent-harness-runtime.imageTag=brain@sha256:'+'a'*64]
        objects=[x for x in yaml.safe_load_all(subprocess.check_output(command,text=True)) if x]
        jobs=[x for x in objects if x['kind']=='Job']
        self.assertEqual(len(jobs),4)
        images={x['metadata']['name']:x['spec']['template']['spec']['containers'][0]['image'] for x in objects if x['kind'] in ['Job','Deployment']}
        self.assertTrue(images['test-managed-agents-schema-control-plane'].endswith(':retained-control'))
        self.assertEqual(images['test-managed-agents-schema-agent-harness-runtime'],images['test-managed-agents-agent-harness-runtime'])
        self.assertEqual(images['test-managed-agents-schema-runtime'],images['test-managed-agents-agent-service'])

    def test_authentication_is_enabled_by_default(self):
        deployments = self.render()
        self.assertEqual(len(deployments), 5)
        for deployment in deployments.values():
            env = deployment['spec']['template']['spec']['containers'][0]['env']
            self.assertEqual([v['value'] for v in env if v['name'] == 'MANAGED_AGENTS_LOCAL_DEV'], ['false'])

    def test_shared_database_health_does_not_remove_all_session_or_brain_endpoints(self):
        for name,deployment in self.render().items():
            container=deployment['spec']['template']['spec']['containers'][0]
            if name.endswith(('agent-service','agent-harness-runtime')):
                self.assertEqual(container['readinessProbe']['httpGet']['path'],'/actuator/health/readiness')
                self.assertEqual(container['startupProbe']['httpGet']['path'],'/actuator/health')
                self.assertIn({'name':'MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED','value':'true'},container['env'])
            elif name.endswith('control-plane'):
                self.assertEqual(container['readinessProbe']['httpGet']['path'],'/readyz')

    def test_inline_tool_wait_is_configurable_without_changing_other_planes(self):
        defaults = self.render()
        changed = self.render('agent-harness-runtime.inlineToolWaitSeconds=1',
                              'agent-harness-runtime.modelTimeoutSeconds=60')
        for name, deployment in changed.items():
            normalized = copy.deepcopy(deployment)
            env = normalized['spec']['template']['spec']['containers'][0]['env']
            setting = next((v for v in env if v['name']=='MANAGED_AGENTS_RUNTIME_INLINE_TOOL_WAIT_SECONDS'),None)
            if setting:
                self.assertEqual(setting['value'],'1')
                setting['value']='5'
            deadline = next((v for v in env if v['name']=='MANAGED_AGENTS_RUNTIME_MODEL_TIMEOUT_SECONDS'),None)
            if deadline:
                self.assertEqual(deadline['value'],'60')
                deadline['value']='0'
            self.assertEqual(normalized,defaults[name])

    def test_synthetic_model_requires_explicit_runtime_enable(self):
        defaults = self.render()
        changed = self.render('agent-harness-runtime.loadTestModel.enabled=true',
                              'agent-harness-runtime.loadTestModel.profiles.short-v1.chunks=4')
        for name,deployment in changed.items():
            env = deployment['spec']['template']['spec']['containers'][0]['env']
            settings = {v['name']:v.get('value') for v in env}
            if name.endswith('agent-harness-runtime'):
                self.assertEqual(settings['MANAGED_AGENTS_RUNTIME_LOAD_TEST_MODEL_ENABLED'],'true')
                self.assertIn('short-v1',settings['MANAGED_AGENTS_RUNTIME_LOAD_TEST_MODEL_PROFILES_JSON'])
                original = {v['name']:v.get('value') for v in defaults[name]['spec']['template']['spec']['containers'][0]['env']}
                self.assertEqual(original['MANAGED_AGENTS_RUNTIME_LOAD_TEST_MODEL_ENABLED'],'false')
            else:
                self.assertNotIn('MANAGED_AGENTS_RUNTIME_LOAD_TEST_MODEL_ENABLED',settings)
                self.assertEqual(deployment,defaults[name])

    def test_poc_switch_only_changes_development_identity_setting(self):
        defaults = self.render()
        enabled = self.render('localDev=true')
        self.assertEqual(set(defaults), set(enabled))
        for name, deployment in enabled.items():
            with self.subTest(component=name):
                normalized = copy.deepcopy(deployment)
                env = normalized['spec']['template']['spec']['containers'][0]['env']
                setting = next(v for v in env if v['name'] == 'MANAGED_AGENTS_LOCAL_DEV')
                self.assertEqual(setting['value'], 'true')
                setting['value'] = 'false'
                self.assertEqual(normalized, defaults[name])
