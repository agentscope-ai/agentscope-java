# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Preserve one confirmed terminal accounting failure; never retry its trial.

This optional operational helper is separate from the frozen benchmark runner.
Every ambiguous or unfamiliar state stops before releasing the environment.
An interrupted mutation requires inspection, not rerunning this command.
"""
import argparse
from collections import Counter
import fcntl
import hashlib
import json
from pathlib import Path
import sqlite3
import sys
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from continue_batch import load_parts


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_candidate(row, turn, turns, budget, requests, events):
    """Recognize only the observed, fully settled Codex usage-accounting failure."""
    require(row.get('target') == 'codex' and row.get('status') == 'budget_exceeded',
            'Unrecognized failure; manual inspection required')
    require(row.get('reward') is None and not row.get('result'), 'Already scored attempt')
    require(turn.get('id') == row['turn'] and turn.get('session_id') == row['session']
            and turn.get('status') == 'cancelled', 'Exact Turn is not confirmed cancelled')
    require(not turns.get('has_more') and turns.get('data'), 'Incomplete Turn listing')
    require(all(t.get('session_id') == row['session'] and t.get('status') in
                {'completed', 'failed', 'cancelled'} for t in turns['data']), 'Nonterminal Session')
    require(any(t.get('id') == row['turn'] for t in turns['data']), 'Turn missing from Session')
    usage = budget['usage']
    denial = usage.get('last_denial', {})
    require(denial.get('limit') == 'usage_unavailable' and denial.get('session_id') == row['session']
            and denial.get('turn_id') == row['turn'], 'Different budget denial')
    require(requests and all(q.get('status') == 'succeeded' and q.get('finished') is not None
                            for q in requests), 'Unsettled or failed request')
    models = [q for q in requests if q['kind'] == 'model']
    tools = [q for q in requests if q['kind'] == 'tool']
    require(models and all(q['audit'].get('stream_completed') is True and
                           isinstance(q['audit'].get('usage'), dict) and
                           isinstance(q['audit']['usage'].get('total_tokens'), int)
                           for q in models), 'Incomplete provider response or unknown usage')
    require(all(q['audit']['usage']['total_tokens'] > 0 for q in models), 'Invalid provider usage')
    observed = sum(q['audit']['usage']['total_tokens'] for q in models)
    require(usage.get('model_calls') == len(models) and usage.get('tool_calls') == len(tools)
            and usage.get('in_flight_calls') == 1, 'Accounting signature differs')
    require(observed - usage.get('total_tokens', 0) == models[-1]['audit']['usage']['total_tokens'],
            'Missing usage is not exactly the final provider response')
    counts = Counter(e.get('type') for e in events)
    require(counts['span.model_request_start'] == len(models)
            and counts['span.model_request_end'] == len(models) - 1, 'Session event signature differs')
    return {'tools': len(tools), 'models': len(models), 'observed_provider_tokens': observed,
            'model_spans': {k: counts[k] for k in ['span.model_request_start', 'span.model_request_end']}}


def validate_deadline(row, turn, turns, budget, requests, events):
    """Recognize a settled cancellation after the frozen 900-second wait limit.

    The final disconnected response retains unknown usage; no accounting repair
    or score is inferred from the preserved business state.
    """
    require(row.get('status') == 'budget_exceeded' and row.get('reward') is None and not row.get('result'),
            'Not an unscored deadline failure')
    require(row.get('error') == 'CaseBudgetExceeded: Turn ' + row['turn'] +
            ' exceeded the evaluation wait budget', 'Different failure cause')
    require(turn.get('id') == row['turn'] and turn.get('session_id') == row['session']
            and turn.get('status') == 'cancelled', 'Exact Turn is not cancelled')
    require(not turns.get('has_more') and turns.get('data') and
            all(t.get('session_id') == row['session'] and t.get('status') in
                {'completed', 'failed', 'cancelled'} for t in turns['data']) and
            any(t.get('id') == row['turn'] for t in turns['data']), 'Session is not completely terminal')
    limits = row['budget']
    duration = limits['deadline_epoch_ms'] / 1000 - row['started']
    require(899 <= duration <= 900.01 and row['elapsed_seconds'] >= duration,
            'Frozen deadline was not reached')
    require(requests and all(q.get('finished') is not None and q.get('status') in {'succeeded', 'failed'}
                            for q in requests), 'Unsettled request')
    models = [q for q in requests if q['kind'] == 'model']
    tools = [q for q in requests if q['kind'] == 'tool']
    require(models and all(q['status'] == 'succeeded' for q in requests if q['kind'] != 'model'),
            'Uncertain business tool or MCP outcome')
    require(all(q['status'] == 'succeeded' and q['audit'].get('stream_completed') is True and
                type((q['audit'].get('usage') or {}).get('total_tokens')) is int
                and q['audit']['usage']['total_tokens'] > 0 for q in models[:-1]),
            'Earlier model outcome is incomplete')
    last = models[-1]
    require(last['status'] == 'failed' and last['audit'].get('error_type') == 'BrokenPipeError' and
            last['audit'].get('usage_unknown') is True and last['audit'].get('stream_completed') is False
            and not last['audit'].get('usage') and last['finished'] >= limits['deadline_epoch_ms'] / 1000,
            'Last response is not the observed deadline disconnect')
    observed = sum(q['audit']['usage']['total_tokens'] for q in models[:-1])
    usage = budget['usage']
    require(usage.get('model_calls') == len(models) and usage.get('tool_calls') == len(tools) and
            usage.get('in_flight_calls') == 0 and usage.get('total_tokens') == observed and
            usage.get('unreported_calls') == 1 and not usage.get('last_denial'),
            'Deadline accounting signature differs')
    counts = Counter(e.get('type') for e in events)
    require(counts['managed_agents.turn.cancelled'] == 1, 'Cancellation event missing or ambiguous')
    return {'tools': len(tools), 'models': len(models), 'observed_provider_tokens': observed,
            'unknown_provider_requests': 1, 'observed_tokens_are_lower_bound': True,
            'model_spans': {k: counts[k] for k in ['span.model_request_start', 'span.model_request_end']}}


def paginated(client, path):
    pages, seen, cursor, parameter = [], set(), None, 'after'
    while True:
        page = client.api(path + '?' + urlencode({'limit': 100, **({parameter: cursor} if cursor else {})}))
        pages.append(page)
        if 'next_page' in page:
            # Session history has a signed page cursor; Turn output uses after.
            parameter, cursor = 'page', page['next_page']
            if not cursor:
                return pages
        else:
            parameter, cursor = 'after', page.get('next_cursor')
            if not page.get('has_more'):
                require(not cursor, 'Cursor contradicts terminal page metadata')
                return pages
        require(cursor and (parameter, cursor) not in seen, 'Pagination did not advance')
        seen.add((parameter, cursor))


def recover(args):
    root = Path(args.batch_root)
    require(root.name == 'formal-business-20261101-182203', 'This guard is restricted to the frozen batch')
    lane = getattr(args, 'parallel_lane', None)
    failure_kind = getattr(args, 'failure_kind', 'accounting')
    if lane:
        from parallel_batch import PHASE, load_lane
        require(lane == 'codex' or failure_kind == 'deadline',
                'Only the observed Codex accounting failure is recognized')
        directory = root / PHASE / lane
        lock_path = directory / 'continuation.lock'
    else:
        directory = root
        lock_path = root / 'appworld-continuation.lock'
    # The same lock used by continuation runners prevents overlapping execution.
    with lock_path.open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        manifest, rows, parts = load_lane(root, lane) if lane else load_parts(root, 'appworld')
        part = parts[-1]
        require(part.name == args.expected_part and rows[-1]['trial'] == args.expected_trial,
                'Stopped attempt changed since inspection')
        require(json.loads((directory / (part.name + '-exit.json')).read_text())['returncode'] == 1,
                'Runner has not exited with the expected failure')
        plan = json.loads((root / 'plan.json').read_text())
        require(manifest['tasks'] == plan['datasets']['appworld']['tasks'] and manifest['repeats'] == 3,
                'Frozen schedule changed')
        frozen = root / 'code'
        hashes = json.loads((root / 'runner-source.json').read_text())
        require(all(hashlib.sha256((frozen / name).read_bytes()).hexdigest() == digest
                    for name, digest in hashes.items()), 'Frozen source changed')
        row = rows[-1]
        lease = json.loads((part / 'environment-lease.json').read_text())
        require(lease['slot'] == 'appworld', 'Wrong environment slot')
        with sqlite3.connect('file:' + str(Path(args.state_root) / 'evaluation-gateway.sqlite') + '?mode=ro',
                             uri=True) as db:
            active = db.execute('SELECT trial,target,owner FROM eval_case WHERE status<>?', ('closed',)).fetchall()
            require(active == [(row['trial'], row['target'], lease['owner'])], 'Active case/owner differs')
            require(db.execute('SELECT COUNT(*) FROM eval_request WHERE status=?', ('pending',)).fetchone()[0] == 0,
                    'Global request ledger has pending I/O')
        sys.path.insert(0, str(frozen))
        from session_client import SessionClient
        from evaluation import tree_fingerprint
        config = json.loads(Path(args.config).read_text())
        target = next(t for t in config['targets'] if t['name'] == row['target'])
        client = SessionClient(target['cluster_url'], target['headers'])
        base = '/v1/sessions/' + row['session']
        turn = client.api(base + '/turns/' + row['turn'])
        turns = client.api(base + '/turns?limit=100')
        budget = client.api(base + '/budget')

        def call(url, path, data=None, owned=True):
            body = {'slot': 'appworld', 'owner': lease['owner'], 'trial': row['trial'], **(data or {})} \
                if owned else data
            request = Request(url.rstrip('/') + path, json.dumps(body).encode(),
                              {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + config['control_key']})
            with urlopen(request, timeout=90) as response:
                return json.load(response)

        require(call(config['control_url'], '/lease/check')['status'] == 'held', 'Lease is not held')
        audit = call(config['evaluation_gateway_url'], '/control/audit')
        event_pages = paginated(client, base + '/events')
        output_pages = paginated(client, base + '/turns/' + row['turn'] + '/output')
        validator = validate_candidate if failure_kind == 'accounting' else validate_deadline
        summary = validator(row, turn, turns, budget, audit['requests'],
                            [e for p in event_pages for e in p['data']])
        prefix = (str(Path(PHASE) / lane / 'recovery-') if lane else 'appworld-recovery-') + str(len(parts)).zfill(3)
        evidence_path, dbs = root / (prefix + '.json'), root / (prefix + '-dbs')
        require(not evidence_path.exists() and not dbs.exists(), 'Recovery already started; inspect, never replay')
        initial = json.loads((part / (row['trial'] + '-initial-databases.json')).read_text())
        apps = sorted(Path(name).stem for name in initial['base']['files'] if name.endswith('.jsonl'))
        require('admin' in apps and 'supervisor' in apps, 'Incomplete initial database inventory')
        originals = {p.name: hashlib.sha256((p / 'results.jsonl').read_bytes()).hexdigest() for p in parts}
        evidence = {'trial': row['trial'], 'part': part.name, 'attempts_retained': len(rows),
                    'before_epoch': time.time(), 'turn': turn, 'all_turns': turns,
                    'audit_before': audit, 'budget_after': budget, 'summary': summary,
                    'results_sha256': originals[part.name], 'all_results_sha256': originals,
                    'recovery_source_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                    'classification': ('Original budget_exceeded and null score retained; complete provider usage, missing platform accounting; root cause unconfirmed.'
                        if failure_kind == 'accounting' else
                        'Original 900-second wait deadline failure and null score retained; final disconnected model usage remains unknown.'),
                    'failure_kind': failure_kind}
        with evidence_path.open('x') as output:
            json.dump(evidence, output, indent=2)
        with (root / (prefix + '-events.json')).open('x') as output:
            json.dump({'trial': row['trial'], 'session': row['session'], 'turn': row['turn'],
                       'event_pages': event_pages, 'output_pages': output_pages}, output, indent=2)
        # Fence late tools before saving state. No reset, replay, grading or usage reconciliation.
        evidence['audit_finish'] = call(config['evaluation_gateway_url'], '/control/finish')
        evidence_path.write_text(json.dumps(evidence, indent=2))
        evidence['state_export'] = call('http://127.0.0.1:9000', '/dbs/save', {
            'from_db_home_path': ':memory:task_output-' + row['task'], 'to_db_home_path': str(dbs),
            'format': 'full', 'app_names': apps, 'delete_if_exists': False,
            'skip_mandatory_apps': False, 'save_model_hashes': True, 'vaccum': False}, owned=False)
        evidence['final_database_fingerprint'] = tree_fingerprint(dbs)
        evidence['exported_apps'] = apps
        require(len(list(dbs.glob('*.db'))) == len(apps), 'Final database export incomplete')
        evidence['audit_after'] = call(config['evaluation_gateway_url'], '/control/audit')
        require(not any(q['status'] == 'pending' for q in evidence['audit_after']['requests']), 'Late request appeared')
        require(all(hashlib.sha256((p / 'results.jsonl').read_bytes()).hexdigest() == originals[p.name]
                    for p in parts), 'Original results changed')
        evidence_path.write_text(json.dumps(evidence, indent=2))
        evidence['lease_release'] = call(config['control_url'], '/lease/release')
        evidence['finished_at_epoch'] = time.time()
        evidence_path.write_text(json.dumps(evidence, indent=2))
        return {'trial': row['trial'], 'part': part.name, 'recovery': prefix,
                'attempts_retained': len(rows), 'lease_status': evidence['lease_release']['status'],
                'failure_kind': failure_kind, **summary}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--batch-root', required=True)
    parser.add_argument('--expected-part', required=True)
    parser.add_argument('--expected-trial', required=True)
    parser.add_argument('--config', required=True)
    parser.add_argument('--state-root', default='/eval')
    parser.add_argument('--parallel-lane', choices=['agentscope', 'pi', 'codex'], help='Use the verified isolated parallel-lane schedule')
    parser.add_argument('--failure-kind', choices=['accounting', 'deadline'], default='accounting')
    print(json.dumps(recover(parser.parse_args())))
