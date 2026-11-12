# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Export a model patch, then apply it to a different, clean SWE grading Pod.

This module does not load reference solutions or change official test assertions.
It does not score a failed patch application as an invented official zero.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import uuid

from sandbox_eval.exec_transport import ExecutionOutcomeUnknown
from sandbox_eval.artifact_transfer import download_snapshot


def require_commit(value):
    if not re.fullmatch('[0-9a-f]{40}', value):
        raise ValueError('Expected the pinned full base commit')


async def prepare_repository(environment, repository, base_commit, setup_commit=None):
    """Validate a fresh image's setup commit before model admission.

    SWE instance images can add a setup commit chmod-ing every tracked file.
    Accept only identical paths/blob contents, a clean worktree, and an ancestor
    base. Preserve this evidence before reset; never use on an attempted trial.
    An explicitly pinned content-changing setup is instead kept in source and
    grader; prediction export must use that setup commit as its baseline.
    """
    require_commit(base_commit)
    if setup_commit is not None:
        require_commit(setup_commit)
    script = '''import subprocess,json
base = BASE
setup = SETUP
def git(*args): return subprocess.check_output(['git']+list(args)).decode().strip()
assert not git('status','--porcelain'), 'fresh repository must be clean'
head=git('rev-parse','HEAD')
subprocess.check_call(['git','merge-base','--is-ancestor',base,head])
def tree(ref):
 entries={}
 for record in subprocess.check_output(['git','ls-tree','-rz',ref]).split(b'\\0'):
  if record:
   metadata,path=record.split(b'\\t',1)
   mode,kind,blob=metadata.split()
   entries[path]=(mode,kind,blob)
 return entries
before,expected=tree(head),tree(base)
if setup is not None:
    # Explicitly pinned upstream image setup (e.g. Astropy's setuptools pin).
    # Keep it in both source and grader; prediction diffs use this same HEAD.
    assert head==setup, 'image setup commit differs from the accepted manifest'
    print(json.dumps({'image_head':head,'base_commit':base,'setup_commit':setup,
                      'same_tracked_contents':before==expected,'normalized_modes':0,
                      'preserved_upstream_setup':True}))
    raise SystemExit(0)
assert set(before)==set(expected), 'image setup changed tracked paths'
assert all(before[p][1:]==expected[p][1:] for p in before), 'image setup changed tracked contents'
receipt={'image_head':head,'base_commit':base,'same_tracked_contents':True,
         'normalized_modes':sum(before[p][0]!=expected[p][0] for p in before)}
subprocess.check_output(['git','reset','--hard',base])
assert git('rev-parse','HEAD')==base and not git('status','--porcelain')
print(json.dumps(receipt))
'''.replace('BASE', repr(base_commit)).replace('SETUP', repr(setup_commit))
    result = await environment.exec('python3 -c ' + shlex.quote(script), cwd=repository, timeout_sec=60)
    if result.return_code != 0:
        raise ExecutionOutcomeUnknown('SWE repository preparation failed; inspect the fresh Pod')
    try:
        receipt = json.loads(result.stdout)
        if receipt['base_commit'] != base_commit or (
                setup_commit is None and receipt['same_tracked_contents'] is not True) or (
                setup_commit is not None and (receipt.get('setup_commit') != setup_commit or
                                              receipt.get('preserved_upstream_setup') is not True)):
            raise ValueError('Invalid repository preparation receipt')
        return receipt
    except (ValueError, KeyError, TypeError) as error:
        raise ExecutionOutcomeUnknown('SWE repository preparation has no verified receipt') from error


async def export_patch(environment, repository, base_commit, output):
    require_commit(base_commit)
    path = Path(output)
    if path.exists():
        raise FileExistsError('Preserve the original patch export')
    path.parent.mkdir(parents=True, exist_ok=True)
    snapshot = '/tmp/managed-swe-patch-' + uuid.uuid4().hex
    # Persist intent before creating a snapshot. An unknown export must be
    # inspected, never silently repeated against a potentially changed tree.
    with path.with_suffix(path.suffix + '.intent.json').open('x') as stream:
        json.dump({'source_pod': environment.pod_name, 'remote_path': snapshot,
                   'base_commit': base_commit}, stream)
    # A private temporary index includes newly created files without changing
    # the agent's index or depending on the agent's current HEAD/commit count.
    index = '/tmp/managed-swe-index-' + uuid.uuid4().hex
    command = ('set -euo pipefail; export GIT_INDEX_FILE=' + shlex.quote(index) + '; '
               'trap \'rm -f -- "$GIT_INDEX_FILE"\' EXIT; '
               'git read-tree ' + base_commit + '; git add -A -- .; '
               'git diff --cached --binary --full-index ' + base_commit + ' -- . > ' +
               shlex.quote(snapshot))
    result = await environment.exec(command, cwd=repository, timeout_sec=120)
    if result.return_code != 0:
        raise ExecutionOutcomeUnknown('SWE patch export did not complete')
    partial = path.with_suffix(path.suffix + '.partial')
    metadata = await download_snapshot(environment, snapshot, partial)
    # link is atomic and refuses to replace an existing accepted export.
    os.link(partial, path)
    partial.unlink()
    return {'base_commit': base_commit, 'sha256': metadata['sha256'],
            'bytes': metadata['bytes'], 'empty': not metadata['bytes'],
            'source_pod': environment.pod_name, 'remote_path': snapshot, 'chunk_bytes': 8192}


async def apply_to_clean_grader(environment, repository, patch_path, receipt):
    require_commit(receipt['base_commit'])
    if environment.pod_name == receipt['source_pod']:
        raise ValueError('SWE grading requires a separate clean task Pod')
    payload = Path(patch_path).read_bytes()
    if hashlib.sha256(payload).hexdigest() != receipt['sha256']:
        raise ValueError('Prediction patch changed after export')
    check = ('import subprocess,json;print(json.dumps({'
             '"head":subprocess.check_output(["git","rev-parse","HEAD"]).decode().strip(),'
             '"status":subprocess.check_output(["git","status","--porcelain"]).decode()}))')
    result = await environment.exec('python3 -c ' + shlex.quote(check), cwd=repository, timeout_sec=30)
    if result.return_code != 0 or json.loads(result.stdout) != {'head': receipt['base_commit'], 'status': ''}:
        raise ValueError('Grader must start at the exact clean base commit')
    if not payload:
        return {'applied': True, 'empty': True, 'sha256': receipt['sha256']}
    remote = '/tmp/managed-swe-prediction-' + uuid.uuid4().hex + '.patch'
    await environment.upload_file(patch_path, remote)
    result = await environment.exec('git apply --check ' + shlex.quote(remote) +
                                    ' && git apply --whitespace=nowarn ' + shlex.quote(remote),
                                    cwd=repository, timeout_sec=60)
    if result.return_code != 0:
        raise ExecutionOutcomeUnknown('Prediction did not apply to the clean grader; preserve evidence')
    return {'applied': True, 'empty': False, 'sha256': receipt['sha256']}
