# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""SkillsBench setup and reference acceptance using the pinned BenchFlow SDK.

No model calls occur in reference acceptance. The model terminal is restricted
to the SDK's non-root agent user; verifier and oracle paths stay root-owned.
"""
import argparse
import asyncio
import json
from pathlib import Path
import shlex
import time

from sandbox_eval.benchflow_ack import BenchflowACK
from sandbox_eval.skillsbench_inventory import task_digest


def save(path, value):
    path = Path(path)
    temporary = path.with_suffix('.writing')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str))
    temporary.replace(path)


class AgentSandbox:
    """Only the terminal bridge uses this view; it cannot request root exec."""
    def __init__(self, environment, workspace):
        self.environment, self.workspace = environment, workspace

    async def exec(self, command=None, **kwargs):
        if kwargs.get('user') not in (None, 'agent'):
            raise ValueError('Model tools must run as the non-root agent')
        kwargs['user'] = 'agent'
        kwargs.setdefault('cwd', self.workspace)
        return await self.environment.exec(command, **kwargs)

    async def upload_file(self, source_path, target_path):
        # These are controller-authored bridge helpers/RPC files, not arbitrary
        # model paths. Keep model commands out of the transport command line.
        path = Path(target_path)
        if path.parent != Path('/tmp/managed-eval-terminal'):
            raise ValueError('Agent bridge uploads must stay in the terminal directory')
        await self.environment.upload_file(source_path, target_path)
        result = await self.environment.exec('chown agent:agent -- ' + shlex.quote(str(path)))
        if result.return_code:
            raise RuntimeError('Cannot assign bridge file to the agent user')


async def quiesce_agent(env, output):
    """Implement the SDK's process fence even in images lacking procps."""
    script = '''import json,os,pathlib,pwd,signal,time
uid=pwd.getpwnam('agent').pw_uid
assert uid!=0
def active():
 rows=[]
 for p in pathlib.Path('/proc').iterdir():
  if not p.name.isdigit():continue
  try:
   fields=dict(x.split(':',1) for x in (p/'status').read_text().splitlines() if ':' in x)
   if int(fields['Uid'].split()[0])==uid and fields['State'].strip()[0]!='Z':rows.append(int(p.name))
  except (FileNotFoundError,ProcessLookupError):continue
 return sorted(rows)
before=active()
for sig in (signal.SIGTERM,signal.SIGKILL):
 for pid in active():
  try:os.kill(pid,sig)
  except ProcessLookupError:pass
 time.sleep(1)
after=active()
print(json.dumps({'uid':uid,'before_pids':before,'active_after':after}))
assert not after,'Agent processes survived verifier fence'
'''
    result = await env.exec('python3 -c ' + shlex.quote(script), user='root', timeout_sec=15)
    save(output / 'agent-process-fence.json', {'return_code': result.return_code,
         'stdout': result.stdout, 'stderr': result.stderr, 'at': time.time()})
    if result.return_code:
        raise RuntimeError('Agent process quiescence not confirmed; verifier refused')


async def prepare_task(env, task, output, *, oracle=False):
    from benchflow.contracts import default_rollout_planes
    from benchflow.rollout._setup import _read_task_instruction, _resolve_agent_cwd
    planes = default_rollout_planes()
    workspace = await _resolve_agent_cwd(env, task)
    workspace = await planes.setup_sandbox_user(env, 'agent', workspace=workspace)
    # The same working directory applies to official oracle and verifier calls.
    env.task_env_config = env.task_env_config.model_copy(update={'workdir': workspace})
    await planes.snapshot_build_config(env, workspace=workspace)
    await planes.seed_verifier_workspace(env, workspace=workspace, sandbox_user='agent')
    skills = task.paths.task_dir / 'environment' / 'skills'
    await planes.deploy_skills(env, task.paths.task_dir, skills, None, 'agent', workspace,
                               skills_sandbox_dir='/skills')
    instruction = output / 'instruction.txt'
    instruction.write_text(_read_task_instruction(task.paths.task_dir))
    await env.upload_file(instruction, '/instruction.md')
    result = await env.exec('chmod 644 /instruction.md && chmod -R a+rX /skills')
    if result.return_code:
        raise RuntimeError('Cannot expose task instruction and bundled skills')
    if oracle:
        await env.upload_dir(task.paths.solution_dir, '/oracle')
    locked = planes.resolve_locked_paths('agent', None)
    await planes.lockdown_paths(env, locked)
    result = await env.exec('mkdir -p /logs/agent /logs/verifier')
    if result.return_code:
        raise RuntimeError('Cannot prepare official log directories')
    probe = await env.exec('id -u; test -r /instruction.md && test -d /skills && '
                          'test ! -r /oracle/solve.sh && test ! -r /verifier/test.sh',
                          user='agent', cwd=workspace)
    if probe.return_code or probe.stdout.strip() == '0':
        raise RuntimeError('Non-root task/skill access or answer isolation failed')
    save(output / 'setup.json', {'workspace': workspace, 'user': 'agent',
         'skills_root': '/skills', 'locked': locked, 'oracle_uploaded': oracle,
         'isolation_probe': probe.stdout.strip(), 'at': time.time()})
    return workspace, planes


async def reference(args):
    from benchflow.task import RolloutPaths, Task
    from benchflow.rollout._setup import _run_oracle, _verify_rollout
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    actual, files = task_digest(args.task)
    if actual != args.digest:
        raise ValueError('Official task content changed before reference acceptance')
    task = Task(Path(args.task))
    with (output / 'reference-intent.json').open('x') as stream:
        json.dump({'task_digest': actual, 'image': args.image, 'files': files,
                   'at': time.time(), 'model_calls': 0}, stream)
    env = BenchflowACK(pod_name=args.pod, namespace=args.namespace, image=args.image,
                      environment=task.config.environment, receipt_dir=output / 'pod')
    row = {'complete': False, 'reward': None, 'model_calls': 0, 'started': time.time()}
    timing = {}
    try:
        await env.start()
        workspace, planes = await prepare_task(env, task, output, oracle=True)
        trajectory, _ = await _run_oracle(env, Path(args.task),
            int(task.config.solution.timeout_sec or task.config.agent.timeout_sec))
        save(output / 'oracle.json', trajectory)
        paths = RolloutPaths(rollout_dir=output / 'benchflow')
        await quiesce_agent(env, output)
        rewards, error, diagnostic = await _verify_rollout(
            env, task, paths, timing, planes, 'agent', workspace)
        row.update(rewards=rewards, verifier_error=error, verifier_timeout=diagnostic,
                   timing=timing)
        if error or not rewards or set(rewards) != {'reward'}:
            raise RuntimeError('Official reference verification incomplete')
        row['reward'] = rewards['reward']
        if any(item.get('return_code') != 0 for item in trajectory) or row['reward'] != 1:
            raise RuntimeError('Official reference did not pass; model admission refused')
        await env.download_dir('/logs', output / 'logs')
        row['complete'] = True
        await env.stop()
    except BaseException as error:
        row['error'] = type(error).__name__ + ': ' + str(error)
        raise
    finally:
        row['finished'] = time.time()
        save(output / 'reference-result.json', row)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('task', 'digest', 'image', 'pod', 'namespace', 'output'):
        parser.add_argument('--' + option, required=True)
    asyncio.run(reference(parser.parse_args()))


if __name__ == '__main__':
    main()
