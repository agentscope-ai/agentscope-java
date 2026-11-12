# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Continue a frozen single-attempt lane only after separate failure recovery.

Never releases leases, resets unknown work, retries a recorded task, or changes
the frozen runner. The main guard is required by AppWorld multiprocessing spawn.
"""
import argparse
import fcntl
import hashlib
import json
from pathlib import Path
import sys
import time


def load_lane(root, target, current_part=None):
    base = root / (target + '-formal')
    parts = [base] + sorted(p for p in root.glob(target + '-formal-part-[0-9][0-9][0-9]') if p.is_dir())
    manifest = json.loads((base / 'manifest.json').read_text())
    if manifest['repeats'] != 1 or [t['name'] for t in manifest['targets']] != [target]:
        raise ValueError('Expected exactly one target and one first attempt per task')
    # A launch may fail before its output directory exists. Its exit receipt is
    # still mandatory; otherwise another controller may be about to admit work.
    for launch in root.glob(target + '-formal*-launch.json'):
        if launch.name == str(current_part) + '-launch.json':
            continue
        exit_path = root / (launch.name.removesuffix('-launch.json') + '-exit.json')
        if not exit_path.exists():
            raise ValueError('An earlier controller has no exit receipt: ' + launch.name)
    rows = []
    for part in parts:
        if not (root / (part.name + '-exit.json')).exists():
            raise ValueError('An earlier runner has no exit receipt: ' + part.name)
        current = json.loads((part / 'manifest.json').read_text())
        for key in ['tasks', 'targets', 'repeats', 'commit', 'model_profile_sha256', 'evaluation_profile_sha256']:
            if current.get(key) != manifest.get(key):
                raise ValueError('Continuation manifest differs: ' + key)
        path = part / 'results.jsonl'
        records = [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
        recorded = {row['trial'] for row in records}
        for path in part.glob('*.json'):
            value = json.loads(path.read_text())
            if isinstance(value, dict) and all(k in value for k in ['trial', 'target', 'task', 'repeat', 'status']):
                if value['trial'] not in recorded:
                    raise ValueError('Unrecorded trial requires inspection: ' + value['trial'])
        rows.extend(records)
    return manifest, rows, parts


def main(args):
    root = Path(args.batch_root)
    with (root / (args.target + '-continuation.lock')).open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        hashes = json.loads((root / 'source-hashes.json').read_text())
        for name, sha in hashes.items():
            if hashlib.sha256((root / name).read_bytes()).hexdigest() != sha:
                raise ValueError('Frozen runner source changed: ' + name)
        sys.path.insert(0, str(root / 'code'))
        from continue_batch import remaining_keys, require_idle
        import run_appworld as runner
        output = Path(args.output)
        if output.parent != root or not output.name.startswith(args.target + '-formal-part-') or output.exists():
            raise ValueError('Output must be a new continuation part in the same batch')
        manifest, rows, parts = load_lane(root, args.target, current_part=output.name)
        remaining = remaining_keys(manifest['targets'], manifest['tasks'], 1, rows)
        if not remaining:
            raise ValueError('Lane is already exhausted')
        require_idle(Path(args.state_root))
        original = runner.cases

        def suffix(targets, tasks, repeats):
            schedule = list(original(targets, tasks, repeats))
            if [(t['name'], task, repeat) for t, task, repeat in schedule[len(rows):]] != remaining:
                raise ValueError('Schedule differs from the unattempted frozen suffix')
            yield from schedule[len(rows):]

        runner.cases = suffix
        provenance = {'created_at_epoch': time.time(), 'target': args.target,
            'prior_parts': [part.name for part in parts], 'attempts_retained': len(rows),
            'remaining': remaining, 'controller_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            'prior_results_sha256': {p.name: hashlib.sha256((p / 'results.jsonl').read_bytes()).hexdigest()
                                    for p in parts if (p / 'results.jsonl').exists()},
            'scope': 'Unattempted suffix only. Frozen runner, model, tools, budgets and grader unchanged.'}
        with (root / (output.name + '-provenance.json')).open('x') as stream:
            json.dump(provenance, stream, indent=2)
        options = argparse.Namespace(config=args.config, model_profile=str(root / 'model-profile.json'),
            evaluation_profile=str(root / 'evaluation-profile.json'), preflight=args.preflight,
            output=str(output), root=args.appworld_root, split=manifest['split'], task_list=str(root / 'tasks.json'),
            repeats=1, formal=True, offset=0, count=1)
        runner.main(options)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['batch-root', 'target', 'config', 'preflight', 'output']:
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--state-root', default='/eval')
    parser.add_argument('--appworld-root', default='/eval/appworld-root')
    main(parser.parse_args())
