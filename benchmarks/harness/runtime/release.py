# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Frozen evaluation identities and conservative, offline release decisions."""
from collections import defaultdict
import hashlib
import html
import json
from pathlib import Path
import re

from reports.unified import summarize
from runtime.scenarios import unsupported
from runtime.datasets import resolve_task, task_files, validate_selection
from runtime.external_datasets import identity


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def validate_inputs(root, config, definitions_only=False):
    """Validate task and adapter contracts without infrastructure or model access."""
    root = Path(root)
    names = [a['name'] for a in config['agents']]
    if not names or len(names) != len(set(names)) or set(names) - {'agentscope', 'codex', 'pi'}:
        raise ValueError('Unknown or duplicate native agent')
    if not config['tasks'] or len(set(config['tasks'])) != len(config['tasks']):
        raise ValueError('Tasks must be nonempty and unique')
    if not 1 <= config['rollouts'] <= 10 or not 1 <= config['concurrency'] <= 8 or config['retry_attempts'] != 0:
        raise ValueError('Invalid rollouts, concurrency or automatic retries')
    if min(config['trial_timeout_seconds'], config['job_timeout_seconds'], config['max_turns']) <= 0:
        raise ValueError('Execution limits must be positive')
    if 'dataset_root' in config:
        raise ValueError('Legacy dataset_root is not supported; use canonical task IDs')
    source = (root / 'adapters/agentscope-java/src/main/java/io/agentscope/examples/eval/HarnessEvalCli.java').read_text()
    java_version = re.search(r'agentscope-harness-eval ([\d.]+)', source)[1]
    docker = (root / 'environments/Dockerfile.sandbox').read_text()
    versions = {'agentscope': java_version}
    for name in ('codex', 'pi'):
        versions[name] = re.search(r'ARG ' + name.upper() + r'_VERSION=([^\s]+)', docker)[1]
    for agent in config['agents']:
        if agent.get('api') != ('responses' if agent['name'] == 'codex' else 'chat_completions'):
            raise ValueError('Unsupported API profile for ' + agent['name'])
        if agent['version'] != versions[agent['name']]:
            raise ValueError('Adapter/image version mismatch: ' + agent['name'])
    cases = {}
    external = validate_selection(root, config)
    for name in config['tasks']:
        if definitions_only and name in external:
            entry = external[name]
            scenario = entry.get('scenario', {})
            cases[name] = {'category': scenario.get('category', entry['kind']),
                           'prepared': False, 'unsupported': {a: None for a in names}}
            continue
        folder = resolve_task(root, name)
        scenario = json.loads((folder / 'scenario.json').read_text()) if (folder / 'scenario.json').exists() else {}
        files = task_files(folder)
        cases[name] = {'category': scenario.get('category', 'smoke'), 'files': files,
                       'unsupported': {a: unsupported(a, scenario) for a in names}}
        if name in external:
            cases[name]['external_inputs'] = identity(root, name, external[name])
    release = config.get('release')
    if release:
        policy = release['policy']
        if policy['version'] != 'release-gate-v1' or policy['score_version'] != 'task-outcome-v1':
            raise ValueError('Unknown release scoring policy')
        if config['rollouts'] < policy['minimum_rollouts'] or policy['minimum_rollouts'] < 3:
            raise ValueError('Release regression requires at least three predeclared rollouts')
        if not 0 <= policy['max_pass_rate_drop'] <= 1:
            raise ValueError('Invalid release threshold')
        if config.get('selected_trials'):
            raise ValueError('A release must run the full frozen matrix')
        if not set(policy['critical_tasks']).issubset(cases):
            raise ValueError('Unknown critical task')
        if not release.get('preflight_tasks') or not set(release['preflight_tasks']).issubset(cases):
            raise ValueError('Release preflight must select existing core tasks')
        if any(reason for case in cases.values() for reason in case['unsupported'].values()):
            raise ValueError('Release core cannot contain unsupported profiles')
    return cases


