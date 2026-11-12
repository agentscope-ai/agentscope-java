# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Deploy PVC-backed benchmark services with Runner-only platform credentials."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def main(args):
    root = Path(__file__).parent
    config = json.loads(Path(args.config).read_text())
    if '@sha256:' not in args.image:
        raise ValueError('Environment image must be pinned by digest')
    k = ['kubectl', '--kubeconfig', args.kubeconfig, '-n', args.namespace]
    code = {p.name: p.read_text() for p in root.iterdir() if p.suffix in {'.py', '.json', '.lock'}}
    code['active-evaluation-profile.json'] = Path(args.evaluation_profile).read_text()
    revision = hashlib.sha256(json.dumps(code, sort_keys=True).encode()).hexdigest()
    env = [{'name': name, 'valueFrom': {'secretKeyRef': {'name': 'eval-transport-v2', 'key': key}}}
           for name, key in [('EVAL_CONTROL_KEY', 'control_key'), ('EVAL_TOOL_KEY', 'tool_key')]]
    mount = [{'name': 'state', 'mountPath': '/eval'}, {'name': 'code', 'mountPath': '/eval/code', 'readOnly': True}]
    python = '/eval/venvs/tau-bench/bin/python'
    probe = "import socket,urllib.request; urllib.request.urlopen('http://127.0.0.1:8002/healthz',timeout=2).read(); [socket.create_connection(('127.0.0.1',p),2).close() for p in [8000,8001,8003,9000,10000]]"
    labels = {'app': 'managed-agents-eval-v2'}
    documents = [
        {'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': 'eval-code-v2'}, 'data': code},
        {'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'eval-transport-v2'},
         'stringData': {k: config[k] for k in ['control_key', 'tool_key']}},
        {'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'eval-runner-v2'},
         'stringData': {'config.json': json.dumps(config)}},
        {'apiVersion': 'apps/v1', 'kind': 'Deployment', 'metadata': {'name': 'eval-environment-v2'},
         'spec': {'replicas': 1, 'strategy': {'type': 'Recreate'}, 'selector': {'matchLabels': labels},
             'template': {'metadata': {'labels': labels, 'annotations': {'evaluation/code-sha256': revision}},
                 'spec': {'automountServiceAccountToken': False, 'securityContext': {'runAsUser': 0, 'runAsGroup': 0},
                     'terminationGracePeriodSeconds': 20, 'containers': [
                        {'name': 'environment', 'image': args.image, 'command': [python, '/eval/code/supervise_environment.py'],
                         'env': env, 'volumeMounts': mount, 'resources': {'requests': {'cpu': '2', 'memory': '4Gi'},
                         'limits': {'cpu': '2', 'memory': '4Gi'}},
                         'readinessProbe': {'exec': {'command': [python, '-c', probe]}, 'periodSeconds': 10, 'timeoutSeconds': 5},
                         'startupProbe': {'exec': {'command': [python, '-c', probe]}, 'periodSeconds': 10, 'timeoutSeconds': 5, 'failureThreshold': 30}},
                        {'name': 'runner', 'image': args.image, 'command': ['python', '-c', 'import time; time.sleep(10**9)'],
                         'volumeMounts': mount + [{'name': 'runner-auth', 'mountPath': '/run/evaluation', 'readOnly': True}],
                         'resources': {'requests': {'cpu': '2', 'memory': '4Gi'}, 'limits': {'cpu': '2', 'memory': '4Gi'}}}],
                     'volumes': [{'name': 'state', 'persistentVolumeClaim': {'claimName': 'eval-state-v2'}},
                                 {'name': 'code', 'configMap': {'name': 'eval-code-v2'}},
                                 {'name': 'runner-auth', 'secret': {'secretName': 'eval-runner-v2', 'defaultMode': 256}}]}}}},
        {'apiVersion': 'v1', 'kind': 'Service', 'metadata': {'name': 'eval-gateway'},
         'spec': {'selector': labels, 'ports': [{'name': 'audit', 'port': 8002, 'targetPort': 8002}]}}]
    if bool(args.seed_url) != bool(args.seed_sha256):
        raise ValueError('Seed URL and checksum must be specified together')
    if args.seed_url:
        documents[3]['spec']['template']['spec']['initContainers'] = [{
            'name': 'seed-benchmarks', 'image': args.image,
            'command': ['python', '/eval/code/seed_environment.py', '--url', args.seed_url,
                        '--sha256', args.seed_sha256], 'volumeMounts': mount,
            'resources': {'requests': {'cpu': '2', 'memory': '4Gi'},
                          'limits': {'cpu': '2', 'memory': '4Gi'}}}]
    # The source ConfigMap can exceed the client-side last-applied annotation limit.
    subprocess.run(k + ['apply', '--server-side', '--field-manager=general-agent-eval', '-f', '-'],
                   input=json.dumps({'apiVersion': 'v1', 'kind': 'List', 'items': documents}),
                   text=True, check=True, stdout=subprocess.DEVNULL)
    print('Evaluation services applied; code SHA256 ' + revision)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['kubeconfig', 'config', 'image']:
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--evaluation-profile', default=str(Path(__file__).with_name('evaluation-profile.json')))
    parser.add_argument('--namespace', default='managed-agents-eval')
    parser.add_argument('--seed-url', help='Trusted immutable dependency/data seed for an empty PVC')
    parser.add_argument('--seed-sha256', help='Expected SHA256 of the seed archive')
    main(parser.parse_args())
