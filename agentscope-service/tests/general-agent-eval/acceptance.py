# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Real Session API acceptance with synthetic tools; no benchmark scoring modifications."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import uuid

from evaluation import CaseBudgetExceeded, EnvironmentLease, EvaluationGateway, admit_case, digest, finish_case, require_completed
from session_client import SessionClient


def run(args):
    config = json.loads(Path(args.config).read_text())
    profile = json.loads(Path(args.profile).read_text())
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    model_profile = json.loads(Path(args.model_profile).read_text())
    model = next(iter(model_profile['brain']['model']['bindings']))
    lease = EnvironmentLease(config, 'fixture', output)
    def save(name, value):
        (output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2))
    save('manifest.json', {'profile_sha256': digest(profile), 'model_profile_sha256': digest(model_profile),
                          'scenarios': args.scenarios,
                          'targets': [t['name'] for t in config['targets']]})
    for target in config['targets']:
        if args.target and target['name'] not in args.target:
            continue
        client = SessionClient(target['cluster_url'], target['headers'])
        envs = client.api('/api/environments')
        environment = next(e['id'] for e in envs if e['name'] == 'general-agent-eval-20261030')
        for scenario in args.scenarios:
            trial = 'acceptance-' + target['name'] + '-' + scenario + '-' + uuid.uuid4().hex[:8]
            row = {'trial': trial, 'target': target['name'], 'scenario': scenario,
                   'started': time.time(), 'status': 'running'}
            system = 'You are a test assistant. Follow the user instructions exactly. Use only the supplied tools.'
            gateway = EvaluationGateway(config, lease, row, system, 'synthetic-empty-v1', 'fixture-v1')
            try:
                if scenario in {'provider_429', 'disconnect', 'lease_renewal'}:
                    # Set the controlled fault before any model request or Session exists.
                    gateway.call('fixture-fault', {'fault': 'model_delay' if scenario == 'lease_renewal' else scenario})
                definition = {'name': 'Consistency acceptance', 'system': system, 'model': model,
                    'maxIters': profile['nativeIterationGuard'], 'defaultEnvironmentId': environment,
                    'tools': [{'type': 'agent_toolset', 'defaultConfig': {'enabled': False}},
                              {'type': 'mcp_toolset', 'mcpServerName': 'fixture',
                               'defaultConfig': {'enabled': True, 'permissionPolicy': {'type': 'always_allow'}}}],
                    'mcpServers': [{'name': 'fixture', 'transport': 'http', 'required': True,
                        'url': config['evaluation_gateway_cluster_base'].rstrip('/') + '/fixture/' + trial + '/mcp/',
                        'headers': {'Authorization': 'Bearer ' + config['tool_key']},
                        'timeout': 'PT90S', 'initializationTimeout': 'PT30S'}]}
                agent = client.api('/api/v1/agents', {'agentKey': 'acceptance-' + uuid.uuid4().hex[:12],
                    'displayName': definition['name'], 'binding': {'kind': 'managed'}, 'definition': definition})['agent']['id']
                row['session'] = client.create(agent, environment, {'trial': trial}, str(uuid.uuid4()))['id']
                settings = {**profile['benchmarks']['appworld'], 'maxModelCalls': 6, 'maxTotalTokens': 200000,
                            'caseTimeoutSeconds': 360}
                if scenario == 'multiturn_budget':
                    settings['maxModelCalls'] = 1
                if scenario == 'tool_results':
                    settings.update(maxModelCalls=16, maxTotalTokens=1000000)
                row['budget'] = admit_case(client, row['session'], settings, row['started'])
                prompt = 'Reply with exactly READY. Do not call tools.'
                if scenario == 'tool_results':
                    prompt = 'Call result_fixture exactly once with variant nested. Then reply DONE. Do not retry any tool error.'
                if scenario == 'restart':
                    prompt = 'Call record_and_wait exactly once with value "restart-proof" and seconds 40. After it returns, reply DONE. Do not repeat the call.'
                row['turn'] = client.send(row['session'], prompt, str(uuid.uuid4()))
                save(trial + '.json', row)
                if scenario == 'restart':
                    deadline = time.monotonic() + 100
                    while True:
                        calls = gateway.call('fixture-state')['calls']
                        if calls:
                            if calls[0]['finished'] is not None:
                                raise AssertionError('Tool completed before restart injection')
                            break
                        if time.monotonic() >= deadline:
                            raise TimeoutError('Fixture tool was not invoked')
                        time.sleep(.5)
                    kube = ['kubectl', '--kubeconfig', args.kubeconfig, '-n', target['namespace']]
                    deployment = json.loads(subprocess.check_output(kube + ['get', 'deployment',
                        'ma-managed-agents-agent-harness-runtime', '-o', 'json']))
                    selector = ','.join(k + '=' + v for k, v in deployment['spec']['selector']['matchLabels'].items())
                    pods = json.loads(subprocess.check_output(kube + ['get', 'pods', '-l', selector, '-o', 'json']))['items']
                    row['restarted_pods'] = [{'name': p['metadata']['name'], 'uid': p['metadata']['uid']} for p in pods]
                    row['restart_injected_at'] = time.time()
                    # A normal rollout may keep both owners alive until the tool has
                    # finished. Kill the captured owners during the outstanding call;
                    # the Deployment recreates them from the same verified template.
                    subprocess.run(kube + ['delete', 'pod'] + [p['metadata']['name'] for p in pods] +
                        ['--grace-period=1', '--wait=false'], capture_output=True, text=True, check=True, timeout=30)
                    deadline = time.monotonic() + 60
                    while True:
                        current = json.loads(subprocess.check_output(kube + ['get', 'pods', '-l', selector, '-o', 'json']))['items']
                        old = {p['uid'] for p in row['restarted_pods']}
                        if not any(p['metadata']['uid'] in old for p in current):
                            row['old_owners_gone_at'] = time.time()
                            break
                        if time.monotonic() >= deadline:
                            raise TimeoutError('Original Harness owners did not terminate')
                        time.sleep(1)
                try:
                    state, events = client.wait(row['session'], row['turn'], timeout=180, monitor_budget=True)
                except CaseBudgetExceeded:
                    row['budget_cancel_receipt'] = client.cancel(row['session'], row['turn'])
                    state, events = client.wait(row['session'], row['turn'], timeout=60)
                row['turns'] = [{'state': state, 'events': events}]
                if scenario in {'provider_429', 'disconnect'}:
                    if state['status'] not in {'failed', 'cancelled'}:
                        raise AssertionError('Uncertain failed request unexpectedly completed')
                else:
                    require_completed(state)
                    if scenario in {'text', 'multiturn_budget', 'lease_renewal'} and client.text(events).strip() != 'READY':
                        raise AssertionError('Expected an actual READY response from the model')
                    if scenario == 'tool_results':
                        for variant in ['long', 'empty', 'error', 'overflow']:
                            row['turn'] = client.send(row['session'], 'Call result_fixture exactly once with variant ' + variant +
                                '. Then reply DONE. Errors are expected: do not repeat the call.', str(uuid.uuid4()))
                            save(trial + '.json', row)
                            state, events = client.wait(row['session'], row['turn'], timeout=120, monitor_budget=True)
                            row['turns'].append({'state': state, 'events': events})
                            require_completed(state)
                    if scenario == 'multiturn_budget':
                        row['turn'] = client.send(row['session'], 'Reply READY again without tools.', str(uuid.uuid4()))
                        try:
                            state, events = client.wait(row['session'], row['turn'], timeout=100, monitor_budget=True)
                        except CaseBudgetExceeded:
                            row['budget_cancel_receipt'] = client.cancel(row['session'], row['turn'])
                            state, events = client.wait(row['session'], row['turn'], timeout=60)
                        row['turns'].append({'state': state, 'events': events})
                        budget = client.api('/v1/sessions/' + row['session'] + '/budget')
                        if 'budget_exceeded' not in json.dumps(budget):
                            raise AssertionError('Second Turn did not exhaust the shared model budget')
                    if scenario == 'restart':
                        calls = gateway.call('fixture-state')['calls']
                        row['fixture_calls'] = calls
                        if len(calls) != 1 or calls[0]['finished'] is None:
                            raise AssertionError('Restart duplicated or lost the external side effect')
                        if row['old_owners_gone_at'] >= calls[0]['finished']:
                            raise AssertionError('Restart did not interrupt the outstanding tool wait')
                row['request_audit'] = gateway.call('audit')['requests']
                models = [r for r in row['request_audit'] if r['kind'] == 'model']
                if any(r['audit'].get('violations') for r in models):
                    raise AssertionError('Actual model request violated the common profile')
                if scenario == 'tool_results':
                    calls = [r['audit'] for r in row['request_audit'] if r['kind'] == 'tool']
                    if len(calls) != 5 or sum(bool(c.get('overflow')) for c in calls) != 1:
                        raise AssertionError('Expected five distinct result fixtures and one visible overflow')
                    delivered = {}
                    for call in calls:
                        identity = call.get('meta', {}).get('io.agentscope/toolCallId')
                        if not identity:
                            raise AssertionError('Tool invocation identity was not propagated')
                        # PI's Responses SDK retains call_id|item_id as its native ID.
                        delivered[identity.split('|')[0]] = call['delivered_result_sha256']
                    observed = {r['call_id']: r['result_sha256'] for model in models for r in model['audit']['tool_results']}
                    if any(observed.get(key) != value for key, value in delivered.items()):
                        raise AssertionError('Effective model tool content differs from the delivered MCP result')
                if scenario in {'text', 'multiturn_budget', 'lease_renewal'} and len(models) != 1:
                    raise AssertionError('Model budget did not persist between Turns')
                if scenario == 'lease_renewal':
                    usage = client.api('/v1/sessions/' + row['session'] + '/budget')['usage']
                    if usage.get('execution_attempts') != 1 or usage.get('recovery_attempts', 0) != 0:
                        raise AssertionError('Live HTTP owner was taken over during model wait')
                    if models[0]['audit'].get('injected_model_delay_seconds') != 75:
                        raise AssertionError('Lease-renewal delay was not applied')
                if scenario in {'provider_429', 'disconnect'} and len(models) != 1:
                    raise AssertionError('A failed/unknown model request was retried outside the declared policy')
                gateway.call('finish')
                row['status'] = 'passed'
            except Exception as error:
                row.update(status='failed', error=type(error).__name__ + ': ' + str(error))
                try:
                    row['request_audit'] = gateway.call('audit')['requests']
                except Exception:
                    pass
                finish_case(client, row)
            finally:
                if row['status'] == 'passed':
                    row['budget_usage'] = client.api('/v1/sessions/' + row['session'] + '/budget')
                row['elapsed_seconds'] = time.time() - row['started']
                save(trial + '.json', row)
                with (output / 'results.jsonl').open('a') as stream:
                    stream.write(json.dumps(row) + '\n')
                print(json.dumps({k: row[k] for k in ['trial', 'status', 'elapsed_seconds']}), flush=True)
            if row['status'] != 'passed':
                raise RuntimeError('Acceptance failed; evidence and environment lease retained')
    lease.release()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['config', 'profile', 'model-profile', 'output', 'kubeconfig']:
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--target', nargs='+')
    parser.add_argument('--scenarios', nargs='+', choices=['text', 'tool_results', 'multiturn_budget', 'provider_429', 'disconnect', 'restart', 'lease_renewal'],
                        default=['text', 'tool_results', 'multiturn_budget', 'provider_429', 'disconnect', 'restart', 'lease_renewal'])
    run(parser.parse_args())
