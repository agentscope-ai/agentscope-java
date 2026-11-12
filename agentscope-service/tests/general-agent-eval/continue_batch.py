# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Continue only the unattempted suffix of a frozen batch, retaining failed trials."""
import argparse
import fcntl
import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
import sys
import time


def scheduled_keys(targets, tasks, repeats):
    for index, task in enumerate(tasks):
        for repeat in range(repeats):
            shift = (index + repeat) % len(targets)
            for target in targets[shift:] + targets[:shift]:
                yield target['name'], task, repeat


def remaining_keys(targets, tasks, repeats, rows):
    schedule = list(scheduled_keys(targets, tasks, repeats))
    attempted = [(row['target'], row['task'], row['repeat']) for row in rows]
    if attempted != schedule[:len(attempted)] or len(attempted) > len(schedule):
        raise ValueError('Attempted trials are not a unique ordered prefix of the frozen schedule')
    if any(row.get('status') in {None, 'running', 'not_run'} for row in rows):
        raise ValueError('An attempt has no final recorded outcome')
    return schedule[len(attempted):]


def load_parts(root, dataset):
    parts = [root / dataset] + sorted(root.glob(dataset + '-part-[0-9][0-9][0-9]'))
    original = json.loads((parts[0] / 'manifest.json').read_text())
    rows = []
    for part in parts:
        exit_file = root / (part.name + '-exit.json')
        if not exit_file.exists():
            raise ValueError('A prior runner has not exited: ' + part.name)
        manifest = json.loads((part / 'manifest.json').read_text())
        for key in ['tasks', 'targets', 'repeats', 'commit', 'model_profile_sha256', 'evaluation_profile_sha256']:
            if manifest.get(key) != original.get(key):
                raise ValueError('Continuation manifest differs: ' + key)
        records = part / 'results.jsonl'
        part_rows = [json.loads(line) for line in records.read_text().splitlines()] if records.exists() else []
        recorded = {row['trial'] for row in part_rows}
        for path in part.glob('*.json'):
            data = json.loads(path.read_text())
            if isinstance(data, dict) and all(key in data for key in ['trial', 'target', 'task', 'repeat', 'status']):
                if data['trial'] not in recorded:
                    raise ValueError('An unrecorded trial requires recovery before continuation: ' + data['trial'])
        rows.extend(part_rows)
    remaining_keys(original['targets'], original['tasks'], original['repeats'], rows)
    return original, rows, parts


def require_idle(state_root):
    with sqlite3.connect('file:' + str(state_root / 'environment-leases.sqlite') + '?mode=ro', uri=True) as db:
        if db.execute('SELECT COUNT(*) FROM lease').fetchone()[0]:
            raise ValueError('A prior environment lease still requires confirmed cleanup')
    with sqlite3.connect('file:' + str(state_root / 'evaluation-gateway.sqlite') + '?mode=ro', uri=True) as db:
        if db.execute('SELECT COUNT(*) FROM eval_case WHERE status<>?', ('closed',)).fetchone()[0] or \
                db.execute('SELECT COUNT(*) FROM eval_request WHERE status=?', ('pending',)).fetchone()[0]:
            raise ValueError('A prior case or request has not settled')


def main(args):
    root = Path(args.batch_root)
    # The lock covers the whole continuation, not just its startup checks.
    with (root / (args.dataset + '-continuation.lock')).open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        manifest, rows, parts = load_parts(root, args.dataset)
        remaining = remaining_keys(manifest['targets'], manifest['tasks'], manifest['repeats'], rows)
        if not remaining:
            raise ValueError('The frozen schedule is already exhausted')
        require_idle(Path(args.state_root))
        source = root / 'code'
        hashes = json.loads((root / 'runner-source.json').read_text())
        if any(hashlib.sha256((source / name).read_bytes()).hexdigest() != digest for name, digest in hashes.items()):
            raise ValueError('Frozen runner source changed')
        sys.path.insert(0, str(source))
        spec = importlib.util.spec_from_file_location('frozen_benchmark_runner', source / ('run_' + args.dataset + '.py'))
        runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runner)
        original_cases = runner.cases

        def suffix(targets, tasks, repeats):
            complete = list(original_cases(targets, tasks, repeats))
            if [(t['name'], task, repeat) for t, task, repeat in complete] != \
                    list(scheduled_keys(manifest['targets'], manifest['tasks'], manifest['repeats'])):
                raise ValueError('Runtime schedule differs from the frozen schedule')
            yield from complete[len(rows):]

        runner.cases = suffix
        part_name = args.dataset + '-part-' + str(len(parts)).zfill(3)
        provenance = {'dataset': args.dataset, 'part': part_name, 'created_at_epoch': time.time(),
                      'prior_parts': [part.name for part in parts], 'attempts_retained': len(rows),
                      'remaining': len(remaining), 'next_key': remaining[0],
                      'prior_results_sha256': {part.name: hashlib.sha256((part / 'results.jsonl').read_bytes()).hexdigest()
                                               for part in parts if (part / 'results.jsonl').exists()},
                      'continuation_source_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                      'scope': 'Scheduling suffix only; frozen runner, official tools/scorer, model and Harness unchanged'}
        with (root / (part_name + '-provenance.json')).open('x') as output:
            json.dump(provenance, output, indent=2)
        options = argparse.Namespace(config=args.config, model_profile=str(source / 'qwen38-eval.json'),
            evaluation_profile=str(source / 'evaluation-profile.json'), preflight=args.preflight,
            output=str(root / part_name), tasks=manifest['tasks'], repeats=manifest['repeats'], formal=True,
            root=args.appworld_root, offset=0, count=len(manifest['tasks']))
        runner.main(options)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--batch-root', required=True)
    parser.add_argument('--dataset', choices=['tau', 'appworld'], required=True)
    parser.add_argument('--config', required=True)
    parser.add_argument('--preflight', required=True)
    parser.add_argument('--state-root', default='/eval')
    parser.add_argument('--appworld-root', default='/eval/appworld-root')
    main(parser.parse_args())
