# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import copy
import json
from pathlib import Path
import tempfile
import time
import unittest

from evaluation import check_preflight, digest
from preflight import inspect_target
from deployment import runtime_environment


class PreflightTest(unittest.TestCase):
    def setUp(self):
        self.profile = json.loads((Path(__file__).parents[2] / 'deploy/profiles/qwen38-eval.json').read_text())
        model = self.profile['brain']['model']
        self.target = {'name': 'pi', 'namespace': 'pi'}
        self.deployments, self.pods = [], []
        for plane in ['agent-service', 'agent-harness-runtime']:
            name = 'ma-managed-agents-' + plane
            env = [{'name': 'UNRELATED_SECRET', 'value': 'never-export-this'}]
            if plane == 'agent-service':
                env += [{'name': 'MANAGED_AGENTS_MODEL_BINDINGS', 'value': json.dumps(model['bindings'])},
                        {'name': 'MANAGED_AGENTS_MODEL_PROVIDER', 'value': model['provider']}]
            else:
                env += [{'name': 'MANAGED_MODEL_KEY', 'valueFrom': {'secretKeyRef': {'name': 'model', 'key': 'key'}}}]
            self.deployments.append({'metadata': {'name': name, 'generation': 2},
                'spec': {'replicas': 1, 'selector': {'matchLabels': {'app': plane}},
                         'template': {'spec': {'containers': [{'name': plane, 'image': 'fixture:v1', 'env': env}]}}},
                'status': {'observedGeneration': 2, 'updatedReplicas': 1, 'availableReplicas': 1}})
            self.pods.append({'metadata': {'labels': {'app': plane}}, 'status': {'containerStatuses':
                [{'name': plane, 'ready': True, 'image': 'fixture:v1', 'imageID': 'sha256:fixture', 'restartCount': 0}]}})

    def test_exports_only_allowlisted_deployment_information(self):
        snapshot = inspect_target(self.target, self.deployments, self.pods, self.profile)
        self.assertEqual(len(snapshot['deployments']), 2)
        self.assertNotIn('never-export-this', json.dumps(snapshot))
        self.assertNotIn('secretKeyRef', json.dumps(snapshot))

    def test_catalog_drift_and_unready_rollouts_fail_before_running_cases(self):
        drift = copy.deepcopy(self.deployments)
        drift[0]['spec']['template']['spec']['containers'][0]['env'][1]['value'] = '{}'
        with self.assertRaisesRegex(ValueError, 'catalog differs'):
            inspect_target(self.target, drift, self.pods, self.profile)
        self.deployments[1]['status']['observedGeneration'] = 1
        with self.assertRaisesRegex(ValueError, 'rollout is not ready'):
            inspect_target(self.target, self.deployments, self.pods, self.profile)

    def test_unrelated_model_bindings_are_preserved_but_selected_binding_is_exact(self):
        value = self.deployments[0]['spec']['template']['spec']['containers'][0]['env'][1]
        catalog = json.loads(value['value'])
        catalog['unrelated:probe'] = {'credential': 'never-export-probe-credential'}
        value['value'] = json.dumps(catalog)
        result = inspect_target(self.target, self.deployments, self.pods, self.profile)
        self.assertEqual(result['additional_model_binding_count'], 1)
        self.assertNotIn('never-export-probe-credential', json.dumps(result))
        selected = next(iter(self.profile['brain']['model']['bindings']))
        catalog[selected]['contextWindow'] += 1
        value['value'] = json.dumps(catalog)
        with self.assertRaisesRegex(ValueError, 'catalog differs'):
            inspect_target(self.target, self.deployments, self.pods, self.profile)

    def test_pinned_release_resources_and_runtime_controls_are_enforced(self):
        evaluation = json.loads(Path(__file__).with_name('evaluation-profile.json').read_text())
        evaluation['deployment']['replicas'] = 1
        evaluation['deployment']['resources'] = {k: v for k, v in evaluation['deployment']['resources'].items()
                                                 if k in {'agent-service', 'agent-harness-runtime'}}
        release = {}
        for deployment in self.deployments:
            container = deployment['spec']['template']['spec']['containers'][0]
            component = deployment['metadata']['name'].removeprefix('ma-managed-agents-')
            resource = evaluation['deployment']['resources'][component]
            container['resources'] = {'requests': resource.copy(), 'limits': resource.copy()}
            container['env'] += [{'name': k, 'value': v} for k, v in
                                 runtime_environment(evaluation, self.target).get(component, {}).items()]
            container['image'] = 'fixture@sha256:' + 'a' * 64
            release['pi/' + component] = {'image': container['image']}
        for pod, deployment in zip(self.pods, self.deployments):
            pod['spec'] = {'containers': copy.deepcopy(deployment['spec']['template']['spec']['containers'])}
            pod['spec']['containers'][0]['resources']['requests']['ephemeral-storage'] = '30Gi'
            pod['status']['qosClass'] = 'Guaranteed'
            pod['metadata']['labels'].update(evaluation['deployment']['admissionPodLabels'])
            pod['spec']['initContainers'] = [{'name': 'wait-for-control', 'resources': {kind: evaluation['deployment']['initResources'] for kind in ['requests', 'limits']}}]
        inspect_target(self.target, self.deployments, self.pods, self.profile, evaluation, release)
        self.deployments[0]['spec']['template']['spec']['containers'][0]['resources']['limits']['cpu'] = '8'
        with self.assertRaisesRegex(ValueError, 'resources differ'):
            inspect_target(self.target, self.deployments, self.pods, self.profile, evaluation, release)
        self.deployments[0]['spec']['template']['spec']['containers'][0]['resources']['limits']['cpu'] = '4'
        self.pods[0]['spec']['containers'][0]['resources']['limits']['cpu'] = '8'
        with self.assertRaisesRegex(ValueError, 'admitted Pod resources differ'):
            inspect_target(self.target, self.deployments, self.pods, self.profile, evaluation, release)
        self.pods[0]['spec']['containers'][0]['resources']['limits']['cpu'] = '4'
        self.pods[0]['spec']['containers'][0]['resources']['requests']['ephemeral-storage'] = '20Gi'
        with self.assertRaisesRegex(ValueError, 'admitted Pod resources differ'):
            inspect_target(self.target, self.deployments, self.pods, self.profile, evaluation, release)
        self.pods[0]['spec']['containers'][0]['resources']['requests']['ephemeral-storage'] = '30Gi'
        self.pods[0]['status']['qosClass'] = 'Burstable'
        with self.assertRaisesRegex(ValueError, 'Pod QoS differs'):
            inspect_target(self.target, self.deployments, self.pods, self.profile, evaluation, release)
        self.pods[0]['status']['qosClass'] = 'Guaranteed'
        self.pods[0]['spec']['initContainers'][0]['resources'] = {}
        with self.assertRaisesRegex(ValueError, 'initializer resources differ'):
            inspect_target(self.target, self.deployments, self.pods, self.profile, evaluation, release)

    def test_snapshot_must_match_profiles_target_set_and_time(self):
        config = {'targets': [self.target]}
        evaluation = {'version': 1}
        snapshot = {'version': 1, 'passed': True, 'checked_at_epoch': time.time(),
                    'model_profile_sha256': digest(self.profile), 'evaluation_profile_sha256': digest(evaluation),
                    'targets': [self.target]}
        with tempfile.TemporaryDirectory() as root:
            path = Path(root, 'preflight.json')
            path.write_text(json.dumps(snapshot))
            check_preflight(path, config, self.profile, evaluation)
            with self.assertRaisesRegex(ValueError, 'profiles differ'):
                check_preflight(path, config, self.profile, {'version': 2})
            with self.assertRaisesRegex(ValueError, 'target set differs'):
                check_preflight(path, {'targets': []}, self.profile, evaluation)
            snapshot['checked_at_epoch'] -= 3601
            path.write_text(json.dumps(snapshot))
            with self.assertRaisesRegex(ValueError, 'last hour'):
                check_preflight(path, config, self.profile, evaluation)


if __name__ == '__main__':
    unittest.main()
