# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""One-pass, with-skills SkillsBench execution through deployed native Harnesses.

The immutable preparation roster is not sufficient to launch this runner.
Every selected task also needs a digest-bound image and successful official
reference receipt. Never retry an admitted model trial on controller restart.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import time
import uuid

from benchflow.rollout._setup import _read_task_instruction, _verify_rollout
from benchflow.skills import parse_skill
from benchflow.task import RolloutPaths, Task
import uvicorn

from evaluation import EnvironmentLease, check_preflight, digest
from session_client import SessionClient
from sandbox_eval.benchflow_ack import BenchflowACK
from sandbox_eval.session_driver import SessionDriver
from sandbox_eval.skillsbench_inventory import task_digest
from sandbox_eval.skillsbench_runtime import AgentSandbox, prepare_task, quiesce_agent, save
from sandbox_eval.terminal_bridge import TerminalBridge
from sandbox_eval.terminal_mcp import TerminalMCP


def skill_instruction(task_root):
    root = Path(task_root)
    skills = root / 'environment' / 'skills'
    catalog = []
    for path in sorted(skills.rglob('SKILL.md')):
        info = parse_skill(path)
        if info is None:
            raise ValueError('Unreadable bundled skill metadata: ' + str(path))
        catalog.append({'name': info.name, 'description': info.description,
                        'path': '/skills/' + path.relative_to(skills).as_posix()})
    if not catalog:
        raise ValueError('With-skills trial requires a nonempty bundled catalog')
    instruction = _read_task_instruction(root) + (
        '\n\nAvailable task-provided skills are listed below. Read the relevant SKILL.md '
        'using the terminal tools and follow its instructions when applicable. '
        'Bundled scripts, references and assets are available beside each SKILL.md.\n'
    ) + json.dumps(catalog, ensure_ascii=False, indent=2)
    return instruction, catalog


def validate_entry(entry, root):
    if entry.get('skill_mode') != 'with-skill' or entry.get('repeat') != 0:
        raise ValueError('Only the authorized single with-skills attempt is supported')
    if Path(entry['task']).name != entry['task']:
        raise ValueError('Invalid task path')
    actual, files = task_digest(Path(root) / entry['task'])
    if actual != entry['digest'] or files != entry['files']:
        raise ValueError('Task differs from the verified release')
    receipt = json.loads(Path(entry['reference_receipt']).read_text())
    intent = json.loads(Path(entry['reference_receipt']).with_name('reference-intent.json').read_text())
    if (receipt.get('complete') is not True or receipt.get('reward') != 1 or
            receipt.get('model_calls') != 0 or intent.get('image') != entry['image'] or
            intent.get('task_digest') != actual or digest(receipt) != entry['reference_receipt_hash']):
        raise ValueError('Missing or mismatched official reference acceptance')


def claim_slot(root, target, entry, trial):
    if target not in {'agentscope', 'pi', 'codex'} or Path(entry['task']).name != entry['task']:
        raise ValueError('Invalid canonical slot')
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    # Shared across batch/suffix controller output directories. A new output
    # path never grants permission to repeat the same authorized model slot.
    with (root / (target + '--' + entry['task'] + '.json')).open('x') as stream:
        json.dump({'target': target, 'task': entry['task'], 'trial': trial,
                   'digest': entry['digest'], 'skill_mode': 'with-skill',
                   'at': time.time(), 'phase': 'preparing'}, stream)


