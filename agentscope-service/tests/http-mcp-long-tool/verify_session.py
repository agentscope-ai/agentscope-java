#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Real model/MCP acceptance in an explicitly selected Session; never uses the manual Session."""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def run(args):
    config = json.loads(Path(__file__).with_name('cluster-test.json').read_text())
    if args.session == config['preparedSessionId']:
        raise ValueError('Use a separate acceptance Session; preserve the manual Session')
    token = json.loads(args.token_file.read_text())['token']
    args.out_dir.mkdir(parents=True, exist_ok=True)
    os.chmod(args.out_dir, 0o700)
    marker = 'mcp-accept-' + str(uuid.uuid4())
    base = config['baseUrl']
    session_path = '/v1/sessions/' + urllib.parse.quote(args.session, safe='')
    report = {'passed': False, 'sessionId': args.session, 'marker': marker, 'seconds': args.seconds,
              'model': 'real DashScope model', 'sandbox': False, 'nas': False, 'states': []}

    def save(name, value):
        path = args.out_dir / name
        path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')
        os.chmod(path, 0o600)

    def api(path, body=None, key=None):
        headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json',
                   'X-AgentScope-Tenant': 'default', 'X-AgentScope-Namespace': 'default', 'AgentScope-Extensions': 'turn-inputs-v1'}
        if key:
            headers['Idempotency-Key'] = key
        request = urllib.request.Request(base + path,
            None if body is None else json.dumps(body).encode(), headers)
        try:
            with urllib.request.urlopen(request, timeout=40) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            save('last-api-error.json', {'path': path, 'status': error.code,
                                        'body': error.read().decode()})
            raise RuntimeError(f'HTTP {error.code}: {path}; see private error file') from None

    capabilities = api(session_path + '/capabilities')
    if capabilities.get('protocol_revision') != 'agentscope-service-2026-10-19-draft3':
        raise RuntimeError('Deploy the target protocol before running cluster acceptance; no work was submitted')

    def submit(text, key):
        response = api(session_path + '/events', {'events': [{'type': 'user.message',
            'content': [{'type': 'text', 'text': text}], 'agentscope_service': {'intent': 'new_turn'}}]}, key)
        return response['data'][0]['agentscope_service']['receipt']
    t1 = submit(f'请只调用一次 longwait__long_wait，marker={marker}，seconds={args.seconds}。'
        '等待真实结果后完整报告 marker、call_id、started_at、finished_at。不要重复调用，不要伪造结果。', marker + '-t1')
    t2 = submit(f'这是排队的 T2。本次 marker 是 {marker}。不要调用任何工具，只从上一轮真实工具结果'
        '完整报告 marker、call_id 和 finished_at。', marker + '-t2')
    report.update(turn1=t1['id'], turn2=t2['id'])
    save('report.json', report)
    print('SUBMITTED', t1['id'], t2['id'], flush=True)
    previous = None
    injected = None
    injection = marker + "-injected"
    deadline = time.monotonic() + args.seconds + 240
    while time.monotonic() < deadline:
        states = [api(session_path + '/turns/' + turn['id']) for turn in [t1, t2]]
        current = tuple(s['status'] for s in states)
        if current != previous:
            report['states'].append({'time': datetime.now().astimezone().isoformat(),
                                     't1': current[0], 't2': current[1]})
            print('STATE', *current, flush=True)
            save('report.json', report)
            previous = current
        if current == ('waiting_tool', 'accepted') and injected is None:
            body = {'events': [{'type': 'user.message', 'content': [{'type': 'text', 'text': '请在本轮最终答复额外原样包含 ' + injection}], 'agentscope_service': {'intent': 'inject', 'turn_id': t1['id']}}]}
            injected = api(session_path + '/events', body, marker + '-inject')['data'][0]['agentscope_service']
            assert api(session_path + '/events', body, marker + '-inject')['data'][0]['agentscope_service'] == injected
            report['injectedInput'] = injected
            save('report.json', report)
        if any(s in {'failed', 'cancelled'} for s in current):
            save('failed-turns.json', states)
            raise AssertionError('A Turn failed or was cancelled')
        if current == ('completed', 'completed'):
            break
        time.sleep(2)
    else:
        raise AssertionError('Turns did not complete before the acceptance deadline')
    assert any(s['t1'] == 'waiting_tool' and s['t2'] == 'accepted' for s in report['states']), report['states']
    outputs = []
    for label, turn in [('t1', t1), ('t2', t2)]:
        page = api(session_path + '/turns/' + turn['id'] + '/output?limit=100')
        final = list(page['data'])
        while page['has_more']:
            page = api(session_path + '/turns/' + turn['id'] + '/output?' + urllib.parse.urlencode({'limit': 100, 'after': page['next_cursor']}))
            final.extend(page['data'])
        save(label + '-output.json', final)
        assert final, label + ' has no committed final output'
        outputs.append(json.dumps(final, ensure_ascii=False))
    assert injected and injection in outputs[0], 'Injected context was not used by T1'
    input_receipt = api(session_path + '/turns/' + t1['id'] + '/inputs/' + injected['input_id'])
    assert input_receipt['status'] == 'applied', input_receipt
    report['inputReceipt'] = input_receipt
    events, cursor = [], None
    while True:
        query = {'limit': 100, 'order': 'asc'}
        if cursor: query['page'] = cursor
        page = api(session_path + '/events?' + urllib.parse.urlencode(query))
        events.extend(page['data'])
        if page['next_page'] is None: break
        cursor = page['next_page']
    save('events.json', events)
    completed = [i for i, event in enumerate(events)
                 if event['type'] == 'agentscope_service.turn.completed' and event['agentscope_service'].get('turn_id') == t1['id']]
    running = [i for i, event in enumerate(events)
               if event['type'] == 'agentscope_service.turn.running' and event['agentscope_service'].get('turn_id') == t2['id']]
    assert completed and running and max(completed) < min(running), 'T2 overtook T1'
    report.update(outputs=outputs, t1CompletedEventIndex=completed[-1], t2RunningEventIndex=running[0])
    if args.kubeconfig:
        kubectl = ['kubectl', '--kubeconfig', str(args.kubeconfig)]
        logs = subprocess.check_output(kubectl + ['logs', '-n', config['kubernetesNamespace'],
            '-l', 'app=agentscope-service-long-tool-mcp', '--tail=-1', '--max-log-requests=4'], text=True)
        physical = []
        for line in logs.splitlines():
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            if event.get('marker') == marker:
                physical.append(event)
        started = [e for e in physical if e.get('event') == 'long_wait.started']
        finished = [e for e in physical if e.get('event') == 'long_wait.completed']
        assert len(started) == len(finished) == 1, physical
        call_id = started[0]['call_id']
        assert finished[0]['call_id'] == call_id
        assert all(call_id in output and marker in output for output in outputs), outputs
        elapsed = (datetime.fromisoformat(finished[0]['finished_at'])
                   - datetime.fromisoformat(started[0]['started_at'])).total_seconds()
        assert elapsed >= args.seconds, elapsed
        report.update(physicalInvocation=finished[0], physicalWaitSeconds=elapsed, physicalCallCount=1)
        save('mcp-invocation.json', physical)
        assert re.fullmatch(r'[A-Za-z0-9_-]+', t1['id']), 'Unsafe server Turn identifier'
        turn = "'" + t1['id'] + "'"
        sql = ("SELECT json_build_object('suspensions',(SELECT count(*) FROM ma.turn_suspension WHERE turn_id="
               + turn + "),'resumed',(SELECT count(*) FROM ma.turn_suspension WHERE turn_id="
               + turn + " AND status='resumed'),'resume_notifications',(SELECT count(*) FROM ma.turn_resume WHERE turn_id="
               + turn + ")); ")
        facts = json.loads(subprocess.check_output(kubectl + ['exec', '-n', config['kubernetesNamespace'],
            'postgres-0', '--', 'sh', '-ec',
            'PGHOST=127.0.0.1 exec psql -X -A -t -v ON_ERROR_STOP=1 -c "$1"', 'psql', sql], text=True))
        assert facts['suspensions'] >= 1 and facts['resumed'] == facts['suspensions'] and facts['resume_notifications'] >= 1, facts
        report['databaseFacts'] = facts
    report['passed'] = True
    save('report.json', report)
    print('PASS real long-tool Suspend/Resume, recovered context and T1-before-T2 order', flush=True)
    print(args.out_dir / 'report.json', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', required=True, help='Separate acceptance Session ID')
    parser.add_argument('--token-file', required=True, type=Path, help='Private login JSON containing token')
    parser.add_argument('--out-dir', required=True, type=Path)
    parser.add_argument('--kubeconfig', type=Path, help='Also check physical invocations and database suspension facts')
    parser.add_argument('--seconds', type=int, default=90, choices=range(10, 181), metavar='10..180')
    run(parser.parse_args())
