# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Apply the shared Helm model catalog to existing evaluation deployments.

Keeps unrelated workload configuration intact. Credentials are copied within each
namespace from the existing model Secret; no credential values are logged.
"""
import argparse
import json
from pathlib import Path
import subprocess
from deployment import model_catalog, runtime_environment, helm_overlay


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--kubeconfig', required=True)
    p.add_argument('--profile', required=True)
    p.add_argument('--namespaces', nargs='+', required=True)
    p.add_argument('--images', help='Verified image manifest from packaging')
    p.add_argument('--evaluation-profile', help='Apply the common evaluation resource and runtime contract')
    p.add_argument('--config', help='Runner configuration, required for audited evaluation deployment')
    p.add_argument('--overlay-dir', help='Write equivalent nonsecret Helm values for future upgrades')
    p.add_argument('--credential-secret', default='managed-agents-credentials')
    args = p.parse_args()
    images = json.loads(Path(args.images).read_text()) if args.images else {}
    profile = json.loads(Path(args.profile).read_text())
    evaluation = json.loads(Path(args.evaluation_profile).read_text()) if args.evaluation_profile else None
    config = json.loads(Path(args.config).read_text()) if args.config else {}
    if evaluation and not config.get('evaluation_gateway_cluster_base'):
        raise ValueError('Evaluation deployment requires a declared gateway')
    model = profile['brain']['model']
    if len(model['bindings']) != 1:
        raise ValueError('Expected one evaluation binding')
    binding = next(iter(model['bindings'].values()))
    key_name = binding['credentialEnv']
    base = ['kubectl', '--kubeconfig', args.kubeconfig]
    for ns in args.namespaces:
        target = next((t for t in config.get('targets', []) if t['namespace'] == ns), {'name': ns, 'namespace': ns})
        catalog = model_catalog(profile, target, config.get('evaluation_gateway_cluster_base'))
        def get(kind, name):
            return json.loads(subprocess.check_output(base + ['-n', ns, 'get', kind, name, '-o', 'json']))
        def patch(kind, name, value):
            subprocess.run(base + ['-n', ns, 'patch', kind, name, '--type=strategic', '--patch-file=/dev/stdin'],
                           input=json.dumps(value), text=True, check=True, stdout=subprocess.DEVNULL)
        credential = get('secret', args.credential_secret)['data']['DASHSCOPE_API_KEY']
        # A named projection keeps model credentials separate from platform credentials.
        patch('secret', args.credential_secret, {'data': {key_name: credential}})
        service = get('deployment', 'ma-managed-agents-agent-service')
        container = service['spec']['template']['spec']['containers'][0]['name']
        env = [{'name': 'MANAGED_AGENTS_MODEL_PROVIDER', 'value': model['provider']},
               {'name': 'MANAGED_AGENTS_MODEL_OPTIONS', 'value': '{}'},
               {'name': 'MANAGED_AGENTS_MODEL_BINDINGS', 'value': json.dumps(catalog, separators=(',', ':'))}]
        service_container = {'name': container, 'env': env}
        if images:
            service_container['image'] = images['agencore-ma/agent-service']['image']
        patch('deployment', service['metadata']['name'], {'spec': {'template': {'spec': {'containers': [service_container]}}}})
        brain = get('deployment', 'ma-managed-agents-agent-harness-runtime')
        container = brain['spec']['template']['spec']['containers'][0]['name']
        env = [{'name': key_name, 'valueFrom': {'secretKeyRef': {'name': args.credential_secret, 'key': key_name}}}]
        if '/managed-agents-agent-harness-runtime' in brain['spec']['template']['spec']['containers'][0]['image']:
            env += [{'name': 'MANAGED_AGENTS_RUNTIME_LOAD_TEST_MODEL_ENABLED', 'value': 'false'},
                    {'name': 'MANAGED_AGENTS_RUNTIME_MODEL_TIMEOUT_SECONDS', 'value': str(profile['agent-harness-runtime']['modelTimeoutSeconds'])},
                    {'name': 'MANAGED_AGENTS_MODEL_NAME', 'value': binding['id']}]
        if '/managed-agents-brain-codex' in brain['spec']['template']['spec']['containers'][0]['image']:
            env += [{'name': 'CODEX_MODEL_PROVIDER', 'value': binding['provider']},
                    {'name': 'CODEX_MODEL_BASE_URL', 'value': binding['baseUrl']}]
        brain_container = {'name': container, 'env': env}
        if images:
            brain_container['image'] = images[ns+'/agent-harness-runtime']['image']
        patch('deployment', brain['metadata']['name'], {'spec': {'template': {'spec': {'containers': [brain_container]}}}})
        if evaluation:
            for component, resources in evaluation['deployment']['resources'].items():
                deployment = get('deployment', 'ma-managed-agents-' + component)
                container = {'name': deployment['spec']['template']['spec']['containers'][0]['name'],
                             'resources': {'$patch': 'replace', 'requests': resources, 'limits': resources},
                             'env': [{'name': k, 'value': v} for k, v in runtime_environment(evaluation, target).get(component, {}).items()]}
                if images and ns + '/' + component in images:
                    container['image'] = images[ns + '/' + component]['image']
                pod = {'containers': [container]}
                for init in deployment['spec']['template']['spec'].get('initContainers', []):
                    if init['name'] != 'wait-for-control':
                        raise ValueError('Unknown initializer requires an explicit resource contract')
                    resource = evaluation['deployment']['initResources']
                    pod.setdefault('initContainers', []).append({'name': init['name'],
                        'resources': {'$patch': 'replace', 'requests': resource, 'limits': resource}})
                patch('deployment', deployment['metadata']['name'], {'spec': {'replicas': evaluation['deployment']['replicas'],
                    'template': {'spec': pod}}})
        if args.overlay_dir:
            if not evaluation or not images:
                raise ValueError('Helm overlays require an evaluation profile and release manifest')
            directory = Path(args.overlay_dir)
            directory.mkdir(parents=True, exist_ok=True)
            (directory / (ns + '.json')).write_text(json.dumps(helm_overlay(profile, evaluation, target,
                config['evaluation_gateway_cluster_base'], images), indent=2))
        print(ns + ': shared catalog and credential projection applied')


if __name__ == '__main__':
    main()
