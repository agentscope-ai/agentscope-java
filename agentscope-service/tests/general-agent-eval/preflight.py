# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Read-only Kubernetes deployment checks; never reads Secrets or exports environment values."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time
import urllib.request

from evaluation import digest
from deployment import model_catalog, runtime_environment


def inspect_target(target, deployments, pods, profile, evaluation=None, release=None, gateway_base=None):
    by_name = {row['metadata']['name']: row for row in deployments}
    if evaluation and any('ma-managed-agents-' + c not in by_name for c in evaluation['deployment']['resources']):
        raise ValueError(target['name'] + ': an evaluation component is missing')
    service = by_name['ma-managed-agents-agent-service']
    brain = by_name['ma-managed-agents-agent-harness-runtime']
    settings = profile['brain']['model']
    env = {v['name']: v.get('value') for v in service['spec']['template']['spec']['containers'][0].get('env', [])}
    deployed_catalog = json.loads(env.get('MANAGED_AGENTS_MODEL_BINDINGS') or '{}')
    expected_catalog = model_catalog(profile, target, gateway_base)
    if {key: deployed_catalog.get(key) for key in expected_catalog} != expected_catalog:
        raise ValueError(target['name'] + ': deployed model catalog differs from the model profile')
    if env.get('MANAGED_AGENTS_MODEL_PROVIDER') != settings['provider']:
        raise ValueError(target['name'] + ': model provider differs')
    binding = next(iter(settings['bindings'].values()))
    brain_env = {v['name']: v for v in brain['spec']['template']['spec']['containers'][0].get('env', [])}
    if brain_env.get('MANAGED_AGENTS_RUNTIME_LOAD_TEST_MODEL_ENABLED', {}).get('value', '').lower() == 'true':
        raise ValueError(target['name'] + ': mock model is enabled')
    if not brain_env.get(binding['credentialEnv'], {}).get('valueFrom', {}).get('secretKeyRef'):
        raise ValueError(target['name'] + ': named model Secret projection is missing')
    resources = []
    for deployment in deployments:
        if not deployment['metadata']['name'].startswith('ma-managed-agents-'):
            continue
        spec, status = deployment['spec'], deployment.get('status', {})
        component = deployment['metadata']['name'].removeprefix('ma-managed-agents-')
        if evaluation:
            expected = evaluation['deployment']
            if spec.get('replicas', 1) != expected['replicas']:
                raise ValueError(target['name'] + ': replica count differs: ' + component)
            for container in spec['template']['spec']['containers']:
                if container.get('resources') != {'requests': expected['resources'][component], 'limits': expected['resources'][component]}:
                    raise ValueError(target['name'] + ': resources differ: ' + component)
                values = {v['name']: v.get('value') for v in container.get('env', [])}
                for key, value in runtime_environment(evaluation, target).get(component, {}).items():
                    if values.get(key) != value:
                        raise ValueError(target['name'] + ': runtime setting differs: ' + key)
                if release:
                    image = release[target['namespace'] + '/' + component]['image']
                    if '@sha256:' not in image or container['image'] != image:
                        raise ValueError(target['name'] + ': release image differs: ' + component)
        replicas = spec.get('replicas', 1)
        if replicas < 1 or status.get('observedGeneration', 0) < deployment['metadata'].get('generation', 1) or \
                status.get('updatedReplicas', 0) != replicas or status.get('availableReplicas', 0) != replicas:
            raise ValueError(target['name'] + ': rollout is not ready: ' + deployment['metadata']['name'])
        selector = spec['selector']['matchLabels']
        selected = [p for p in pods if not p['metadata'].get('deletionTimestamp') and
                    all(p['metadata'].get('labels', {}).get(k) == v for k, v in selector.items())]
        if len(selected) != replicas:
            raise ValueError(target['name'] + ': ambiguous Pod set')
        statuses = []
        pod_resources = []
        for pod in selected:
            actual = [{k: c.get(k) for k in ['name', 'image', 'resources']}
                      for c in pod.get('spec', {}).get('containers', [])]
            if evaluation:
                if pod.get('status', {}).get('qosClass') != expected.get('podQosClass'):
                    raise ValueError(target['name'] + ': admitted Pod QoS differs: ' + component)
                if any(pod['metadata'].get('labels', {}).get(k) != v
                       for k, v in expected.get('admissionPodLabels', {}).items()):
                    raise ValueError(target['name'] + ': admitted compute class differs: ' + component)
                init_resources = {kind: expected['initResources'] for kind in ['requests', 'limits']}
                if any(c.get('resources') != init_resources for c in pod.get('spec', {}).get('initContainers', [])):
                    raise ValueError(target['name'] + ': admitted initializer resources differ: ' + component)
                defaults = expected.get('admissionResourceDefaults', {})
                expected_resources = {kind: {**expected['resources'][component], **defaults.get(kind, {})}
                                      for kind in ['requests', 'limits']}
                if not actual or any(c['resources'] != expected_resources for c in actual):
                    raise ValueError(target['name'] + ': admitted Pod resources differ: ' + component)
                if release and any(c['image'] != release[target['namespace'] + '/' + component]['image'] for c in actual):
                    raise ValueError(target['name'] + ': admitted Pod image differs: ' + component)
            pod_resources.append({'name': pod['metadata'].get('name'), 'qos_class': pod.get('status', {}).get('qosClass'),
                                  'containers': actual, 'initializers': [{k: c.get(k) for k in ['name', 'image', 'resources']}
                                      for c in pod.get('spec', {}).get('initContainers', [])]})
            for container in pod.get('status', {}).get('containerStatuses', []):
                if not container.get('ready') or not container.get('imageID'):
                    raise ValueError(target['name'] + ': a container is not ready or has no image digest')
                statuses.append({k: container.get(k) for k in ['name', 'image', 'imageID', 'restartCount']})
            if not pod.get('status', {}).get('containerStatuses'):
                raise ValueError(target['name'] + ': missing Pod status')
        resources.append({'name': deployment['metadata']['name'], 'replicas': replicas,
            'containers': [{k: c.get(k) for k in ['name', 'image', 'resources']}
                           for c in spec['template']['spec']['containers']], 'running': statuses, 'admitted_pods': pod_resources})
    return {'name': target['name'], 'namespace': target['namespace'], 'deployments': resources,
            'model_catalog_sha256': digest(deployed_catalog),
            'additional_model_binding_count': len(set(deployed_catalog) - set(expected_catalog))}


