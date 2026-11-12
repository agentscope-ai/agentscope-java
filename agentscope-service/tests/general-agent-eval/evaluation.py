# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Common evaluation contracts. No Agent reasoning loop is implemented here."""
import hashlib
import json
import socket
from pathlib import Path
import time
import urllib.request
import urllib.error
import uuid


class CaseBudgetExceeded(RuntimeError):
    pass


class HarnessFailure(RuntimeError):
    """The tested execution failed or produced an invalid terminal response."""


class ProviderFailure(RuntimeError):
    """A model provider rejected or interrupted a request."""


class ContractViolation(RuntimeError):
    """A run violated the frozen evaluation inputs or capabilities."""


def tree_fingerprint(directory):
    """Hash every persisted file in an official database snapshot, with relative names."""
    directory = Path(directory)
    files = {str(p.relative_to(directory)): hashlib.sha256(p.read_bytes()).hexdigest()
             for p in sorted(directory.rglob('*')) if p.is_file() and p.name not in {'.DS_Store'}}
    if not files:
        raise ContractViolation('Initial database snapshot is empty')
    return {'sha256': digest(files), 'files': files}


class EvaluationGateway:
    def __init__(self, config, lease, row, system, initial_hash, input_key):
        self.config, self.lease, self.row = config, lease, row
        if not config.get('evaluation_gateway_url'):
            raise ContractViolation('The audited evaluation gateway is required')
        self.call('start', {'target': row['target'], 'system': system,
                            'initial_hash': initial_hash, 'input_key': input_key,
                            'declared_tool_namespace': {'fixture': 'fixture', 'tau': 'retail', 'appworld': 'appworld',
                                                        'terminal': 'terminal'}[lease.slot]})

    def call(self, operation, data=None):
        request = urllib.request.Request(self.config['evaluation_gateway_url'].rstrip('/') + '/control/' + operation,
            json.dumps({**(data or {}), 'slot': self.lease.slot, 'owner': self.lease.owner,
                        'trial': self.row['trial']}).encode(),
            {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + self.config['control_key']})
        with urllib.request.urlopen(request, timeout=45) as response:
            return json.load(response)

    def snapshot(self):
        requests = self.call('audit')['requests']
        self.row['request_audit'] = requests
        violations = [v for r in requests for v in r['audit'].get('violations', [])]
        if violations:
            self.row['status'] = 'context_out_of_scope' if set(violations) == {'context_limit'} else 'contract_violation'
            self.row['contract_violations'] = violations
        elif any(r['status'] == 'provider_error' for r in requests) and self.row['status'] != 'completed':
            self.row['status'] = 'provider_error'
        elif self.row['status'] != 'completed' and any(
                r['kind'] == 'model' and r['status'] == 'failed' for r in requests):
            self.row['status'] = 'infrastructure_error'
            self.row['failure_origin'] = 'model_transport_downstream'
        elif self.row['status'] != 'completed' and any(
                r['kind'] == 'mcp' and (r['status'] in {'failed', 'pending'} or
                                       r['audit'].get('http_status', 200) >= 300) for r in requests):
            self.row['status'] = 'infrastructure_error'
            self.row['failure_origin'] = 'mcp_initialization_or_catalog'
        if self.row['status'] == 'completed':
            self.call('finish')
        return requests


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'),
                                    ensure_ascii=False).encode()).hexdigest()


def check_preflight(path, config, model, evaluation):
    snapshot = json.loads(Path(path).read_text())
    if snapshot.get('version') != 1 or snapshot.get('passed') is not True or \
            not 0 <= time.time() - snapshot.get('checked_at_epoch', 0) <= 3600:
        raise ValueError('A successful deployment preflight from the last hour is required')
    if snapshot['model_profile_sha256'] != digest(model) or snapshot['evaluation_profile_sha256'] != digest(evaluation):
        raise ValueError('Preflight profiles differ from this run')
    targets = lambda rows: sorted((row['name'], row['namespace']) for row in rows)
    if targets(snapshot['targets']) != targets(config['targets']):
        raise ValueError('Preflight target set differs from this run')
    return snapshot