def freeze_contract(root, config):
    cases = validate_inputs(root, config)
    root = Path(root)
    # Runtime, adapters and scorer changes require an explicit new baseline. Candidate
    # source, namespace, concurrency, run ID and provenance docs are not comparison inputs.
    implementation = {}
    for directory in ('runtime', 'adapters', 'schemas', 'contracts'):
        for path in sorted((root / directory).rglob('*')):
            if path.is_file() and not any(p in ('target', '__pycache__') for p in path.relative_to(root).parts):
                implementation[str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
    environment_files = ['environments/Dockerfile.sandbox'] + [
        'environments/Dockerfile.' + name for name in config['tasks'] if name.startswith('tb-')]
    for name in (*environment_files, 'reports/unified.py'):
        implementation[name] = hashlib.sha256((root / name).read_bytes()).hexdigest()
    identity = {key: config.get(key) for key in ('suite', 'model', 'model_base_url', 'model_snapshot_locked',
        'agents', 'rollouts', 'trial_timeout_seconds', 'max_turns', 'harbor', 'base_images')}
    identity.update(tasks=cases, implementation=implementation,
                    policy=config.get('release', {}).get('policy'), score_version=config.get('score_version', 'frozen-strict-v1'))
    return {'version': 'evaluation-contract-v1', 'sha256': digest(identity), 'identity': identity}


def valid_row(row):
    return (row.get('execution_status') == 'completed' and row.get('scoring_status') == 'scored'
            and type(row.get('score')) in (int, float) and row['score'] in (0, 1)
            and all(row.get(k) is True for k in ('native_completed', 'protocol_valid', 'archive_complete')))


def task_metrics(manifest, summary):
    rows = summarize(manifest, summary['results'])['results']
    grouped = defaultdict(list)
    for row in rows:
        grouped[(row['agent'], row['task'])].append(row)
    return {agent + '/' + task: {'agent': agent, 'task': task, 'planned': len(items),
        'valid': sum(valid_row(r) for r in items),
        'passed': sum(valid_row(r) and r['score'] == 1 for r in items),
        'mean': sum(r['score'] for r in items) / len(items) if all(valid_row(r) for r in items) else None,
        'all_rollouts_passed': all(valid_row(r) and r['score'] == 1 for r in items),
        'at_least_one_passed': any(valid_row(r) and r['score'] == 1 for r in items),
        'rollouts': [{k: r.get(k) for k in ('rollout', 'score', 'execution_status', 'scoring_status')} for r in items]}
        for (agent, task), items in sorted(grouped.items())}


def evaluate_release(manifest, summary, baseline=None):
    """Never reinterpret a task's reward, retry an answer or silently drop a row."""
    policy = manifest['release']['policy']
    summary = summarize(manifest, summary['results'])
    invalid, failures, review = [], [], []
    candidate = manifest.get('candidate', {})
    if candidate.get('mode') != 'git-ref' or candidate.get('modified_files'):
        invalid.append('Release candidate is not an immutable Git ref')
    if manifest['rollouts'] < policy['minimum_rollouts']:
        invalid.append('Insufficient predeclared rollouts')
    contract = manifest.get('evaluation_contract')
    if not contract or digest(contract.get('identity')) != contract.get('sha256'):
        invalid.append('Missing or invalid evaluation contract')
    if any(not r.get('framework_contracts_passed') for r in summary['results']):
        invalid.append('Required native framework contract evidence is absent or failed')
    bad = [r['agent'] + '/' + r['task'] + '/r' + str(r['rollout']) for r in summary['results'] if not valid_row(r)]
    if bad:
        invalid.append('Missing, unsupported, unscored, incomplete or unverifiable trials')
    metrics = task_metrics(manifest, summary)
    for key, item in metrics.items():
        if item['task'] in policy['critical_tasks'] and not item['all_rollouts_passed']:
            failures.append('Critical task failed: ' + key)
    pairs = []
    if baseline is None:
        review.append('No comparison baseline: this run can be reviewed for baseline admission only')
    else:
        before_manifest, before_summary = baseline
        before_contract = before_manifest.get('evaluation_contract') or {}
        if not contract or before_contract.get('sha256') != contract['sha256'] or digest(before_contract.get('identity')) != before_contract.get('sha256'):
            invalid.append('Baseline changes task, scorer, model, adapter or execution contract')
        if before_manifest.get('candidate', {}).get('mode') != 'git-ref':
            invalid.append('Baseline is not an immutable candidate')
        if any(not row.get('framework_contracts_passed') for row in before_summary['results']):
            invalid.append('Baseline lacks native framework contract evidence')
        before = task_metrics(before_manifest, before_summary)
        if set(before) != set(metrics) or any(x['valid'] != x['planned'] for x in before.values()):
            invalid.append('Baseline matrix is different or has invalid trials')
        if not invalid:
            for key, after in metrics.items():
                old = before[key]
                delta = after['mean'] - old['mean']
                pairs.append({'key': key, 'before': old, 'after': after, 'delta': delta,
                              'change': 'regressed' if delta < 0 else 'improved' if delta > 0 else 'unchanged'})
            for agent in (a['name'] for a in manifest['agents']):
                selected = [p for p in pairs if p['after']['agent'] == agent]
                drop = -sum(p['delta'] for p in selected) / len(selected)
                if drop > policy['max_pass_rate_drop'] + 1e-12:
                    failures.append('Pass rate drop exceeds frozen threshold: ' + agent)
            if any(p['delta'] < 0 for p in pairs):
                review.append('Per-task regressions require evidence review; improvements do not erase them')
    if not manifest.get('model_snapshot_locked'):
        review.append('Provider model alias is not snapshot-locked; causal attribution needs review')
    status = 'invalid' if invalid else 'fail' if failures else 'review' if review else 'pass'
    return {'version': policy['version'], 'run_id': manifest['run_id'], 'status': status,
        'release_passed': status == 'pass', 'baseline_run': baseline[0]['run_id'] if baseline else None,
        'candidate': candidate, 'contract_sha256': contract.get('sha256') if contract else None,
        'invalid_reasons': invalid, 'failures': failures, 'review_reasons': review,
        'invalid_trials': bad, 'planned': summary['planned'], 'passed': summary['passed'],
        'task_metrics': metrics, 'pairs': pairs, 'policy': policy,
        'statistics_note': 'Task means use all predeclared rollouts. No significance claim and no best-of-K replacement.',
        'source_summary_sha256': digest(summary)}


def render_gate(gate):
    esc = lambda v: html.escape(str(v))
    rows = ''.join('<tr>' + ''.join('<td>'+esc(v)+'</td>' for v in
        (p['key'], p['before']['passed'], p['after']['passed'], p['after']['planned'], p['change'])) + '</tr>' for p in gate['pairs'])
    return ('<!doctype html><meta charset="utf-8"><title>Release regression</title>'
        '<style>body{font:16px system-ui;margin:32px}td,th{padding:10px;border-bottom:1px solid #ddd}pre{white-space:pre-wrap}</style>'
        '<h1>Release regression: '+esc(gate['status'])+'</h1><p>'+esc(gate['run_id'])+'</p>'
        '<p><a href="report.html">Trial evidence</a> · <a href="release-gate.json">Decision JSON</a></p>'
        '<table><tr><th>Task</th><th>Baseline pass</th><th>Candidate pass</th><th>Rollouts</th><th>Change</th></tr>'+rows+'</table>'
        '<pre>'+esc(json.dumps(gate, ensure_ascii=False, indent=2))+'</pre>')