def main(args):
    config = json.loads(Path(args.config).read_text())
    model = json.loads(Path(args.model_profile).read_text())
    evaluation = json.loads(Path(args.evaluation_profile).read_text())
    release = json.loads(Path(args.release_manifest).read_text())
    gateway_base = config.get('evaluation_gateway_cluster_base')
    if evaluation.get('gateway', {}).get('required') and not gateway_base:
        raise ValueError('Evaluation gateway transport is required')
    with urllib.request.urlopen(config['evaluation_gateway_url'].rstrip('/') + '/healthz', timeout=10) as response:
        health = json.load(response)
    if health.get('source_sha256') != hashlib.sha256(Path(__file__).with_name('evaluation_gateway.py').read_bytes()).hexdigest():
        raise ValueError('Running evaluation gateway source differs')
    if health.get('policy_sha256') != digest(evaluation['gateway']):
        raise ValueError('Running evaluation gateway policy differs')
    snapshot = {'version': 1, 'checked_at_epoch': time.time(), 'passed': False,
                'model_profile_sha256': digest(model), 'evaluation_profile_sha256': digest(evaluation),
                'release_sha256': digest(release), 'gateway_base': gateway_base,
                'gateway_health': health, 'targets': []}
    for target in config['targets']:
        def get(resource):
            command = ['kubectl', '--kubeconfig', args.kubeconfig, '--request-timeout=30s',
                       '-n', target['namespace'], 'get', resource, '-o', 'json']
            return json.loads(subprocess.check_output(command))['items']
        snapshot['targets'].append(inspect_target(target, get('deployment'), get('pod'), model, evaluation, release, gateway_base))
    snapshot['passed'] = True
    with Path(args.output).open('x') as output:
        json.dump(snapshot, output, ensure_ascii=False, indent=2)
    print('PASS: model catalog, credential references, rollout and image/resource snapshots')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['kubeconfig', 'config', 'model-profile', 'evaluation-profile', 'release-manifest', 'output']:
        parser.add_argument('--' + name, required=True)
    main(parser.parse_args())
