#!/usr/bin/env python3
"""Local stdio fixture for the real Runtime Host; performs no model calls."""

import json
import os
from pathlib import Path
import sys
import time
from urllib.request import Request, urlopen


def emit(value):
    print(json.dumps(value, ensure_ascii=False), flush=True)


if '--version' in sys.argv:
    print('codex-cli 0.153.4 (local fixture)')
    sys.exit(0)

assert 'app-server' in sys.argv, sys.argv
thread_id, turn_id = 'fixture-thread', 'fixture-turn'
for line in sys.stdin:
    message = json.loads(line)
    method = message.get('method')
    if not method:
        continue
    with Path(os.environ['FAKE_CODEX_REQUEST_LOG']).open('a') as log:
        log.write(json.dumps({'method': method}) + '\n')
    if method == 'initialized':
        continue
    params = message.get('params', {})
    result = {}
    if method in ('thread/start', 'thread/resume'):
        assert params['model'] == 'fixture-model', params.get('model')
        result = {'thread': {'id': thread_id}}
    if method == 'turn/start':
        assert params['threadId'] == thread_id
        result = {'turn': {'id': turn_id, 'status': 'inProgress', 'items': []}}
    emit({'jsonrpc': '2.0', 'id': message['id'], 'result': result})
    if method != 'turn/start':
        continue
    assert 'MANAGED_AGENTS_INTERNAL_TOKEN' not in os.environ
    mcp = os.environ['MANAGED_AGENTS_CONTROL_PLANE'] + '/mcp/collaboration'
    token = os.environ['MANAGED_AGENTS_TASK_TOKEN']
    def collaboration(identifier, rpc_method, rpc_params=None):
        body = {'jsonrpc': '2.0', 'id': identifier, 'method': rpc_method, 'params': rpc_params or {}}
        request = Request(mcp, data=json.dumps(body).encode(), method='POST',
            headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token})
        with urlopen(request, timeout=10) as response:
            result = json.loads(response.read())
            assert 'error' not in result, result
            return result['result']
    assert collaboration(1, 'initialize')['serverInfo']['name']
    assert any(tool['name'] == 'task.get' for tool in collaboration(2, 'tools/list')['tools'])
    answer = collaboration(3, 'tools/call', {'name': 'math.evaluate', 'arguments': {'expression': '7*6'}})
    assert answer['structuredContent']['result'] == '42' and not answer.get('isError'), answer
    task = collaboration(4, 'tools/call', {'name': 'task.get', 'arguments': {}})
    assert not task.get('isError'), task
    assert token.startswith('aslocal_'), 'Provider should use the attempt-local credential relay'
    context_request = Request(os.environ['MANAGED_AGENTS_CONTROL_PLANE'] + '/api/v1/agent-tasks/' + os.environ['MANAGED_AGENTS_TASK_ID'] + '/context',
                              headers={'X-Agent-Task-Token': token})
    with urlopen(context_request, timeout=10) as response:
        context = json.loads(response.read())
        assert context['task']['id'] == os.environ['MANAGED_AGENTS_TASK_ID'] and not context.get('taskToken'), context
    base = {'threadId': thread_id, 'turnId': turn_id}
    if os.environ.get('FAKE_CODEX_APPROVAL'):
        emit({'jsonrpc': '2.0', 'id': 400, 'method': 'item/commandExecution/requestApproval',
            'params': {**base, 'itemId': 'approval-tool', 'command': 'fixture-only-command', 'cwd': os.getcwd()}})
        reply = json.loads(sys.stdin.readline())
        expected = 'accept' if os.environ['FAKE_CODEX_APPROVAL'] == 'approve' else 'decline'
        assert reply.get('id') == 400 and reply.get('result', {}).get('decision') == expected, reply
        with Path(os.environ['FAKE_CODEX_REQUEST_LOG']).open('a') as log:
            log.write(json.dumps({'method': 'fixture/approval_response', 'decision': expected}) + '\n')
        answer = collaboration(5, 'tools/call', {'name': 'math.evaluate', 'arguments': {'expression': '10*2'}})
        assert answer['structuredContent']['result'] == '20' and not answer.get('isError'), answer
    for method, values in (
        ('item/started', {'item': {'type': 'agentMessage', 'id': 'message-1', 'text': ''}}),
        ('item/agentMessage/delta', {'itemId': 'message-1', 'delta': '你好 🧰'}),
        ('item/started', {'item': {'type': 'commandExecution', 'id': 'tool-1',
            'command': 'fixture-command', 'cwd': os.getcwd()}}),
        ('item/commandExecution/outputDelta', {'itemId': 'tool-1', 'delta': 'fixture result'}),
        ('item/completed', {'item': {'type': 'commandExecution', 'id': 'tool-1',
            'command': 'fixture-command', 'cwd': os.getcwd(), 'aggregatedOutput': 'fixture result',
            'exitCode': 0, 'status': 'completed'}}),
        ('thread/tokenUsage/updated', {'tokenUsage': {'total': {'inputTokens': 3, 'outputTokens': 2},
            'last': {'inputTokens': 3, 'outputTokens': 2}}}),
        # Same usage report is deliberately repeated with a new event ordinal.
        ('thread/tokenUsage/updated', {'tokenUsage': {'total': {'inputTokens': 3, 'outputTokens': 2},
            'last': {'inputTokens': 3, 'outputTokens': 2}}}),
        ('item/completed', {'item': {'type': 'agentMessage', 'id': 'message-1', 'text': '你好 🧰'}}),
    ):
        emit({'jsonrpc': '2.0', 'method': method, 'params': {**base, **values}})
    with Path(os.environ['FAKE_CODEX_REQUEST_LOG']).open('a') as log:
        log.write(json.dumps({'method': 'fixture/stream_ready'}) + '\n')
    # Permit at least one renewal while provider output is in flight.
    time.sleep(120 if os.environ.get('FAKE_CODEX_WAIT_FOR_CANCEL') == 'true' else 2.5)
    emit({'jsonrpc': '2.0', 'method': 'turn/completed',
        'params': {'threadId': thread_id, 'turn': {'id': turn_id, 'status': 'completed', 'items': []}}})
