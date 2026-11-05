#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Release-stack smoke check. Creates named test resources only on the supplied installation."""
import argparse
import json
import os
from pathlib import Path
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--env-file', type=Path, help='Private deploy .env; defaults to process environment')
    parser.add_argument('--state', type=Path, required=True, help='IDs saved here; reuse with --resume after restart')
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--model-turn', action='store_true', help='Also call the configured model (requires working credentials)')
    parser.add_argument('--environment-id', help='Configured Environment ID; required for authenticated installations')
    parser.add_argument('--model', help='Optional Agent model; otherwise use the platform default')
    args = parser.parse_args()
    settings = dict(os.environ)
    if args.env_file:
        settings.update(line.split('=', 1) for line in args.env_file.read_text().splitlines()
                        if line and not line.startswith('#') and '=' in line)
    local_dev = settings.get('BUILDER_LOCAL_DEV', 'false').lower() == 'true'
    token = ''
    scope_headers = {}

    def request(method, path, body=None, auth=True, expected=None, key=None):
        headers = {'Content-Type': 'application/json',
                   'AgentScope-Extensions': 'turn-inputs-v1', **scope_headers}
        if key:
            headers['Idempotency-Key'] = key
        if token and auth:
            headers['Authorization'] = 'Bearer ' + token
        req = Request(args.base.rstrip('/') + path, data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method)
        for attempt in range(3):
            try:
                with urlopen(req, timeout=30) as response:
                    code, raw = response.status, response.read()
            except HTTPError as error:
                code, raw = error.code, error.read()
            # Readiness can lag process health. Retrying a write requires its original key.
            if code == 503 and attempt < 2 and (method == 'GET' or key):
                time.sleep(attempt + 1)
                continue
            break
        if expected is not None:
            assert code == expected, f'{method} {path}: expected {expected}, got {code}'
        else:
            assert 200 <= code < 300, f'{method} {path}: HTTP {code}: {raw.decode(errors="replace")[:1024]}'
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return raw.decode()

    request('GET', '/actuator/health', auth=False)
    request('GET', '/healthz', auth=False)
    request('GET', '/api/internal/channels/config', auth=False, expected=404)
    if not local_dev:
        for name in ('admin', 'alice', 'bob'):
            request('POST', '/api/auth/login', {'username': name, 'password': name}, auth=False, expected=401)
        login = request('POST', '/api/auth/login', {
            'username': settings.get('CONTROL_PLANE_BOOTSTRAP_ADMIN', 'admin'),
            'password': settings['CONTROL_PLANE_BOOTSTRAP_PASSWORD']}, auth=False)
        token = login['token']
        assert request('GET', '/api/auth/me')['isAdmin']
    descriptor = request('GET', '/api/v1/me/scope')
    saved = json.loads(args.state.read_text()) if args.resume else {}
    tenant = saved.get('tenant', settings.get('SMOKE_TENANT'))
    namespace = saved.get('namespace', settings.get('SMOKE_NAMESPACE'))
    selected = next((item for item in descriptor['namespaces']
                     if (not tenant or item['tenant'] == tenant)
                     and (not namespace or item['name'] == namespace)), None)
    assert selected, 'No matching authorized namespace; check SMOKE_TENANT / SMOKE_NAMESPACE'
    scope_headers.update({'X-AgentScope-Tenant': selected['tenant'],
                          'X-AgentScope-Namespace': selected['name']})
    if args.resume:
        state = saved
        request('GET', '/api/v1/agents/' + state['agent'])
        session_path = '/v1/sessions/' + state['session']
        request('GET', session_path)
        request('GET', session_path + '/events?limit=100')
        if state.get('turn'):
            assert request('GET', session_path + '/turns/' + state['turn'])['status'] == 'completed'
            output = request('GET', session_path + '/turns/' + state['turn'] + '/output')
            assert 'release smoke passed' in json.dumps(output).lower(), 'Persisted output missing'
        print('Restart check passed: Agent, Session, Events and completed Turn remain available.')
        return
    suffix = str(time.time_ns())
    definition = {'name': 'Release smoke', 'system': 'Answer briefly.',
                  'tools': [{'type': 'agent_toolset', 'defaultConfig': {'enabled': False}}]}
    if args.model:
        definition['model'] = args.model
    environment_id = args.environment_id
    if not environment_id:
        if not local_dev:
            parser.error('Authenticated installation requires --environment-id; configure an Environment first')
        environment_id = request('POST', '/api/environments', {
            'name': 'release-smoke-' + suffix, 'type': 'local', 'config': {}})['id']
    definition['defaultEnvironmentId'] = environment_id
    agent = request('POST', '/api/v1/agents', {
        'agentKey': 'release-smoke-' + suffix, 'displayName': 'Release smoke',
        'binding': {'kind': 'managed'}, 'definition': definition})['agent']
    session = request('POST', '/v1/sessions', {'agent': agent['id'], 'environment_id': environment_id},
                      key='release-session-' + suffix)
    state = {'agent': agent['id'], 'session': session['id'],
             'tenant': selected['tenant'], 'namespace': selected['name'],
             'environment': environment_id}
    session_path = '/v1/sessions/' + session['id']
    request('GET', session_path)
    request('GET', session_path + '/events?limit=100')

    def save_state():
        args.state.parent.mkdir(parents=True, exist_ok=True)
        args.state.write_text(json.dumps(state, indent=2) + '\n')

    save_state()
    if args.model_turn:
        receipt = request('POST', session_path + '/events', {'events': [{
            'type': 'user.message',
            'content': [{'type': 'text', 'text': 'Reply with: release smoke passed'}],
            'agentscope_service': {'intent': 'new_turn'}}]}, key='release-turn-' + suffix)
        turn_id = receipt['data'][0]['agentscope_service']['turn_id']
        state['turn'] = turn_id
        save_state()
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            current = request('GET', session_path + '/turns/' + turn_id)
            if current['status'] == 'completed':
                output = request('GET', session_path + '/turns/' + turn_id + '/output')
                assert 'release smoke passed' in json.dumps(output).lower(), 'Expected model output missing'
                request('GET', session_path + '/events?limit=100')
                break
            if current['status'] in ('failed', 'cancelled', 'requires_action'):
                raise AssertionError('Turn did not complete: ' + json.dumps(current))
            time.sleep(2)
        else:
            raise AssertionError('Turn did not complete within 180 seconds')
    print('Release smoke passed: health, edge isolation, Managed Agent, Session and Events.')
    if not args.model_turn:
        print('Model inference was not exercised; add --model-turn with valid credentials to qualify it.')


if __name__ == '__main__':
    main()
