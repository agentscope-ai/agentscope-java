# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Partition an untouched schedule suffix into one isolated lane per Harness."""
import argparse
import fcntl
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import time

from continue_batch import remaining_keys, require_idle, scheduled_keys


PHASE = 'parallel-20261102'


def build_plan(batch, manifest, rows):
    remaining = remaining_keys(manifest['targets'], manifest['tasks'], manifest['repeats'], rows)
    complete = list(scheduled_keys(manifest['targets'], manifest['tasks'], manifest['repeats']))
    return {'version': 1, 'batch': batch, 'phase': PHASE, 'dataset': 'appworld',
            'created_at_epoch': time.time(), 'serial_attempted': len(rows),
            'all_keys': complete, 'serial_keys': complete[:len(rows)],
            'lanes': {t['name']: [key for key in remaining if key[0] == t['name']]
                      for t in manifest['targets']},
            'concurrency': len(manifest['targets']), 'per_lane_concurrency': 1,
            'tasks': manifest['tasks'], 'repeats': manifest['repeats'],
            'model_profile_sha256': manifest['model_profile_sha256'],
            'evaluation_profile_sha256': manifest['evaluation_profile_sha256'],
            'scope': 'Only unattempted entries; preserve each target task/repeat order. Serial and parallel phases must be reported separately.'}


def validate_plan(plan):
    complete = [tuple(k) for k in plan['all_keys']]
    serial = [tuple(k) for k in plan['serial_keys']]
    if serial != complete[:len(serial)] or len(serial) != plan['serial_attempted']:
        raise ValueError('Invalid serial prefix')
    union = []
    for target, keys in plan['lanes'].items():
        keys = [tuple(k) for k in keys]
        if keys != [k for k in complete[len(serial):] if k[0] == target]:
            raise ValueError('Lane is not the untouched ordered target suffix')
        union.extend(keys)
    if len(set(complete)) != len(complete) or len(set(union)) != len(union) or \
            set(union) != set(complete[len(serial):]):
        raise ValueError('Parallel plan has duplicated or missing entries')


def lane_remaining(plan, target, rows):
    validate_plan(plan)
    keys = [tuple(k) for k in plan['lanes'][target]]
    attempted = [(r['target'], r['task'], r['repeat']) for r in rows]
    if attempted != keys[:len(attempted)] or len(attempted) > len(keys):
        raise ValueError('Lane attempts are not its unique ordered prefix')
    if any(r.get('status') in {None, 'running', 'not_run'} for r in rows):
        raise ValueError('A lane attempt has no recorded outcome')
    return keys[len(rows):]


def load_lane(root, target):
    plan = json.loads((root / 'parallel-plan.json').read_text())
    directory = root / PHASE / target
    parts = sorted(directory.glob('part-[0-9][0-9][0-9]'))
    rows, manifest = [], None
    for part in parts:
        if not (directory / (part.name + '-exit.json')).exists():
            raise ValueError('Prior lane runner has not exited')
        current = json.loads((part / 'manifest.json').read_text())
        if current['tasks'] != plan['tasks'] or current['repeats'] != plan['repeats'] or \
                [t['name'] for t in current['targets']] != [target] or any(
                    current[k] != plan[k] for k in ['model_profile_sha256', 'evaluation_profile_sha256']):
            raise ValueError('Lane manifest differs from the frozen inputs')
        manifest = current
        path = part / 'results.jsonl'
        part_rows = [json.loads(x) for x in path.read_text().splitlines()] if path.exists() else []
        recorded = {r['trial'] for r in part_rows}
        for path in part.glob('*.json'):
            data = json.loads(path.read_text())
            if isinstance(data, dict) and all(k in data for k in ['trial', 'target', 'task', 'repeat', 'status']) \
                    and data['trial'] not in recorded:
                raise ValueError('Unrecorded lane trial requires inspection')
        rows.extend(part_rows)
    lane_remaining(plan, target, rows)
    return manifest, rows, parts


def main(args):
    root = Path(args.batch_root)
    plan_bytes = (root / 'parallel-plan.json').read_bytes()
    if hashlib.sha256(plan_bytes).hexdigest() != args.plan_sha256:
        raise ValueError('Parallel plan hash differs')
    plan = json.loads(plan_bytes)
    validate_plan(plan)
    directory = root / PHASE / args.target
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / 'continuation.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        _, rows, parts = load_lane(root, args.target)
        remaining = lane_remaining(plan, args.target, rows)
        if not remaining or args.part != 'part-' + str(len(parts)).zfill(3):
            raise ValueError('Wrong next part or exhausted lane')
        config = json.loads(Path(args.config).read_text())
        if [t['name'] for t in config['targets']] != [args.target]:
            raise ValueError('Lane configuration must contain exactly its own target')
        require_idle(Path('/eval'))
        frozen = root / 'code'
        hashes = json.loads((root / 'runner-source.json').read_text())
        if any(hashlib.sha256((frozen / n).read_bytes()).hexdigest() != sha for n, sha in hashes.items()):
            raise ValueError('Frozen runner source changed')
        sys.path.insert(0, str(frozen))
        spec = importlib.util.spec_from_file_location('frozen_parallel_appworld', frozen / 'run_appworld.py')
        runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runner)
        original = runner.cases

        def suffix(targets, tasks, repeats):
            complete = list(original(targets, tasks, repeats))
            if [(t['name'], task, repeat) for t, task, repeat in complete] != \
                    [tuple(k) for k in plan['all_keys'] if k[0] == args.target]:
                raise ValueError('Frozen Runner generated a different target schedule')
            selected = set(remaining)
            yield from ((t, task, repeat) for t, task, repeat in complete
                        if (t['name'], task, repeat) in selected)

        runner.cases = suffix
        provenance = {'phase': PHASE, 'target': args.target, 'part': args.part,
                      'plan_sha256': args.plan_sha256, 'serial_attempts_retained': plan['serial_attempted'],
                      'lane_attempts_retained': len(rows), 'remaining': remaining,
                      'created_at_epoch': time.time(),
                      'prior_results_sha256': {p.name: hashlib.sha256((p / 'results.jsonl').read_bytes()).hexdigest()
                                               for p in parts},
                      'scheduler_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
        with (directory / (args.part + '-provenance.json')).open('x') as output:
            json.dump(provenance, output, indent=2)
        options = argparse.Namespace(config=args.config, model_profile=str(frozen / 'qwen38-eval.json'),
            evaluation_profile=str(frozen / 'evaluation-profile.json'), preflight=args.preflight,
            output=str(directory / args.part), root='/eval/appworld-root', offset=0,
            count=len(plan['tasks']), repeats=plan['repeats'], formal=True)
        runner.main(options)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['batch-root', 'target', 'part', 'config', 'preflight', 'plan-sha256']:
        parser.add_argument('--' + name, required=True)
    main(parser.parse_args())
