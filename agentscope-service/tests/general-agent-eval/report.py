# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Aggregate one immutable evaluation batch without dropping or selecting attempts."""
import argparse
from collections import Counter
import json
import math
from pathlib import Path
import statistics


def interval(successes, count):
    if not count:
        return None
    z = 1.959963984540054
    p, d = successes / count, 1 + z * z / count
    center = (p + z * z / (2 * count)) / d
    radius = z * math.sqrt(p * (1 - p) / count + z * z / (4 * count * count)) / d
    return [max(0, center - radius), min(1, center + radius)]


def reward(row):
    if row.get('status') != 'completed':
        return None
    value = row.get('reward', row.get('result', {}).get('reward', row.get('result', {}).get('success')))
    if value is None:
        raise ValueError('Completed trial has no official score')
    return float(value)


def failure_category(row):
    """Describe the recorded outcome without guessing a provider/root cause."""
    if row.get('status') == 'completed':
        return 'official_task_failure' if reward(row) != 1 else None
    if row.get('status') == 'provider_error':
        return 'provider_error'
    if row.get('status') == 'budget_exceeded':
        error = row.get('error') or ''
        if error.startswith('CaseBudgetExceeded: Turn ') and error.endswith(
                ' exceeded the evaluation wait budget'):
            return 'evaluation_wait_deadline'
        usage = row.get('budget_usage', {}).get('usage', {})
        if (usage.get('last_denial') or {}).get('limit') == 'usage_unavailable':
            return 'model_usage_unavailable'
        return 'other_budget_failure'
    return row.get('status') or 'unknown_execution_failure'


def model_usage_coverage(rows):
    requests = [request for row in rows for request in row.get('request_audit', [])
                if request['kind'] == 'model']
    known = sum(not request['audit'].get('usage_unknown') and
                all(type((request['audit'].get('usage') or {}).get(key)) is int
                    and request['audit']['usage'][key] >= 0
                    for key in ['input_tokens', 'output_tokens', 'total_tokens'])
                for request in requests)
    unaudited = sum('request_audit' not in row for row in rows)
    return {'audited_model_requests': len(requests), 'usage_reported_requests': known,
            'usage_unknown_requests': len(requests) - known,
            'incomplete_stream_requests': sum(request['audit'].get('stream_completed') is False
                                              for request in requests),
            'trials_without_request_audit': unaudited,
            'observed_tokens_are_lower_bound': known != len(requests) or unaudited > 0,
            'note': 'Missing usage is unknown, never zero. Budget usage is the saved trial snapshot, '
                    'not a live count of outstanding requests.'}


def task_comparison(manifest, rows):
    """Keep each scheduled repeat visible, including missing and unscored attempts."""
    by_case = {(row['target'], row['task'], row['repeat']): row for row in rows}
    comparison = []
    for task in manifest['tasks']:
        targets = {}
        for target in manifest['targets']:
            trials = []
            for repeat in range(manifest['repeats']):
                row = by_case.get((target['name'], task, repeat))
                if row is None:
                    trials.append({'repeat': repeat, 'status': 'not_run', 'official_score': None})
                    continue
                trials.append({
                    'repeat': repeat, 'trial': row.get('trial'), 'status': row['status'],
                    'official_score': reward(row), 'elapsed_seconds': row.get('elapsed_seconds'),
                    'session': row.get('session'), 'turn': row.get('turn'),
                    'failure_type': (row.get('error') or '').split(':', 1)[0] or None,
                    'failure_category': failure_category(row),
                    'contract_violations': row.get('contract_violations', []),
                    'budget_usage': row.get('budget_usage', {}).get('usage'),
                    'model_usage_coverage': model_usage_coverage([row]),
                })
                official = row.get('result', {})
                if 'num_tests' in official:
                    trials[-1]['official_checks'] = {
                        'total': official['num_tests'], 'passed': len(official.get('passes', [])),
                        'failed': len(official.get('failures', [])), 'difficulty': official.get('difficulty'),
                    }
            scores = [trial['official_score'] for trial in trials if trial['official_score'] is not None]
            attempted = sum(trial['status'] != 'not_run' for trial in trials)
            successes = sum(score == 1 for score in scores)
            targets[target['name']] = {
                'trials': trials, 'planned': len(trials), 'attempted': attempted,
                'official_scored': len(scores), 'successes': successes,
                'mean_official_score': statistics.mean(scores) if scores else None,
                'end_to_end_success_rate': successes / attempted if attempted else None,
                'all_repeats_successful': all(score == 1 for score in scores)
                    if len(scores) == len(trials) else None,
            }
        comparison.append({'task': task, 'targets': targets})
    return comparison