def load_profile(path, benchmark):
    profile = json.loads(Path(path).read_text())
    if profile.get('version') != 1 or profile.get('ordering') != 'task-repeat-rotating-targets':
        raise ValueError('Unsupported evaluation profile')
    settings = profile['benchmarks'][benchmark]
    for key in ['caseTimeoutSeconds', 'turnTimeoutSeconds', 'maxModelCalls',
                'maxTotalTokens', 'maxToolCalls']:
        if type(settings.get(key)) is not int or settings[key] < 1:
            raise ValueError('Invalid evaluation limit: ' + key)
    if type(settings.get('maxRecoveryAttempts')) is not int or settings['maxRecoveryAttempts'] < 0:
        raise ValueError('Invalid recovery budget')
    if profile['nativeIterationGuard'] <= settings['maxModelCalls']:
        raise ValueError('Native guard must exceed the common model budget')
    if benchmark == 'tau' and settings.get('maxActions') != 30:
        raise ValueError('Original tau-bench environment requires the official 30 action limit')
    return profile, settings


def cases(targets, tasks, repeats):
    if not targets or not tasks or repeats < 1:
        raise ValueError('Targets, tasks and positive repeats required')
    for index, task in enumerate(tasks):
        for repeat in range(repeats):
            shift = (index + repeat) % len(targets)
            for target in targets[shift:] + targets[:shift]:
                yield target, task, repeat


def admit_case(client, session, settings, admitted_at):
    limits = {'max_model_calls': settings['maxModelCalls'],
              'max_total_tokens': settings['maxTotalTokens'],
              'max_tool_calls': settings['maxToolCalls'],
              'max_recovery_attempts': settings['maxRecoveryAttempts'],
              'deadline_epoch_ms': int((admitted_at + settings['caseTimeoutSeconds']) * 1000)}
    result = client.api(f'/v1/sessions/{session}/budget', limits, method='PUT')
    if result.get('limits') != limits:
        raise RuntimeError('Server did not accept the exact evaluation budget')
    return limits


def remaining(limits, turn_limit):
    seconds = limits['deadline_epoch_ms'] / 1000 - time.time()
    if seconds <= 0:
        raise CaseBudgetExceeded('Case deadline exceeded')
    return min(seconds, turn_limit)


def failure_status(error):
    if isinstance(error, CaseBudgetExceeded):
        return 'budget_exceeded'
    if isinstance(error, HarnessFailure):
        return 'harness_error'
    if isinstance(error, ProviderFailure):
        return 'provider_error'
    if isinstance(error, ContractViolation):
        return 'contract_violation'
    if isinstance(error, (urllib.error.URLError, TimeoutError, ConnectionError, socket.timeout)):
        return 'infrastructure_error'
    # Unknown failures must not be silently excluded from the success-rate denominator.
    return 'unknown_error'


def require_completed(state):
    if state.get('status') == 'completed':
        return
    if 'budget_exceeded' in json.dumps(state):
        raise CaseBudgetExceeded('Platform case budget exhausted')
    raise HarnessFailure('Turn ended ' + str(state.get('status')))


def finish_case(client, row):
    """Preserve accounting and cancellation evidence, including on uncertain transport failures."""
    row['turn_timings'] = getattr(client, 'turn_timings', {})
    if 'session' not in row:
        return
    if row['status'] != 'completed' and 'turn' in row:
        try:
            row['cancellation'] = client.cancel(row['session'], row['turn'])
        except Exception as error:
            row['cancellation_error'] = type(error).__name__ + ': ' + str(error)
    try:
        row['budget_usage'] = client.api(f"/v1/sessions/{row['session']}/budget")
    except Exception as error:
        row['accounting_error'] = type(error).__name__ + ': ' + str(error)
        if row['status'] == 'completed':
            row['status'] = 'infrastructure_error'


class EnvironmentLease:
    """No automatic expiration: uncertain external execution must retain its slot."""
    def __init__(self, config, slot, output):
        self.config, self.slot = config, slot
        self.owner = str(uuid.uuid4())
        Path(output, 'environment-lease.json').write_text(json.dumps(
            {'slot': slot, 'owner': self.owner, 'status': 'acquiring'}, indent=2))
        self.path = Path(output, 'environment-lease.json')
        self.call('/lease/acquire', {})
        self.path.write_text(json.dumps({'slot': slot, 'owner': self.owner, 'status': 'held'}, indent=2))

    def call(self, path, body):
        request = urllib.request.Request(self.config['control_url'] + path,
            json.dumps({**body, 'slot': self.slot, 'owner': self.owner}).encode(),
            {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + self.config['control_key']})
        with urllib.request.urlopen(request, timeout=90) as response:
            return json.load(response)

    def release(self):
        self.call('/lease/release', {})
        self.path.write_text(json.dumps({'slot': self.slot, 'owner': self.owner, 'status': 'released'}, indent=2))
