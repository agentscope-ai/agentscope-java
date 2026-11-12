# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""One Session API turn against a Harbor sandbox; no agent reasoning loop here.

The caller owns the environment, lease and MCP server. It may invoke the official
verifier only after ready_for_verifier. An exception retains the slot for inspection.
"""
import asyncio
import json
from pathlib import Path
import shlex
import time
import uuid

from evaluation import (CaseBudgetExceeded, ContractViolation, EvaluationGateway,
                        admit_case, digest, failure_status, remaining, require_completed)
from session_client import SessionClient
from sandbox_eval.exec_transport import ExecutionOutcomeUnknown
from sandbox_eval.terminal_bridge import TOOL_CATALOG


SYSTEM = (
    'Complete the user task in the supplied Linux sandbox using the terminal tools. '
    'All terminal commands operate in the same task filesystem. Read tool descriptions, '
    'inspect the environment and verify your work. Running processes are not completed '
    'commands: poll for their exit status and output. Use an interactive shell when '
    'persistent working directory or shell variables are needed. Do not invent results. '
    'When the task is complete, return a concise final response.'
)


def validate_accounting(budget, requests):
    """A completed Turn alone is insufficient to admit the official verifier."""
    if not requests or any(q.get('status') != 'succeeded' or q.get('finished') is None
                           for q in requests):
        raise ExecutionOutcomeUnknown('An audited request has no successful terminal receipt')
    models = [q for q in requests if q['kind'] == 'model']
    tools = [q for q in requests if q['kind'] == 'tool']
    if not models or any(q['audit'].get('stream_completed') is not True or
                         type(q['audit'].get('usage', {}).get('total_tokens')) is not int
                         for q in models):
        raise ExecutionOutcomeUnknown('Provider model usage is incomplete')
    usage = budget['usage']
    total = sum(q['audit']['usage']['total_tokens'] for q in models)
    if (usage.get('in_flight_calls', 0) or usage.get('unreported_calls', 0) or
            usage.get('model_calls') != len(models) or usage.get('tool_calls') != len(tools) or
            usage.get('total_tokens') != total):
        raise ExecutionOutcomeUnknown('Provider and platform model accounting differ')
    return {'model_calls': len(models), 'tool_calls': len(tools), 'total_tokens': total,
            'input_tokens': sum(q['audit']['usage'].get('input_tokens', 0) for q in models),
            'output_tokens': sum(q['audit']['usage'].get('output_tokens', 0) for q in models)}


class SessionDriver:
    def __init__(self, config, target, model, settings, max_iters, lease, terminal,
                 bridge, output, trial, task_id, initial_hash, dataset_revision,
                 environment_id, client=None, comparison_namespace=None, benchmark='terminal-swe'):
        self.config, self.target, self.model = config, target, model
        self.settings, self.max_iters = settings, max_iters
        self.lease, self.terminal, self.bridge = lease, terminal, bridge
        self.output = Path(output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.environment_id = environment_id
        self.benchmark = benchmark
        if comparison_namespace is not None and (
                not isinstance(comparison_namespace, str) or not comparison_namespace or
                len(comparison_namespace) > 128):
            raise ValueError('Invalid explicit comparison namespace')
        self.comparison_namespace = comparison_namespace
        self.client = client or SessionClient(target['cluster_url'], target['headers'])
        self.row = {'trial': trial, 'task': task_id, 'target': target['name'], 'repeat': 0,
                    'status': 'prepared', 'reward': None, 'initial_hash': initial_hash,
                    'dataset_revision': dataset_revision, 'tool_catalog_sha256': digest(TOOL_CATALOG),
                    'available_tool_count': len(TOOL_CATALOG)}
        self.gateway = None

    def comparison_key(self):
        original = self.row['dataset_revision'] + ':' + self.row['task']
        if self.comparison_namespace is None:
            return original
        return digest({'namespace': self.comparison_namespace, 'dataset_task': original})

    def save(self, name, value):
        path = self.output / name
        temporary = path.with_suffix(path.suffix + '.writing')
        temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str))
        temporary.replace(path)

    def checkpoint(self):
        self.save('session-result.json', self.row)

    async def call(self, method, *args, **kwargs):
        # MCP runs on this event loop. Synchronous Session HTTP must not block it.
        return await asyncio.to_thread(method, *args, **kwargs)

    def definition(self):
        return {'name': 'Managed terminal evaluation', 'model': self.model,
                'maxIters': self.max_iters, 'defaultEnvironmentId': self.environment_id,
                'system': SYSTEM,
                'tools': [{'type': 'agent_toolset', 'defaultConfig': {'enabled': False}},
                          {'type': 'mcp_toolset', 'mcpServerName': 'terminal', 'defaultConfig': {
                              'enabled': True, 'permissionPolicy': {'type': 'always_allow'}}}],
                'mcpServers': [{'name': 'terminal', 'transport': 'http', 'required': True,
                    'url': self.config['evaluation_gateway_cluster_base'].rstrip('/') +
                           '/terminal/' + self.row['trial'] + '/mcp/',
                    'headers': {'Authorization': 'Bearer ' + self.config['tool_key']},
                    'timeout': 'PT90S', 'initializationTimeout': 'PT90S'}]}

    async def wait(self):
        row = self.row
        while True:
            if row['trial'] in self.terminal.unknown:
                raise ExecutionOutcomeUnknown(self.terminal.unknown[row['trial']])
            remaining(row['budget'], self.settings['turnTimeoutSeconds'])
            budget = await self.call(self.client.api, '/v1/sessions/' + row['session'] + '/budget')
            denial = budget.get('usage', {}).get('last_denial')
            if denial and denial.get('code') == 'budget_exceeded':
                raise CaseBudgetExceeded('Platform budget denied execution: ' + denial['limit'])
            path = '/v1/sessions/' + row['session'] + '/turns/' + row['turn']
            turn = await self.call(self.client.api, path)
            if turn['status'] in {'completed', 'failed', 'cancelled'}:
                self.save('turn.json', turn)
                require_completed(turn)
                output = await self.call(self.client.output, path)
                row['public_output_observed'] = output is not None
                if output is not None:
                    self.save('turn-output.json', output)
                # A terminal turn may have no public text (for example, output
                # tokens consumed by reasoning). Do not wait for text that can
                # no longer arrive. Tool and accounting checks still run before
                # ordinary verifier admission; this does not invent a score.
                return
            await asyncio.sleep(2)

    async def admit_terminal_artifact(self):
        """Independently admit the frozen artifact after an execution failure.

        This does not settle unknown model usage, repeat a tool, extend the task
        budget, or turn an execution failure into end-to-end success. Uncertain
        tool effects and live processes continue to require operator inspection.
        """
        row = self.row
        if not self.gateway or not row.get('session') or not row.get('turn'):
            raise ExecutionOutcomeUnknown('No exact Session/Turn for artifact admission')
        if row['trial'] in self.terminal.unknown:
            raise ExecutionOutcomeUnknown('A terminal operation has an unknown outcome')
        with (self.output / 'artifact-admission-intent.json').open('x') as stream:
            json.dump({'trial': row['trial'], 'at': time.time()}, stream)
        await self.bridge.close()
        self.save('original-execution-failure.json', dict(row))
        path = '/v1/sessions/' + row['session'] + '/turns/' + row['turn']
        # A cancellation acknowledgement can precede the persisted terminal
        # state. Observe the same Turn for a bounded interval, without another
        # cancel/send or allowing the closed bridge to execute further tools.
        observations = []
        for attempt in range(7):
            turn = await self.call(self.client.api, path)
            observations.append({'at': time.time(), 'status': turn.get('status')})
            self.save('artifact-admission-turn-observations.json', observations)
            self.save('artifact-admission-turn.json', turn)
            if turn.get('status') in {'completed', 'failed', 'cancelled'}:
                break
            if attempt == 6:
                raise ExecutionOutcomeUnknown('Turn is not terminal; no artifact scoring')
            await asyncio.sleep(5)
        # Cancellation can finish the Turn before the transport writes its final
        # receipt. Read only; never settle or replay the outstanding request.
        for attempt in range(7):
            audit = await self.call(self.gateway.call, 'audit')
            requests = audit['requests']
            if all(q.get('finished') is not None and q.get('status') != 'pending' for q in requests):
                break
            if attempt == 6:
                raise ExecutionOutcomeUnknown('Audited I/O is still in flight')
            await asyncio.sleep(5)
        self.save('artifact-admission-audit.json', audit)
        if any(q['kind'] in {'tool', 'mcp'} and q['status'] != 'succeeded' for q in requests):
            raise ExecutionOutcomeUnknown('Tool/catalog execution was not confirmed')
        if any(q.get('audit', {}).get('violations') for q in requests):
            raise ExecutionOutcomeUnknown('Contract violation requires explicit review')
        probe = (
            'from pathlib import Path;import json;'
            'r=Path(' + repr(self.bridge.root) + ');'
            'assert r.is_dir();'
            'print(json.dumps({p.parent.name:json.loads(p.read_text()) '
            'for p in r.glob("*/receipt.json")}))'
        )
        response = await self.bridge.environment.exec('python3 -c ' + shlex.quote(probe), timeout_sec=30)
        if response.return_code != 0:
            raise ExecutionOutcomeUnknown('Cannot establish terminal process quiescence')
        processes = json.loads(response.stdout)
        self.save('artifact-admission-processes.json', processes)
        if any(p.get('status') != 'exited' for p in processes.values()):
            raise ExecutionOutcomeUnknown('A task process is still running')
        budget = await self.call(self.client.api, '/v1/sessions/' + row['session'] + '/budget')
        self.save('artifact-admission-budget.json', budget)
        self.save('artifact-fence-intent.json', {'trial': row['trial'], 'at': time.time()})
        fenced = await self.call(self.gateway.call, 'finish')
        self.save('artifact-fence-receipt.json', fenced)
        if fenced.get('status') != 'closed' or digest(fenced.get('requests')) != digest(requests):
            raise ExecutionOutcomeUnknown('Audit changed during artifact fencing')
        row['execution_failure'] = {'status': row['status'], 'error': row.get('error')}
        row['budget_usage'] = budget
        row['artifact_scoring_admission'] = {'model_replayed': False, 'tool_replayed': False,
                                           'unknown_usage_preserved': True, 'at': time.time()}
        row['status'] = 'ready_for_artifact_verifier'
        self.checkpoint()
        return row

    async def run(self, instruction):
        row = self.row
        # The intent is exclusive and written before any mutation. A process
        # restart never means permission to create another Session or send a turn.
        with (self.output / 'run-intent.json').open('x') as stream:
            json.dump({'trial': row['trial'], 'at': time.time()}, stream)
        row.update(started=time.time(), status='running', instruction_sha256=digest(instruction),
                   agent_key='terminal-eval-' + uuid.uuid4().hex[:16],
                   create_key=str(uuid.uuid4()), send_key=str(uuid.uuid4()))
        self.checkpoint()
        try:
            self.terminal.bind(row['trial'], self.bridge)
            definition = self.definition()
            normalized = {**definition, 'defaultEnvironmentId': '<evaluation-environment>',
                          'mcpServers': [{k: v for k, v in s.items() if k not in {'url', 'headers'}}
                                         for s in definition['mcpServers']]}
            row['definition_sha256'] = digest(normalized)
            self.save('definition.json', normalized)
            self.save('instruction.json', {'instruction': instruction})
            row['comparison_namespace'] = self.comparison_namespace
            row['comparison_key'] = self.comparison_key()
            self.gateway = await self.call(EvaluationGateway, self.config, self.lease, row,
                                           SYSTEM, row['initial_hash'], row['comparison_key'])
            created = await self.call(self.client.api, '/api/v1/agents', {
                'agentKey': row['agent_key'], 'displayName': definition['name'],
                'binding': {'kind': 'managed'}, 'definition': definition})
            row['agent'] = created['agent']['id']
            self.checkpoint()
            session = await self.call(self.client.create, row['agent'], self.environment_id,
                {'benchmark': self.benchmark, 'trial': row['trial']}, row['create_key'])
            row['session'] = session['id']
            self.checkpoint()
            row['budget'] = await self.call(admit_case, self.client, row['session'], self.settings, row['started'])
            self.checkpoint()
            row['turn'] = await self.call(self.client.send, row['session'], instruction, row['send_key'])
            self.checkpoint()
            await self.wait()
            await self.bridge.close()
            row['status'] = 'execution_completed'
            audit = await self.call(self.gateway.snapshot)
            self.save('audit.json', audit)
            if row['status'] != 'execution_completed':
                raise ContractViolation('Audit rejected execution: ' + row['status'])
            row['budget_usage'] = await self.call(self.client.api, '/v1/sessions/' + row['session'] + '/budget')
            row['accounting'] = validate_accounting(row['budget_usage'], audit)
            if row['trial'] in self.terminal.unknown:
                raise ExecutionOutcomeUnknown(self.terminal.unknown[row['trial']])
            row['audit_finish'] = await self.call(self.gateway.call, 'finish')
            row['status'] = 'ready_for_verifier'
            return row
        except BaseException as error:
            row['status'] = failure_status(error) if isinstance(error, Exception) else 'interrupted'
            row['error'] = type(error).__name__ + ': ' + str(error)
            self.checkpoint()
            await self.bridge.close()
            if row.get('session') and row.get('turn'):
                try:
                    row['cancellation'] = await self.call(self.client.cancel, row['session'], row['turn'])
                except Exception as cancel_error:
                    row['cancellation_error'] = type(cancel_error).__name__ + ': ' + str(cancel_error)
            # Do not finish the audit, release the lease or run the verifier here.
            # A missing turn receipt can still represent an admitted remote turn.
            raise
        finally:
            row['elapsed_seconds'] = time.time() - row['started']
            self.checkpoint()
