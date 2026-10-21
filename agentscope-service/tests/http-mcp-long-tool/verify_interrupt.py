#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Check ordinary interrupt/follow-up while T2 is queued in a separate acceptance Session."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def has_idle_boundary(events, interrupt_event_id, next_turn):
    """History sorts by processed_at/id, not the optional Turn-notification order."""
    interrupt = next(event for event in events if event['id'] == interrupt_event_id)
    running = next(event for event in events if event['type'] == 'agentscope_service.turn.running'
                   and event.get('agentscope_service', {}).get('turn_id') == next_turn)
    lower, upper = (datetime.fromisoformat(event['processed_at'].replace('Z', '+00:00')) for event in (interrupt, running))
    return any(event['type'] == 'session.status_idle' and event.get('stop_reason') == {'type': 'end_turn'}
               and lower <= datetime.fromisoformat(event['processed_at'].replace('Z', '+00:00')) <= upper for event in events)


def run(args):
    config = json.loads(Path(__file__).with_name('cluster-test.json').read_text())
    assert args.session != config['preparedSessionId'], 'Preserve the manual Session'
    token = json.loads(args.token_file.read_text())['token']
    args.out_dir.mkdir(parents=True, exist_ok=True)
    args.out_dir.chmod(0o700)
    path = '/v1/sessions/' + urllib.parse.quote(args.session, safe='')
    marker = 'interrupt-' + str(uuid.uuid4())
    headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json',
               'X-AgentScope-Tenant': 'default', 'X-AgentScope-Namespace': 'default',
               'AgentScope-Extensions': 'turn-inputs-v1'}

    def api(suffix, body=None, key=None):
        request = urllib.request.Request(config['baseUrl'] + path + suffix,
            None if body is None else json.dumps(body).encode(),
            headers if key is None else {**headers, 'Idempotency-Key': key})
        with urllib.request.urlopen(request, timeout=40) as response:
            return json.load(response)

    def message(text, intent=True):
        return {'type': 'user.message', 'content': [{'type': 'text', 'text': text}],
                **({'agentscope_service': {'intent': 'new_turn'}} if intent else {})}

    assert api('/capabilities')['protocol_revision'] == 'agentscope-service-2026-10-19-draft3'
    first = api('/events', {'events': [message('请只调用一次 longwait__long_wait，seconds=15，marker=' + marker + '。等待真实结果再回答。')]}, marker + '-t1')
    t1 = first['data'][0]['agentscope_service']['turn_id']
    deadline = time.monotonic() + 180
    while api('/turns/' + t1)['status'] != 'waiting_tool':
        assert time.monotonic() < deadline, 'T1 did not suspend'
        time.sleep(1)
    t2 = api('/events', {'events': [message('不要调用工具，只回复 T2-OK')]}, marker + '-t2')['data'][0]['agentscope_service']['turn_id']
    batch = {'events': [{'type': 'user.interrupt'}, message('不要调用工具，只回复 FOLLOWUP-OK', False)]}
    receipt = api('/events', batch, marker + '-interrupt')
    assert receipt['data'][0]['agentscope_service']['turn_id'] == t1
    t3 = receipt['data'][1]['agentscope_service']['turn_id']
    states = []
    while True:
        current = [api('/turns/' + turn)['status'] for turn in [t1, t2, t3]]
        if not states or states[-1] != current:
            states.append(current)
            print('STATE', *current, flush=True)
        if current == ['cancelled', 'completed', 'completed']:
            break
        assert 'failed' not in current and current[1] != 'cancelled' and current[2] != 'cancelled', current
        assert time.monotonic() < deadline, current
        time.sleep(1)
    assert api('/events', batch, marker + '-interrupt') == receipt, 'Frozen interrupt receipt changed'
    try:
        api('/events', {'events': [{'type': 'user.interrupt', 'agentscope_service': {'intent': 'cancel', 'turn_id': t1}}]}, marker + '-terminal')
        raise AssertionError('Terminal cancel unexpectedly admitted')
    except urllib.error.HTTPError as error:
        detail = json.load(error)
        assert error.code == 409 and detail['error']['code'] == 'turn_terminal', detail
    events, cursor = [], None
    while True:
        page = api('/events?' + urllib.parse.urlencode({'limit': 100, 'order': 'asc', **({'page': cursor} if cursor else {})}))
        events.extend(page['data']); cursor = page['next_page']
        if cursor is None: break
    def index(kind, turn):
        return next(i for i, event in enumerate(events) if event['type'] == kind and event.get('agentscope_service', {}).get('turn_id') == turn)
    assert index('agentscope_service.turn.cancelled', t1) < index('agentscope_service.turn.running', t2) < index('agentscope_service.turn.running', t3)
    assert has_idle_boundary(events, receipt['data'][0]['id'], t2), 'Missing interrupt/idle/end_turn boundary'
    result = {'passed': True, 'sessionId': args.session, 'turns': [t1, t2, t3], 'states': states, 'receipt': receipt, 'events': events}
    report = args.out_dir/'report.json'; report.write_text(json.dumps(result, indent=2, ensure_ascii=False)+'\n'); report.chmod(0o600)
    print('PASS interrupt/follow-up, FIFO and terminal cancel/replay', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', required=True)
    parser.add_argument('--token-file', type=Path, required=True)
    parser.add_argument('--out-dir', type=Path, required=True)
    run(parser.parse_args())
