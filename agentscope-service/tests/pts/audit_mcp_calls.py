#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Reconcile storage-free MCP stdout with original admissions and captured tool results."""
import argparse
from collections import defaultdict
import gzip
import json
from pathlib import Path


def records(path):
    opener = gzip.open if str(path).endswith('.gz') else open
    with opener(path, 'rt') as source:
        for line in source:
            try:
                yield json.loads(line[line.index('{'):])
            except (ValueError, TypeError):
                continue


def audit(batch, admissions, terminals, events, diagnostics):
    expected = {r['marker'] for r in admissions}
    starts, completed, captured = defaultdict(list), defaultdict(list), defaultdict(list)
    pods = set()
    for row in events:
        marker = row.get('marker')
        if marker not in expected:
            continue
        if row.get('event') == 'long_wait.started': starts[marker].append(row.get('call_id'))
        if row.get('event') == 'long_wait.completed': completed[marker].append(row.get('call_id'))
        if row.get('pod'): pods.add(row['pod'])
    for row in terminals:
        if row.get('marker') in expected:
            captured[row['marker']].extend(p.get('call_id') for p in row.get('physical', [])
                                           if p.get('state') == 'succeeded')
    bad = [m for m in expected if len(starts[m]) != 1 or len(completed[m]) != 1
           or len(captured[m]) != 1 or not starts[m][0]
           or starts[m] != completed[m] or starts[m] != captured[m]]
    drained = {r['pod'] for r in diagnostics if r.get('value', {}).get('trace_dropped') == 0
               and r['value'].get('trace_pending') == 0 and r['value'].get('active_executions') == 0}
    return {'batch': batch, 'expected_markers': len(expected), 'invalid_marker_count': len(bad),
            'invalid_marker_examples': sorted(bad)[:20],
            'log_drain_verified': bool(pods) and pods <= drained,
            'evidence_source': 'independent_mcp_stdout_and_hands_results',
            'passed': bool(expected) and len(expected) == len(admissions) and not bad
                      and bool(pods) and pods <= drained}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--batch', required=True)
    p.add_argument('--admissions', type=Path, nargs='+', required=True)
    p.add_argument('--terminals', type=Path, nargs='+', required=True)
    p.add_argument('--logs', type=Path, nargs='+', required=True)
    p.add_argument('--diagnostics', type=Path, required=True,
                   help='Final per-pod diagnostics JSON: {"pods":[{"pod":...,"value":...}]}')
    p.add_argument('--out', type=Path, required=True)
    a = p.parse_args()
    value = audit(a.batch, [r for f in a.admissions for r in records(f)],
                  [r for f in a.terminals for r in records(f)],
                  (r for f in a.logs for r in records(f)), json.loads(a.diagnostics.read_text())['pods'])
    a.out.write_text(json.dumps(value, indent=2) + '\n')
    print(json.dumps(value))
    raise SystemExit(0 if value['passed'] else 1)
