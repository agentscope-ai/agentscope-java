# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import json
import shutil
import subprocess
import unittest
from pathlib import Path
import yaml

CHART = Path(__file__).resolve().parents[2] / 'helm/managed-agents'

@unittest.skipUnless(shutil.which('helm'), 'helm is required')
class PiHelmTest(unittest.TestCase):
    def render(self, kind='pi', *extra):
        command = ['helm', 'template', 'test', str(CHART), '--set',
                   'imageRepository=example.invalid/test,existingSecret=workload,schema.existingSecret=migration,queryStore.existingSecret=query,runtime.sessionQueue.enabled=true,brain.pi.existingSecret=brain-only',
                   '--set', 'brain.type=' + kind]
        for value in extra:
            command += ['--set', value]
        return subprocess.run(command, text=True, capture_output=True)

    def test_both_kernels_have_one_node_brain_and_no_java_brain_migration(self):
        for kind in ['pi', 'pi-durable']:
            rendered = self.render(kind)
            self.assertEqual(rendered.returncode, 0, rendered.stderr)
            docs = list(yaml.safe_load_all(rendered.stdout))
            deployments = [d for d in docs if d and d['kind'] == 'Deployment']
            self.assertEqual(len(deployments), 5)
            brain = next(d for d in deployments if d['metadata']['name'].endswith('agent-harness-runtime'))
            pod = brain['spec']['template']['spec']; container = pod['containers'][0]
            self.assertIn('/managed-agents-brain-pi:', container['image'])
            self.assertEqual(container['readinessProbe']['httpGet']['path'], '/readyz')
            self.assertEqual(container['livenessProbe']['httpGet']['path'], '/healthz')
            self.assertEqual(container['envFrom'], [{'secretRef': {'name': 'brain-only'}}])
            env = {e['name']: e.get('value') for e in container['env']}
            self.assertEqual(env['MANAGED_AGENTS_HARNESS_TYPE'], kind)
            self.assertNotIn('SPRING_PROFILES_ACTIVE', env)
            self.assertNotIn('initContainers', pod)
            self.assertTrue(all('emptyDir' in v for v in pod['volumes']))
            self.assertEqual(len([d for d in docs if d and d['kind'] == 'Job' and '-schema-' in d['metadata']['name']]), 3)
            service = next(d for d in deployments if d['metadata']['name'].endswith('agent-service'))
            env = {e['name']: e.get('value') for e in service['spec']['template']['spec']['containers'][0]['env']}
            self.assertEqual(env['MANAGED_AGENTS_HARNESS_TYPE'], kind)

    def test_codex_uses_native_image_and_dedicated_protocol_credentials(self):
        rendered = self.render('codex', 'brain.codex.existingSecret=codex-only',
                               'brain.codex.modelProvider=managed',
                               'brain.codex.modelOptions.api=openai-responses')
        self.assertEqual(rendered.returncode, 0, rendered.stderr)
        docs = [d for d in yaml.safe_load_all(rendered.stdout) if d]
        deployments = [d for d in docs if d['kind'] == 'Deployment']
        self.assertEqual(len(deployments), 5)
        brain = next(d for d in deployments if d['metadata']['name'].endswith('agent-harness-runtime'))
        pod = brain['spec']['template']['spec']; container = pod['containers'][0]
        self.assertIn('/managed-agents-brain-codex:', container['image'])
        self.assertEqual(pod['securityContext']['runAsUser'], 10001)
        self.assertNotIn('initContainers', pod)
        self.assertEqual(container['envFrom'], [{'secretRef': {'name': 'codex-only'}}])
        self.assertEqual(container['readinessProbe']['httpGet']['path'], '/readyz')
        self.assertTrue(container['securityContext']['readOnlyRootFilesystem'])
        self.assertEqual(len([d for d in docs if d['kind'] == 'Job' and '-schema-' in d['metadata']['name']]), 3)
        service = next(d for d in deployments if d['metadata']['name'].endswith('agent-service'))
        env = {e['name']: e.get('value') for e in service['spec']['template']['spec']['containers'][0]['env']}
        self.assertEqual(env['MANAGED_AGENTS_HARNESS_TYPE'], 'codex')
        self.assertEqual(env['MANAGED_AGENTS_MODEL_PROVIDER'], 'managed')
        self.assertIn('openai-responses', env['MANAGED_AGENTS_MODEL_OPTIONS'])
        self.assertNotIn('MANAGED_AGENTS_BRAIN_CALLBACK_URL', env)
        for value in ['brain.codex.existingSecret=workload', 'brain.codex.concurrency=0',
                      'runtime.sessionQueue.enabled=false']:
            self.assertNotEqual(self.render('codex', 'brain.codex.existingSecret=codex-only', value).returncode, 0)

    def test_java_default_keeps_existing_brain_and_four_schema_jobs(self):
        rendered = self.render('agentscope-java'); self.assertEqual(rendered.returncode, 0, rendered.stderr)
        docs = list(yaml.safe_load_all(rendered.stdout))
        self.assertEqual(len([d for d in docs if d and d['kind'] == 'Job' and '-schema-' in d['metadata']['name']]), 4)
        brain = next(d for d in docs if d and d['kind'] == 'Deployment' and d['metadata']['name'].endswith('agent-harness-runtime'))
        self.assertIn('/managed-agents-agent-harness-runtime:', brain['spec']['template']['spec']['containers'][0]['image'])

    def test_java_brain_uses_http_without_platform_credentials_or_volumes(self):
        rendered = self.render('agentscope-java')
        self.assertEqual(rendered.returncode, 0, rendered.stderr)
        docs = [d for d in yaml.safe_load_all(rendered.stdout) if d and d['kind'] == 'Deployment']
        brain = next(d for d in docs if d['metadata']['name'].endswith('agent-harness-runtime'))
        pod = brain['spec']['template']['spec']; container = pod['containers'][0]
        self.assertNotIn('envFrom', container)
        self.assertNotIn('volumes', pod)
        self.assertNotIn('volumeMounts', container)
        env = {e['name']: e for e in container['env']}
        self.assertIn('MANAGED_AGENTS_SESSION_SERVICE_URL', env)
        self.assertFalse(any(any(token in name for token in ['DB_', 'DATASOURCE', '_MQ_', '_REDIS_', 'QUERY_STORE']) for name in env))
        self.assertEqual(env['MANAGED_AGENTS_INTERNAL_TOKEN']['valueFrom']['secretKeyRef']['key'], 'MANAGED_AGENTS_INTERNAL_TOKEN')
        service = next(d for d in docs if d['metadata']['name'].endswith('agent-service'))
        env = {e['name']: e.get('value') for e in service['spec']['template']['spec']['containers'][0]['env']}
        self.assertEqual(env['MANAGED_AGENTS_BRAIN_HTTP_ENABLED'], 'true')
        self.assertTrue(env['MANAGED_AGENTS_BRAIN_CALLBACK_URL'].endswith('/api/internal/brain/notify'))

    def test_invalid_rollout_settings_fail_render(self):
        for value in ['brain.pi.existingSecret=workload', 'brain.pi.concurrency=0', 'runtime.sessionQueue.enabled=false', 'brain.pi.terminationGracePeriodSeconds=30']:
            self.assertNotEqual(self.render('pi', value).returncode, 0, value)
        self.assertNotEqual(self.render('unknown').returncode, 0)

    def test_shared_eval_catalog_overrides_legacy_for_all_harnesses(self):
        profile = CHART.parents[1] / "deploy/profiles/qwen38-eval.json"
        expected = json.loads(profile.read_text())["brain"]["model"]["bindings"]
        for kind in ["agentscope-java", "pi", "codex"]:
            command = ["helm", "template", "test", str(CHART), "-f", str(profile),
                "--set", "imageRepository=example.invalid/test,existingSecret=workload,schema.existingSecret=migration,queryStore.existingSecret=query,runtime.sessionQueue.enabled=true,brain.pi.existingSecret=brain-only,brain.codex.existingSecret=brain-only",
                "--set", "brain.type=" + kind]
            rendered = subprocess.run(command, text=True, capture_output=True)
            self.assertEqual(rendered.returncode, 0, rendered.stderr)
            docs = list(yaml.safe_load_all(rendered.stdout))
            service = next(d for d in docs if d and d["kind"] == "Deployment" and d["metadata"]["name"].endswith("agent-service"))
            env = {e["name"]: e.get("value") for e in service["spec"]["template"]["spec"]["containers"][0]["env"]}
            self.assertEqual(json.loads(env["MANAGED_AGENTS_MODEL_BINDINGS"]), expected)
            self.assertEqual(env["MANAGED_AGENTS_MODEL_PROVIDER"], "dashscope")
            self.assertNotIn("MANAGED_AGENTS_PI_MODEL_OPTIONS", env)
