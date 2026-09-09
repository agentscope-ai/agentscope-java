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
from pathlib import Path
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--env-file', type=Path, required=True, help='Private deploy .env; never printed')
    parser.add_argument('--state', type=Path, required=True, help='IDs saved here; reuse with --resume after restart')
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--model-turn', action='store_true', help='Also call the configured model (requires working credentials)')
    args = parser.parse_args()
    settings = dict(line.split('=', 1) for line in args.env_file.read_text().splitlines() if line and not line.startswith('#') and '=' in line)
    token = ''

    def request(method, path, body=None, auth=True, expected=None):
        headers = {'Content-Type': 'application/json'}
        if token and auth:
            headers['Authorization'] = 'Bearer ' + token
        req = Request(args.base.rstrip('/') + path, data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method)
        try:
            with urlopen(req, timeout=30) as response:
                code, raw = response.status, response.read()
        except HTTPError as error:
            code, raw = error.code, error.read()
        if expected is not None:
            assert code == expected, f'{method} {path}: expected {expected}, got {code}'
        else:
            assert 200 <= code < 300, f'{method} {path}: HTTP {code}'
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return raw.decode()

    request('GET', '/actuator/health', auth=False)
    request('GET', '/healthz', auth=False)
    request('GET', '/api/internal/channels/config', auth=False, expected=404)
    for name in ('admin', 'alice', 'bob'):
        request('POST', '/api/auth/login', {'username': name, 'password': name}, auth=False, expected=401)
    login = request('POST', '/api/auth/login', {'username': settings.get('AISTIO_BOOTSTRAP_ADMIN', 'admin'), 'password': settings['AISTIO_BOOTSTRAP_PASSWORD']}, auth=False)
    token = login['token']
    me = request('GET', '/api/auth/me')
    assert me['isAdmin']
    if args.resume:
        state = json.loads(args.state.read_text())
        request('GET', '/api/v1/agents/' + state['agent'])
        request('GET', '/api/sessions/' + state['session'] + '/events')
        request('GET', '/api/v1/issues/' + state['issue'])
        if state.get('team'):
            request('GET', '/api/v1/teams/' + state['team'])
        print('Restart check passed: administrator, Agent and Session history remain available.')
        return
    suffix = str(time.time_ns())
    agent = request('POST', '/api/v1/agents', {'agentKey': 'release-smoke-' + suffix, 'displayName': 'Release smoke', 'ownerType': 'user', 'binding': {'kind': 'managed', 'priority': 100}, 'definition': {'name': 'Release smoke', 'system': 'Answer briefly.', 'model': 'qwen-plus'}})['agent']
    environment = request('POST', '/api/environments', {'name': 'release-smoke-' + suffix, 'type': 'local', 'config': {}})
    session = request('POST', '/api/sessions', {'agent': agent['id'], 'environmentId': environment['id']})
    state = {'agent': agent['id'], 'environment': environment['id'], 'session': session['id']}
    request('GET', '/api/sessions/' + session['id'] + '/events')
    request('GET', '/api/agents/' + agent['id'] + '/workspace')
    team = request('POST', '/api/v1/teams', {'name': 'release-smoke-' + suffix, 'leaderAgentId': agent['id']})
    state['team'] = team.get('team', team)['id']
    # Unassigned Issue verifies persistence without dispatching a paid model task.
    issue = request('POST', '/api/v1/issues', {'title': 'Release smoke ' + suffix, 'description': 'Deployment verification; no external work.'})
    state['issue'] = issue.get('issue', issue)['id']
    if args.model_turn:
        request('POST', '/api/sessions/' + session['id'] + '/events', {'events': [{'type': 'user.message', 'payload': {'text': 'Reply with: release smoke passed'}}]})
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            events = request('GET', '/api/sessions/' + session['id'] + '/events')
            if any(event.get('type') == 'agent.message' and 'release smoke passed' in json.dumps(event.get('payload', {})).lower() for event in (events if isinstance(events, list) else events.get('events', []))):
                break
            time.sleep(2)
        else:
            raise AssertionError('No model response within 120 seconds')
    args.state.parent.mkdir(parents=True, exist_ok=True)
    args.state.write_text(json.dumps(state, indent=2) + '\n')
    print('Release smoke passed: health, bootstrap, demo-login rejection, Agent, Environment, Session, Team, Issue.')
    if not args.model_turn:
        print('Model inference was not exercised; add --model-turn with valid credentials to qualify it.')


if __name__ == '__main__':
    main()