def task_table(report, manifest):
    names = [target['name'] for target in manifest['targets']]
    lines = ['# Per-task comparison', '', '| Task | ' + ' | '.join(names) + ' |',
             '| --- | ' + ' | '.join('---' for _ in names) + ' |']
    for task in report['tasks']:
        values = []
        for name in names:
            scores = [format(trial['official_score'], 'g') if trial['official_score'] is not None
                      else trial['status'] for trial in task['targets'][name]['trials']]
            values.append(', '.join(scores))
        lines.append('| ' + str(task['task']) + ' | ' + ' | '.join(values) + ' |')
    lines += ['', 'Each cell lists repeats in their original order. Numbers are official scores.',
              'Execution failures and not_run are unscored; neither is an official zero.',
              'The JSON report retains trial/Session IDs, timing, budget usage and failure categories.',
              'All-repeats success is reported only when every repeat has an official score.']
    return '\n'.join(lines) + '\n'


def summarize(manifest, rows):
    targets = [t['name'] for t in manifest['targets']]
    if not targets or len(targets) != len(set(targets)):
        raise ValueError('Invalid target manifest')
    seen = set()
    for row in rows:
        key = (row['target'], row['task'], row['repeat'])
        if key in seen:
            raise ValueError('Duplicate attempt in a batch; retain retries in a separate batch')
        if row['target'] not in targets or row['task'] not in manifest['tasks'] or not 0 <= row['repeat'] < manifest['repeats']:
            raise ValueError('Trial is outside the frozen schedule')
        seen.add(key)
    planned = len(manifest['tasks']) * manifest['repeats']
    paired = set.intersection(*[{(r['task'], r['repeat']) for r in rows
                               if r['target'] == target and r['status'] == 'completed'} for target in targets])
    result = {'benchmark': manifest['benchmark'], 'model_profile_sha256': manifest.get('model_profile_sha256'),
              'evaluation_profile_sha256': manifest.get('evaluation_profile_sha256'),
              'paired_scored_cases': len(paired), 'targets': {}, 'cost': None,
              'cost_note': 'No verified tariff or provider billing supplied; SDK zero prices are not costs.'}
    result['tasks'] = task_comparison(manifest, rows)
    for target in targets:
        selected = [r for r in rows if r['target'] == target]
        scored = [r for r in selected if r['status'] == 'completed']
        successful = sum(reward(r) == 1 for r in scored)
        usage = Counter()
        provider_usage, simulator_usage, phase_seconds = Counter(), Counter(), Counter()
        observed_models = set()
        duplicate_decisions = 0
        first_text = []
        for row in selected:
            for request in row.get('simulator_usage', []):
                for key in ['prompt_tokens', 'completion_tokens', 'total_tokens']:
                    value = (request.get('usage') or {}).get(key)
                    if type(value) is int:
                        simulator_usage[key] += value
            for key, value in row.get('budget_usage', {}).get('usage', {}).items():
                if type(value) in {int, float}:
                    usage[key] += value
            for timing in row.get('turn_timings', {}).values():
                for key in ['server_admission_to_start_seconds', 'server_start_to_terminal_seconds']:
                    if type(timing.get(key)) in {int, float}:
                        phase_seconds[key] += timing[key]
            tools = {}
            for request in row.get('request_audit', []):
                audit = request['audit']
                if request.get('finished') is not None:
                    phase_seconds[request['kind']] += request['finished'] - request['started']
                values = audit.get('usage') or {}
                for key in ['input_tokens', 'output_tokens', 'total_tokens']:
                    if type(values.get(key)) is int:
                        provider_usage[key] += values[key]
                cached = values.get('input_tokens_details') or {}
                if type(cached.get('cached_tokens')) is int:
                    provider_usage['cache_read_input_tokens'] += cached['cached_tokens']
                if type(audit.get('first_text_seconds')) in {int, float}:
                    first_text.append(audit['first_text_seconds'])
                if audit.get('returned_model'):
                    observed_models.add(audit['returned_model'])
                if request['kind'] == 'tool':
                    signature = (audit.get('name'), audit.get('arguments_sha256'))
                    identity = audit.get('meta', {}).get('io.agentscope/toolCallId')
                    if identity and signature in tools and tools[signature] != identity:
                        duplicate_decisions += 1
                    tools[signature] = identity
        durations = [r['elapsed_seconds'] for r in selected if 'elapsed_seconds' in r]
        result['targets'][target] = {'planned': planned, 'attempted': len(selected), 'not_run': planned - len(selected),
            'statuses': dict(Counter(r['status'] for r in selected)), 'official_scored': len(scored), 'successes': successful,
            'end_to_end_success_rate': successful / len(selected) if selected else None,
            'end_to_end_wilson95': interval(successful, len(selected)),
            'official_scored_success_rate': successful / len(scored) if scored else None,
            'paired_scored_success_rate': sum(reward(r) == 1 for r in scored if (r['task'], r['repeat']) in paired) / len(paired) if paired else None,
            'budget_usage': dict(usage), 'returned_models': sorted(observed_models),
            'observed_provider_usage': dict(provider_usage), 'simulator_usage': dict(simulator_usage),
            'model_usage_coverage': model_usage_coverage(selected),
            'audited_phase_seconds': dict(phase_seconds),
            'provider_first_text_seconds': {'count': len(first_text), 'median': statistics.median(first_text),
                'max': max(first_text)} if first_text else None,
            'failure_rates': {k: v / len(selected) for k, v in Counter(r['status'] for r in selected).items()
                              if k != 'completed'},
            'failure_categories': dict(Counter(category for row in selected
                                              if (category := failure_category(row)) is not None)),
            'failure_category_note': 'Categories describe saved outcomes, not inferred root causes. '
                'Official task failure is scored; all execution failures remain unscored. '
                'Usage unavailable does not establish numeric quota exhaustion.',
            'timing_note': 'Model/tool durations and provider first-text latency are measured at the streaming gateway. Server Turn lifecycle intervals are separate from client observations, which have 2-second polling resolution and do not isolate exact projection service time.',
            'repeated_tool_decisions': duplicate_decisions,
            'latency_seconds': {'median': statistics.median(durations), 'max': max(durations)} if durations else None}
        fully_scored = [task['targets'][target] for task in result['tasks']
                        if task['targets'][target]['all_repeats_successful'] is not None]
        reliable = sum(task['all_repeats_successful'] for task in fully_scored)
        result['targets'][target]['repeat_reliability'] = {
            'repeats': manifest['repeats'], 'planned_tasks': len(manifest['tasks']),
            'fully_scored_tasks': len(fully_scored), 'all_repeats_successful_tasks': reliable,
            'all_repeats_success_rate_scored_tasks': reliable / len(fully_scored) if fully_scored else None,
            'note': 'Empirical all-repeats success among fully scored tasks, not pass@k; coverage is explicit.',
        }
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('batch')
    args = parser.parse_args()
    root = Path(args.batch)
    manifest = json.loads((root / 'manifest.json').read_text())
    rows = [json.loads(line) for line in (root / 'results.jsonl').read_text().splitlines() if line.strip()]
    report = summarize(manifest, rows)
    (root / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    lines = ['# Evaluation batch report', '',
             '| Harness | Planned | Attempted | Scored | Successes | End-to-end | Scored only |',
             '| --- | ---: | ---: | ---: | ---: | ---: | ---: |']
    percent = lambda value: 'N/A' if value is None else f'{value:.1%}'
    for name, row in report['targets'].items():
        lines.append(f"| {name} | {row['planned']} | {row['attempted']} | {row['official_scored']} | {row['successes']} | "
                     f"{percent(row['end_to_end_success_rate'])} | {percent(row['official_scored_success_rate'])} |")
    lines += ['', 'All attempted cases remain in the end-to-end denominator. Missing, invalid and unscored cases are reported separately.',
              'Retries and earlier batches are not merged or selected for the best result.',
              report['cost_note'], '', 'This report does not establish statistical superiority from smoke samples.']
    (root / 'report.md').write_text('\n'.join(lines) + '\n')
    (root / 'cases.md').write_text(task_table(report, manifest))
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
