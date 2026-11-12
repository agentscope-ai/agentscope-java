# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Run an immutable list of Harbor Terminal tasks through one deployed Harness.

No automatic model retries or reference solution in a model trial. Task tests
are admitted after normal accounting checks or a separately evidenced terminal
artifact fence. Preserve execution failures and unknown usage independently.
"""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path
import time
import uuid

from harbor.models.agent.context import AgentContext
from harbor.models.task.task import Task
from harbor.models.trial.paths import TrialPaths
from harbor.verifier.verifier import Verifier
import uvicorn

from evaluation import EnvironmentLease, check_preflight, digest
from session_client import SessionClient
from sandbox_eval.harbor_agent import ManagedSessionAgent
from sandbox_eval.harbor_environment import ManagedACKEnvironment
from sandbox_eval.grading import require_pytest_execution, require_swe_execution
from sandbox_eval.environment_config import (comparable_runtime, effective_environment,
                                             resource_overrides, verifier_environment)
from sandbox_eval.swe_patch import apply_to_clean_grader, export_patch, prepare_repository, require_commit
from sandbox_eval.preparation import prepare_network, prepare_runtime
from sandbox_eval.session_driver import SessionDriver
from sandbox_eval.terminal_bridge import TerminalBridge
from sandbox_eval.terminal_mcp import TerminalMCP


def save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.writing')
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False, default=str))
    temporary.replace(path)


def validate_plan(plan, task_root, expected_dataset='terminal-bench-2.1'):
    if plan.get('version') != 1 or plan.get('dataset') != expected_dataset or expected_dataset not in {
            'terminal-bench-2.1', 'swe-bench-verified'}:
        raise ValueError('Unsupported Terminal plan')
    entries = plan['tasks']
    ids = [item['task'] for item in entries]
    if not ids or len(ids) != len(set(ids)):
        raise ValueError('Task plan must be nonempty and unique')
    for item in entries:
        scorer = 'swe-report' if expected_dataset == 'swe-bench-verified' else 'pytest-ctrf'
        if item.get('scorer_evidence') != scorer:
            raise ValueError('The task must declare the required official scorer evidence')
        if scorer == 'swe-report':
            require_commit(item['base_commit'])
            if item.get('setup_commit') is not None:
                require_commit(item['setup_commit'])
            if item.get('repository') != '/testbed':
                raise ValueError('Only the accepted SWE /testbed image layout is supported')
        if Path(item['task']).name != item['task']:
            raise ValueError('Task must be a single directory name')
        root = Path(task_root) / item['task']
        # Exact files verified from the upstream package before model admission.
        actual = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in sorted(root.rglob('*')) if p.is_file()}
        if actual != item['files']:
            raise ValueError('Task files differ from the accepted manifest: ' + item['task'])
        if item['oracle_acceptance'].get('reward') != 1:
            raise ValueError('Official reference-solution acceptance is required first')
        verifier_environment(plan, item)
        resource_overrides(item)
    return entries


async def run(args):
    os.umask(0o077)
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    config = json.loads(Path(args.config).read_text())
    model = json.loads(Path(args.model_profile).read_text())
    evaluation = json.loads(Path(args.evaluation_profile).read_text())
    plan = json.loads(Path(args.plan).read_text())
    entries = validate_plan(plan, args.task_root, args.dataset)
    is_swe = args.dataset == 'swe-bench-verified'
    check_preflight(args.preflight, config, model, evaluation)
    if len(config['targets']) != 1 or config['targets'][0]['name'] != args.target:
        raise ValueError('Exactly one matching target per isolated controller')
    target = config['targets'][0]
    bindings = model['brain']['model']['bindings']
    if len(bindings) != 1:
        raise ValueError('Exactly one model binding is required')
    model_name = next(iter(bindings))
    with (output / 'launch-intent.json').open('x') as stream:
        json.dump({'at': time.time(), 'plan_sha256': digest(plan), 'target': args.target}, stream)
    save(output / 'manifest.json', {'plan': plan, 'model_profile_sha256': digest(model),
                                   'evaluation_profile_sha256': digest(evaluation),
                                   'target': args.target, 'repeat': 0})
    terminal = TerminalMCP(config['tool_key'])
    server = uvicorn.Server(uvicorn.Config(terminal, host='0.0.0.0', port=args.mcp_port,
                                           log_level='warning', access_log=False))
    server_task = asyncio.create_task(server.serve())
    while not server.started:
        if server_task.done():
            await server_task
            raise RuntimeError('Terminal MCP failed to start')
        await asyncio.sleep(.05)
    passed = False
    try:
        client = SessionClient(target['cluster_url'], target['headers'])
        environments = await asyncio.to_thread(client.api, '/api/environments')
        environment_id = next(e['id'] for e in environments if e['name'] == 'general-agent-eval-20261030')
        for entry in entries:
            task_id = entry['task']
            identity = args.target + '-' + task_id + '-' + uuid.uuid4().hex[:8]
            directory = output / identity
            directory.mkdir()
            row = {'task': task_id, 'trial': identity, 'target': args.target, 'repeat': 0,
                   'reward': None, 'status': 'preparing', 'started': time.time()}
            save(directory / 'result.json', row)
            lease = await asyncio.to_thread(EnvironmentLease, config, 'terminal', directory)
            task = Task(Path(args.task_root) / task_id)
            paths = TrialPaths(directory / 'harbor')
            paths.trial_dir.mkdir(parents=True)
            env = ManagedACKEnvironment(environment_dir=task.paths.environment_dir,
                environment_name=task_id, session_id=identity, trial_paths=paths,
                task_env_config=effective_environment(task.config.environment, entry, is_swe),
                pod_overrides={'spec': {'hostAliases': entry.get('host_aliases', [])}},
                namespace=args.task_namespace, default_exec_timeout_sec=task.config.verifier.timeout_sec)
            driver = None
            accepted_reward = None
            grading_env = None
            try:
                await env.start()
                runtime = await prepare_runtime(env, entry.get('runtime_profile'))
                save(directory / 'runtime-preparation.json', runtime)
                prepared = await prepare_network(env, entry.get('network_profile'))
                save(directory / 'network-preparation.json', prepared)
                budget = dict(evaluation['benchmarks']['swe' if is_swe else 'terminal'])
                # Honor the task's declared agent timeout, identically for all Harnesses.
                budget['caseTimeoutSeconds'] = int(task.config.agent.timeout_sec)
                budget['turnTimeoutSeconds'] = budget['caseTimeoutSeconds']
                initial = {'image': entry['image'], 'task_files': entry['files'],
                           'environment': task.config.environment.model_dump(mode='json'),
                           'effective_environment': env.task_env_config.model_dump(mode='json'),
                           'effective_workdir': env.task_env_config.workdir,
                           'effective_verifier_env': verifier_environment(plan, entry),
                           'host_aliases': entry.get('host_aliases', []),
                           'network_preparation': prepared, 'runtime_preparation': comparable_runtime(runtime)}
                if is_swe:
                    initial['repository_preparation'] = await prepare_repository(
                        env, entry['repository'], entry['base_commit'], entry.get('setup_commit'))
                    initial['base_commit'] = entry['base_commit']
                save(directory / 'initial.json', initial)
                bridge = TerminalBridge(env, directory / 'terminal-journal')
                driver = SessionDriver(config, target, model_name,
                    budget, evaluation['nativeIterationGuard'], lease, terminal, bridge, directory / 'session',
                    identity, task_id, digest(initial), plan['revision'], environment_id, client,
                    comparison_namespace=plan.get('comparison_namespace'))
                agent = ManagedSessionAgent(logs_dir=paths.agent_dir, driver=driver)
                await agent.setup(env)
                context = AgentContext()
                try:
                    await agent.run(task.instruction, env, context)
                except Exception:
                    # The original execution failure is retained separately.
                    # Grade only after exact terminal, audited I/O and process
                    # quiescence checks; this path never resumes the agent.
                    if not driver.gateway or not driver.row.get('session') or not driver.row.get('turn'):
                        raise
                    await driver.admit_terminal_artifact()
                if driver.row['status'] not in {'ready_for_verifier', 'ready_for_artifact_verifier'}:
                    raise RuntimeError('Session did not satisfy verifier admission')
                row.update(driver.row, status='verifying')
                save(directory / 'result.json', row)
                verifier_paths, verifier_env = paths, env
                if is_swe:
                    prediction = directory / 'prediction.patch'
                    exported = await export_patch(env, entry['repository'],
                                                  entry.get('setup_commit') or entry['base_commit'], prediction)
                    save(directory / 'patch-export.json', exported)
                    verifier_paths = TrialPaths(directory / 'clean-grader')
                    verifier_paths.trial_dir.mkdir(parents=True)
                    grading_env = ManagedACKEnvironment(
                        environment_dir=task.paths.environment_dir, environment_name=task_id,
                        session_id=identity + '-grader', trial_paths=verifier_paths,
                        task_env_config=effective_environment(task.config.environment, entry, True),
                        pod_overrides={'spec': {'hostAliases': entry.get('host_aliases', [])}},
                        namespace=args.task_namespace, default_exec_timeout_sec=task.config.verifier.timeout_sec)
                    save(directory / 'grader-intent.json', {'source_pod': env.pod_name,
                                                           'grading_pod': grading_env.pod_name})
                    await grading_env.start()
                    save(directory / 'grader-runtime-preparation.json',
                         await prepare_runtime(grading_env, entry.get('runtime_profile')))
                    save(directory / 'grader-network-preparation.json',
                         await prepare_network(grading_env, entry.get('network_profile')))
                    save(directory / 'grader-repository-preparation.json',
                         await prepare_repository(grading_env, entry['repository'], entry['base_commit'],
                                                  entry.get('setup_commit')))
                    applied = await apply_to_clean_grader(grading_env, entry['repository'], prediction, exported)
                    save(directory / 'patch-application.json', applied)
                    verifier_env = grading_env
                # Harbor Trial normally supplies this outer timeout. Explicitly
                # preserve it when embedding its official Verifier here.
                async with asyncio.timeout(task.config.verifier.timeout_sec):
                    verified = await Verifier(task, verifier_paths, verifier_env,
                                             verifier_env=verifier_environment(plan, entry)).verify()
                save(directory / 'official-verifier.json', verified.model_dump(mode='json'))
                if not verified.rewards or set(verified.rewards) != {'reward'}:
                    raise ValueError('No unambiguous official reward')
                if entry.get('scorer_evidence') == 'pytest-ctrf':
                    row['scorer_execution'] = require_pytest_execution(
                        verifier_paths.verifier_dir, verified.rewards['reward'])
                elif is_swe:
                    row['scorer_execution'] = require_swe_execution(
                        verifier_paths.verifier_dir, task_id, verified.rewards['reward'])
                accepted_reward = verified.rewards['reward']
                row.update(status=('scored_after_execution_failure' if row.get('execution_failure')
                                   else 'completed'), reward=accepted_reward,
                           end_to_end_success=accepted_reward == 1 and not row.get('execution_failure'))
                # Preserve process receipts (including intentionally running daemons)
                # before deleting a successfully graded Pod. Tools remain fenced.
                await env.download_dir(bridge.root, directory / 'terminal-process-artifacts')
                await asyncio.to_thread(lease.release)
                save(directory / 'result.json', row)
                await env.stop(delete=True)
                if grading_env is not None:
                    await grading_env.stop(delete=True)
            except BaseException as error:
                if driver:
                    row.update(driver.row)
                status = (driver.row['status'] if driver and driver.row['status'] not in
                          {'prepared', 'running', 'execution_completed', 'ready_for_verifier'}
                          else 'infrastructure_error')
                row.update(status=status, error=type(error).__name__ + ': ' + str(error),
                           reward=accepted_reward,
                           task_pod=env.pod_name, task_namespace=args.task_namespace)
                if grading_env is not None:
                    row['grading_pod'] = grading_env.pod_name
                # Preserve any already obtained official score, but do not turn
                # an absent score into zero. Never continue across an uncertain slot.
                if (directory / 'official-verifier.json').exists():
                    row['raw_verifier_reward'] = json.loads(
                        (directory / 'official-verifier.json').read_text())['rewards']['reward']
                raise
            finally:
                row['elapsed_seconds'] = time.time() - row['started']
                save(directory / 'result.json', row)
                with (output / 'results.jsonl').open('a') as stream:
                    stream.write(json.dumps(row, ensure_ascii=False, default=str) + '\n')
        passed = True
    finally:
        save(output / 'exit.json', {'completed': passed, 'at': time.time()})
        server.should_exit = True
        await server_task


def main(dataset='terminal-bench-2.1'):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['config', 'model-profile', 'evaluation-profile', 'preflight', 'plan',
                 'task-root', 'task-namespace', 'output', 'target']:
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--mcp-port', type=int, required=True)
    args = parser.parse_args()
    args.dataset = dataset
    asyncio.run(run(args))


if __name__ == '__main__':
    main()
