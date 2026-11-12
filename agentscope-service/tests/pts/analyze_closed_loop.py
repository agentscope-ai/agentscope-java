#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Reconcile fixed business concurrency against durable facts and retained telemetry."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
from analyze import distribution, reconcile


def lines(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def analyze(directory, facts, durable_samples=None):
    config = json.loads((directory/'configuration.json').read_text())
    summary = json.loads((directory/'summary.json').read_text())
    admissions = lines(directory/'admissions.jsonl') if (directory/'admissions.jsonl').exists() else []
    begin, end = summary.get('hold_started_at'), summary.get('hold_ended_at')
    established = begin is not None and end is not None and end > begin
    if not established:
        begin = min((a['submitted_at'] for a in admissions),default=summary['started_at'])
        end = summary['finished_at']
    if end is None or end <= begin:
        raise ValueError('No valid observation window')
    # The driver fetches every 3s; the observer samples every 15s. Count each real sample once.
    observed = {s['sampled_at']:s for s in lines(directory/'observer.jsonl')}
    observed = [observed[t] for t in sorted(observed)]
    result = reconcile(facts, {'batch':config['batch'],'scenario':'tool' if config['tool'] else 'short',
        'seconds':30 if config['tool'] else 0,'rps':0}, begin*1000,end*1000,observed)
    result.pop('capacity_candidate_passed')  # This run has a concurrency target, not an offered-RPS target.
    steady_begin, steady_end = begin, end
    durable_file = durable_samples or directory/'durable-concurrency.jsonl'
    durable = [s for s in lines(durable_file)
               if steady_begin <= s['sampled_at'] <= steady_end]
    unique = {}
    sample_errors = 0
    for sample in durable:
        if 'error' in sample or 'active_turns' not in sample:
            sample_errors += 1
            continue
        timestamp = sample['sampled_at']
        if timestamp in unique and unique[timestamp]['active_turns'] != sample['active_turns']:
            sample_errors += 1
        unique[timestamp] = sample
    durable = [unique[t] for t in sorted(unique)]
    values = [s['active_turns'] for s in durable]
    target = config['concurrency']
    fraction = sum(v >= target*.98 for v in values)/max(1,len(values))
    sample_times = [s['sampled_at'] for s in durable]
    cadence_good = bool(not sample_errors and sample_times and sample_times[0]-steady_begin <= 1
        and steady_end-sample_times[-1] <= 1
        and all(b-a <= 1 for a,b in zip(sample_times,sample_times[1:])))
    counters = [m['value'] for s in observed if begin <= s['sampled_at'] <= end
                for m in s['metrics'] if m['name']=='ma_pg_deadlocks_total']
    deadlocks = max(counters)-min(counters) if counters else None
    failures = result['turn_statuses'].get('failed',0)+result['turn_statuses'].get('cancelled',0)
    required_hold=float(config.get('hold',600))
    passed = (established and end-begin >= required_hold and fraction >= .99 and cadence_good
        and result['observer_coverage_sufficient'] and deadlocks == 0
        and not summary['errors'] and not summary['unresolved']
        and not result['outstanding_sessions'] and failures == 0
        and not any(result[k] for k in ['output_validation_errors','physical_tool_errors',
            'unsealed_successful_turns','pod_restart_increments','hikari_timeout_increments']))
    # Captured Hands results prove delivery to the platform, not physical at-most-once execution.
    # A storage-free fixture requires independent stdout reconciliation after the run.
    audit_path=directory/'mcp-audit.json'
    audit=json.loads(audit_path.read_text()) if audit_path.exists() else {}
    audit_ok=(audit.get('passed') is True and audit.get('batch')==config['batch']
        and audit.get('expected_markers')==len(admissions))
    if config['tool']:passed=passed and audit_ok
    result['physical_mcp_audit_passed']=audit_ok if config['tool'] else None
    result.update(acceptance_rules='data-plane-p2-v1',mode='closed_loop',synthetic_model=True,target_inflight_sessions=target,
        target_established=established,observation_started_at=begin,observation_ended_at=end,
        hold_started_at=summary.get('hold_started_at'),hold_ended_at=summary.get('hold_ended_at'),
        hold_seconds=end-begin if established else 0,required_hold_seconds=required_hold,
        durable_active_turns=distribution(values)|{'min':min(values) if values else None},
        durable_concurrency_sample_cadence_sufficient=cadence_good,
        durable_sample_source=Path(durable_file).name,
        durable_sample_errors=sample_errors,
        fraction_samples_at_least_98pct_target=fraction,
        database_deadlock_increment=deadlocks,closed_loop_passed=passed,
        driver_counters=summary['counters'],driver_errors=summary['errors'])
    result['user_message_http_admission_seconds'] = distribution([
        a['http_admission_seconds'] for a in admissions if 'http_admission_seconds' in a])
    per_pod = defaultdict(lambda:defaultdict(list))
    for point in result.get('load_timeline',[]):
        if steady_begin <= point['timestamp'] <= steady_end:
            for pod,metrics in point['pods'].items():
                for key,value in metrics.items():per_pod[pod][key].append(value)
    result['steady_pod_load'] = {pod:{key:distribution(values) for key,values in metrics.items()}
                                for pod,metrics in per_pod.items()}
    return result


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run',type=Path,required=True)
    p.add_argument('--facts',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True)
    p.add_argument('--durable-samples',type=Path, help='Independent SQL sampler file; no interpolation or cadence relaxation')
    args = p.parse_args()
    value = analyze(args.run,json.loads(args.facts.read_text()),args.durable_samples)
    args.out.write_text(json.dumps(value,indent=2))
    print(json.dumps({k:v for k,v in value.items() if k not in ['load_timeline','pod_load']}))
