# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Verify and report an archived serial prefix plus disjoint parallel suffixes.

Offline only: this module never launches, recovers, resets or scores a trial.
Input archives must first be collected from terminal, idle lanes. Output is a
new private directory; only the explicitly generated reports are publishable.
"""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path

from continue_batch import load_parts, remaining_keys, scheduled_keys
from parallel_batch import PHASE, lane_remaining, load_lane, validate_plan
from report import summarize, task_table


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def keys(rows):
    return [(r['target'], r['task'], r['repeat']) for r in rows]


def merge_rows(manifest, plan, serial_rows, lanes):
    """Require the complete schedule; never deduplicate or select attempts."""
    validate_plan(plan)
    original = list(scheduled_keys(manifest['targets'], manifest['tasks'], manifest['repeats']))
    if original != [tuple(k) for k in plan['all_keys']]:
        raise ValueError('Plan differs from the original manifest schedule')
    if set(lanes) != set(plan['lanes']):
        raise ValueError('Missing or unexpected lane')
    if keys(serial_rows) != [tuple(k) for k in plan['serial_keys']]:
        raise ValueError('Serial attempts differ from the sealed prefix')
    remaining_keys(manifest['targets'], manifest['tasks'], manifest['repeats'], serial_rows)
    for field in ['model_profile_sha256', 'evaluation_profile_sha256']:
        if plan[field] != manifest[field]:
            raise ValueError('Plan configuration differs: ' + field)
    rows = [{**r, 'execution_phase': 'serial', 'execution_lane': None} for r in serial_rows]
    for target, selected in lanes.items():
        if lane_remaining(plan, target, selected):
            raise ValueError('Lane is incomplete: ' + target)
        rows.extend({**r, 'execution_phase': PHASE, 'execution_lane': target} for r in selected)
    trials = [r['trial'] for r in rows]
    if len(trials) != len(set(trials)):
        raise ValueError('Trial identity reused across lanes or phases')
    if len(rows) != len(original) or set(keys(rows)) != set(original):
        raise ValueError('Merged attempts do not cover the exact original schedule')
    positions = {key: index for index, key in enumerate(original)}
    return sorted(rows, key=lambda row: positions[(row['target'], row['task'], row['repeat'])])


def compare_hashes(manifest, rows):
    """Disclose drift/missing evidence instead of dropping affected attempts."""
    comparisons = {}
    for task in manifest['tasks']:
        selected = [r for r in rows if r['task'] == task]
        fields = {}
        for field in ['initial_hash', 'definition_sha256', 'task_sha256', 'catalog_sha256']:
            def values(row):
                if field == 'catalog_sha256':
                    return {q['audit'][field] for q in row.get('request_audit', [])
                            if q.get('audit', {}).get(field)}
                return {row[field]} if row.get(field) else set()
            hashes = sorted({value for row in selected for value in values(row)})
            fields[field] = {'values': hashes, 'mismatch': len(hashes) > 1,
                             'missing_trials': [r['trial'] for r in selected if not values(r)]}
        comparisons[str(task)] = {'phases': sorted({r['execution_phase'] for r in selected}),
                                  'hashes': fields}
    return comparisons


def result_report(manifest, rows):
    result = summarize(manifest, rows)
    by_key = {(r['target'], r['task'], r['repeat']): r for r in rows}
    for task in result['tasks']:
        for target, group in task['targets'].items():
            for trial in group['trials']:
                row = by_key[(target, task['task'], trial['repeat'])]
                usage = Counter()
                for request in row.get('request_audit', []):
                    if request['kind'] == 'model':
                        for key, value in (request['audit'].get('usage') or {}).items():
                            if key in {'input_tokens', 'output_tokens', 'total_tokens'} and type(value) is int:
                                usage[key] += value
                trial.update({'execution_phase': row['execution_phase'],
                              'execution_lane': row['execution_lane'], 'started_at_epoch': row.get('started'),
                              'observed_provider_usage': dict(usage)})
    for target in result['targets'].values():
        target['official_scoring_coverage'] = target['official_scored'] / target['planned']
        reliable = target['repeat_reliability']
        reliable['all_repeats_success_rate_all_tasks'] = (
            reliable['all_repeats_successful_tasks'] / reliable['planned_tasks'])
    result['execution_phases'] = {}
    for phase in sorted({r['execution_phase'] for r in rows}):
        selected = [r for r in rows if r['execution_phase'] == phase]
        summary = summarize(manifest, selected)
        # A phase contains only part of the full schedule. Do not label the
        # other phase's tasks as not_run or report misleading phase reliability.
        fields = ['attempted', 'statuses', 'official_scored', 'successes',
                  'end_to_end_success_rate', 'official_scored_success_rate',
                  'observed_provider_usage', 'model_usage_coverage', 'latency_seconds', 'failure_categories']
        result['execution_phases'][phase] = {
            'attempted': len(selected), 'concurrency': 1 if phase == 'serial' else 3,
            'first_started_at_epoch': min(r['started'] for r in selected),
            'last_finished_at_epoch': max(r['started'] + r['elapsed_seconds'] for r in selected),
            'targets': {name: {k: data[k] for k in fields} for name, data in summary['targets'].items()},
        }
    result['execution_note'] = ('Canonical task/repeat order is for reporting only; per-trial start times '
        'and serial/parallel phases are retained. Different task subsets and gateway routing were used '
        'in the two phases; these data are not a controlled concurrency performance comparison.')
    return result


def verify_source(root, expected):
    if json.loads((root / 'runner-source.json').read_text()) != expected:
        raise ValueError('Frozen runner source manifest differs')
    if any(digest(root / 'code' / name) != sha for name, sha in expected.items()):
        raise ValueError('Frozen runner source content differs')


def load_inputs(serial_root, lane_roots, plan):
    tau_manifest, tau_rows, _ = load_parts(serial_root, 'tau')
    if remaining_keys(tau_manifest['targets'], tau_manifest['tasks'], tau_manifest['repeats'], tau_rows):
        raise ValueError('Serial tau dataset is incomplete')
    manifest, serial_rows, serial_parts = load_parts(serial_root, 'appworld')
    if [p.name for p in serial_parts] != plan['serial_parts'] or any(
            digest(p / 'results.jsonl') != plan['serial_results_sha256'][p.name] for p in serial_parts):
        raise ValueError('Sealed serial results changed')
    source = json.loads((serial_root / 'runner-source.json').read_text())
    verify_source(serial_root, source)
    lanes, evidence = {}, {}
    if set(lane_roots) != set(plan['lanes']):
        raise ValueError('Supply exactly one archive root per planned lane')
    for target, root in lane_roots.items():
        verify_source(root, source)
        if json.loads((root / 'parallel-plan.json').read_text()) != plan:
            raise ValueError('Archived lane plan differs')
        _, rows, parts = load_lane(root, target)
        if not parts and plan['lanes'][target]:
            raise ValueError('Missing lane parts')
        part_evidence = []
        prior = []
        for part in parts:
            current = json.loads((part / 'manifest.json').read_text())
            if {k: v for k, v in current.items() if k != 'targets'} != {
                    k: v for k, v in manifest.items() if k != 'targets'} or current['targets'] != [
                        t for t in manifest['targets'] if t['name'] == target]:
                raise ValueError('Lane benchmark/configuration manifest differs')
            provenance = json.loads(part.with_name(part.name + '-provenance.json').read_text())
            if provenance['plan_sha256'] != digest(root / 'parallel-plan.json') or \
                    provenance['prior_results_sha256'] != {p.name: digest(p / 'results.jsonl') for p in prior}:
                raise ValueError('Lane provenance integrity failure')
            prior.append(part)
            part_evidence.append({'part': part.name, 'results_sha256': digest(part / 'results.jsonl'),
                                  'exit': json.loads(part.with_name(part.name + '-exit.json').read_text())})
        lanes[target] = rows
        evidence[target] = part_evidence
    merged = merge_rows(manifest, plan, serial_rows, lanes)
    if len(tau_rows) != 180 or len(merged) != 180 or tau_manifest['repeats'] != 3 or manifest['repeats'] != 3:
        raise ValueError('This collector requires the complete frozen 360-attempt batch')
    tau_rows = [{**r, 'execution_phase': 'serial', 'execution_lane': None} for r in tau_rows]
    return {'tau': (tau_manifest, tau_rows), 'appworld': (manifest, merged)}, evidence


def write_reports(output, datasets, plan, evidence, include_private_rows=True):
    # Never overwrite a prior collection or place raw rows in the public repo.
    output.mkdir(mode=0o700)
    verification = {'batch': plan['batch'], 'attempted': 360, 'unique_schedule_verified': True,
                    'serial_attempted': 180 + plan['serial_attempted'],
                    'parallel_attempted_by_lane': {k: len(v) for k, v in plan['lanes'].items()},
                    'lane_parts': evidence, 'datasets': {}, 'gateway_namespaces': plan['lane_namespaces']}
    for dataset, (manifest, rows) in datasets.items():
        directory = output / dataset
        directory.mkdir(mode=0o700)
        report = result_report(manifest, rows)
        checks = compare_hashes(manifest, rows)
        verification['datasets'][dataset] = checks
        for name, data in [('manifest.json', manifest), ('report.json', report), ('verification.json', checks)]:
            (directory / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
        if include_private_rows:
            # Stream rows instead of constructing another full dataset string.
            with (directory / 'results.private.jsonl').open('w') as stream:
                for row in rows:
                    json.dump(row, stream)
                    stream.write('\n')
        (directory / 'cases.md').write_text(task_table(report, manifest))
        lines = ['# Evaluation report', '',
                 '| Harness | Attempted | Official scored | Successes | End-to-end | Three successes / all tasks |',
                 '| --- | ---: | ---: | ---: | ---: | ---: |']
        for name, data in report['targets'].items():
            reliable = data['repeat_reliability']['all_repeats_successful_tasks']
            lines.append(f"| {name} | {data['attempted']} | {data['official_scored']} | {data['successes']} | "
                         f"{data['end_to_end_success_rate']:.1%} | {reliable}/{len(manifest['tasks'])} |")
        lines += ['', 'Unscored failures remain in the end-to-end denominator and are never official zeros.',
                  'See [cases.md](cases.md) for all three scores and [report.json](report.json) for phase, timing, '
                  'usage coverage and failure details.', '', report['execution_note'], '', report['cost_note']]
        (directory / 'report.md').write_text('\n'.join(lines) + '\n')
    (output / 'verification.json').write_text(json.dumps(verification, indent=2) + '\n')
    return verification


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial-root', type=Path, required=True)
    parser.add_argument('--serial-archive', type=Path, required=True)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--lane', action='append', required=True, help='target=/private/archive/root')
    parser.add_argument('--output', type=Path, required=True, help='New private directory, not the public report path')
    args = parser.parse_args()
    plan = json.loads(args.plan.read_text())
    if digest(args.serial_archive) != plan['serial_archive_sha256']:
        raise ValueError('Serial archive differs from the sealed cutover archive')
    entries = [value.split('=', 1) for value in args.lane]
    if len({name for name, _ in entries}) != len(entries):
        raise ValueError('Repeated lane argument')
    datasets, evidence = load_inputs(args.serial_root, {k: Path(v) for k, v in entries}, plan)
    os.umask(0o077)
    write_reports(args.output, datasets, plan, evidence)
    print(json.dumps({'attempted': 360, 'output': str(args.output), 'status': 'verified_offline'}))


if __name__ == '__main__':
    main()