async def run(args):
    os.umask(0o077)
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    config, model, evaluation, plan = [json.loads(Path(p).read_text()) for p in
        (args.config, args.model_profile, args.evaluation_profile, args.plan)]
    if plan.get('dataset') != 'skillsbench@1.1' or plan.get('scope_total') != 261:
        raise ValueError('Expected the authorized 261-slot SkillsBench plan')
    entries = plan['tasks']
    if not entries or len({e['task'] for e in entries}) != len(entries):
        raise ValueError('Unique nonempty task list required')
    for entry in entries:
        validate_entry(entry, args.task_root)
    check_preflight(args.preflight, config, model, evaluation)
    if len(config['targets']) != 1 or config['targets'][0]['name'] != args.target:
        raise ValueError('Exactly one target per controller')
    target = config['targets'][0]
    bindings = model['brain']['model']['bindings']
    if len(bindings) != 1:
        raise ValueError('One frozen common model required')
    with (output / 'launch-intent.json').open('x') as stream:
        json.dump({'at': time.time(), 'target': args.target, 'plan_sha256': digest(plan)}, stream)
    terminal = TerminalMCP(config['tool_key'])
    server = uvicorn.Server(uvicorn.Config(terminal, host='0.0.0.0', port=args.mcp_port,
                           log_level='warning', access_log=False))
    server_task = asyncio.create_task(server.serve())
    completed = False
    try:
        while not server.started:
            if server_task.done():
                await server_task
                raise RuntimeError('Terminal MCP startup failed')
            await asyncio.sleep(.05)
        client = SessionClient(target['cluster_url'], target['headers'])
        environments = await asyncio.to_thread(client.api, '/api/environments')
        environment_id = next(e['id'] for e in environments if e['name'] == args.environment_name)
        for entry in entries:
            identity = args.target + '-sb-' + entry['task'][:30] + '-' + uuid.uuid4().hex[:8]
            claim_slot(plan['slot_claim_root'], args.target, entry, identity)
            directory = output / identity
            directory.mkdir()
            row = {'task': entry['task'], 'trial': identity, 'target': args.target,
                   'skill_mode': 'with-skill', 'repeat': 0, 'status': 'preparing',
                   'reward': None, 'started': time.time()}
            save(directory / 'result.json', row)
            lease = await asyncio.to_thread(EnvironmentLease, config, 'terminal', directory)
            task = Task(Path(args.task_root) / entry['task'])
            env = BenchflowACK(pod_name=identity, namespace=args.task_namespace,
                image=entry['image'], environment=task.config.environment, receipt_dir=directory / 'pod')
            driver = None
            accepted_reward = None
            try:
                await env.start()
                workspace, planes = await prepare_task(env, task, directory)
                instruction, catalog = skill_instruction(task.paths.task_dir)
                initial = {'image': entry['image'], 'files': entry['files'],
                    'task_digest': entry['digest'], 'skill_catalog': catalog,
                    'skill_discovery': 'common metadata catalog; files loaded through terminal',
                    'environment': task.config.environment.model_dump(mode='json', by_alias=True),
                    'workspace': workspace, 'user': 'agent'}
                save(directory / 'initial.json', initial)
                budget = dict(evaluation['benchmarks']['terminal'])
                budget['caseTimeoutSeconds'] = int(task.config.agent.timeout_sec)
                budget['turnTimeoutSeconds'] = budget['caseTimeoutSeconds']
                bridge = TerminalBridge(AgentSandbox(env, workspace), directory / 'terminal-journal')
                await bridge.setup()
                driver = SessionDriver(config, target, next(iter(bindings)), budget,
                    evaluation['nativeIterationGuard'], lease, terminal, bridge, directory / 'session',
                    identity, entry['task'], digest(initial), entry['revision'], environment_id, client,
                    comparison_namespace=plan['comparison_namespace'], benchmark='skillsbench')
                try:
                    await driver.run(instruction)
                except Exception:
                    if not driver.gateway or not driver.row.get('turn'):
                        raise
                    await driver.admit_terminal_artifact()
                if driver.row['status'] not in {'ready_for_verifier', 'ready_for_artifact_verifier'}:
                    raise RuntimeError('Model/tool termination evidence incomplete')
                row.update(driver.row, status='verifying')
                save(directory / 'result.json', row)
                # Freeze bridge evidence before the SDK kills remaining agent processes.
                await env.download_dir(bridge.root, directory / 'terminal-process-artifacts')
                await quiesce_agent(env, directory)
                timing = {}
                rewards, error, diagnostic = await _verify_rollout(env, task,
                    RolloutPaths(rollout_dir=directory / 'benchflow'), timing, planes, 'agent', workspace)
                save(directory / 'official-verifier.json', {'rewards': rewards,
                    'error': error, 'diagnostic': diagnostic, 'timing': timing})
                if error or not rewards or set(rewards) != {'reward'}:
                    raise RuntimeError('Official verifier has no unambiguous valid score')
                accepted_reward = rewards['reward']
                row.update(reward=rewards['reward'], status='completed',
                    end_to_end_success=rewards['reward'] == 1 and not row.get('execution_failure'))
                await env.download_dir('/logs', directory / 'logs')
                await asyncio.to_thread(lease.release)
                await env.stop()
            except BaseException as error:
                if driver:
                    row.update(driver.row)
                row.update(status='requires_inspection', error=type(error).__name__ + ': ' + str(error),
                           reward=accepted_reward, task_pod=env.pod_name,
                           task_namespace=args.task_namespace)
                raise
            finally:
                row['elapsed_seconds'] = time.time() - row['started']
                save(directory / 'result.json', row)
                with (output / 'results.jsonl').open('a') as stream:
                    stream.write(json.dumps(row, ensure_ascii=False, default=str) + '\n')
        completed = True
    finally:
        save(output / 'exit.json', {'completed': completed, 'at': time.time()})
        server.should_exit = True
        await server_task


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ('config', 'model-profile', 'evaluation-profile', 'preflight', 'plan',
                'task-root', 'target', 'task-namespace', 'output', 'environment-name'):
        parser.add_argument('--' + key, required=True)
    parser.add_argument('--mcp-port', type=int, required=True)
    asyncio.run(run(parser.parse_args()))


if __name__ == '__main__':
    main()
