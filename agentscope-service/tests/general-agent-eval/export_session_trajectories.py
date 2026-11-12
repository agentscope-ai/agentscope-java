# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Read complete terminal Session history and Turn outputs for private archival."""
import hashlib
import json

from recover_appworld_case import paginated, require


def trajectory(client, row):
    identity = {k: row.get(k) for k in ['dataset', 'target', 'trial', 'session', 'turn']}
    if not row.get('session'):
        return {'identity': identity, 'coverage': 'no_session_created', 'events': [], 'turns': [], 'outputs': {}}
    base = '/v1/sessions/' + row['session']
    turn_pages = paginated(client, base + '/turns')
    turns = [turn for page in turn_pages for turn in page['data']]
    require(all(t.get('session_id') == row['session'] and t.get('status') in
                {'completed', 'failed', 'cancelled'} for t in turns), 'Nonterminal or unrelated Session Turn')
    if row.get('turn'):
        require(any(t.get('id') == row['turn'] for t in turns), 'Recorded Turn absent from history')
    events = paginated(client, base + '/events')
    ids = [event['id'] for page in events for event in page['data']]
    require(len(ids) == len(set(ids)), 'Duplicate history event across pages')
    outputs = {turn['id']: paginated(client, base + '/turns/' + turn['id'] + '/output') for turn in turns}
    return {'identity': identity, 'coverage': 'complete_terminal_history', 'events': events,
            'turns': turn_pages, 'outputs': outputs, 'budget': client.api(base + '/budget')}


def export_rows(client, rows, directory):
    directory.mkdir(mode=0o700, exist_ok=True)
    receipts = []
    for row in rows:
        identity = {k: row.get(k) for k in ['dataset', 'target', 'trial', 'session', 'turn']}
        key = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()
        path = directory / (key + '.json')
        if path.exists():
            data = json.loads(path.read_text())
            require(data.get('identity') == identity, 'Existing trajectory identity differs')
        else:
            data = trajectory(client, row)
            temporary = path.with_suffix('.partial')
            with temporary.open('x') as output:
                json.dump(data, output, indent=2)
            temporary.rename(path)
        receipts.append({'identity': identity, 'file': path.name, 'coverage': data['coverage'],
                         'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                         'event_count': sum(len(p['data']) for p in data['events']),
                         'turn_count': sum(len(p['data']) for p in data['turns'])})
    return receipts
