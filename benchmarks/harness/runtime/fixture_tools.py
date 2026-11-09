# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Per-trial synthetic tools; fixture answers and call receipts stay off candidates."""
import copy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import inspect
import json
from pathlib import Path
import secrets
import threading
import time

ROOT = Path(__file__).resolve().parent / 'fixtures'


def fixture_module(name):
    allowed = {'test_tool_selection.py', 'test_tool_usage_relational.py',
        'test_tool_usage_incident_graph.py', 'test_todo_tools.py', 'test_subagents.py'}
    if name not in allowed:
        raise ValueError('Unknown frozen tool module')
    # A fresh module per trial also isolates mutable synthetic fixture data.
    spec = importlib.util.spec_from_file_location('eval_fixture', ROOT / name.removeprefix('test_'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return vars(module)


class Fixture:
    def __init__(self, spec):
        module = {} if spec['module'] in ('builtin', 'release-core') else fixture_module(spec['module'])
        self.tools = {}
        self.receipts = []
        self.lock = threading.Lock()
        self.todos = []
        self.ticket = secrets.token_hex(12)
        self.looked_up = False
        for name in spec['names']:
            if spec['module'] == 'release-core' and name == 'operate':
                self.tools[name] = self.operate
            elif name == 'write_todos':
                self.tools[name] = self.write_todos
            else:
                fn = module[name]
                if not getattr(fn, 'is_fixture_tool', False):
                    raise ValueError('Not a fixture tool: ' + name)
                self.tools[name] = fn

    def operate(self, op: str, **payload) -> dict:
        """op=lookup returns a ticket and price; commit requires ticket and integer quantity.
        record accepts a JSON payload; validate requires integer schema_version 2.
        A failed validation returns a correction; do not fabricate local receipts.
        """
        if op == 'lookup':
            self.looked_up = True
            return {'ticket': self.ticket, 'price': 17}
        if op == 'commit':
            if not self.looked_up or payload.get('ticket') != self.ticket or type(payload.get('quantity')) is not int:
                raise ValueError('lookup first, then commit with the returned ticket and integer quantity')
        elif op == 'validate':
            if type(payload.get('schema_version')) is not int or payload['schema_version'] != 2:
                raise ValueError('schema_version must be integer 2')
        elif op != 'record':
            raise ValueError('Unknown operation')
        return {'committed': True, 'payload': {'op': op, **payload}}

    def write_todos(self, todos: list[dict]) -> str:
        """Replace the task list. Each item has content (string) and status (pending, in_progress, completed)."""
        if not isinstance(todos, list) or any(not isinstance(t, dict) or not isinstance(t.get('content'), str)
                or t.get('status') not in ('pending', 'in_progress', 'completed') for t in todos):
            raise ValueError('Invalid todo schema')
        self.todos = copy.deepcopy(todos)
        return 'Todo list updated.'

    def schemas(self):
        return [{'name': name, 'signature': str(inspect.signature(fn)), 'description': inspect.getdoc(fn)}
                for name, fn in self.tools.items()]

    def call(self, name, args):
        with self.lock:
            entry = {'name': name, 'arguments': copy.deepcopy(args), 'started_at': time.time()}
            try:
                if name not in self.tools or not isinstance(args, dict):
                    raise ValueError('Unknown tool or invalid arguments')
                fn = self.tools[name]
                inspect.signature(fn).bind(**args)
                result = fn(**args)
                entry['result'] = copy.deepcopy(result)
                return {'result': result}
            except (ValueError, TypeError, KeyError) as error:
                entry['error'] = str(error)
                return {'error': str(error)}
            finally:
                entry['finished_at'] = time.time()
                self.receipts.append(entry)


class ToolServer:
    def __init__(self, host='0.0.0.0', port=8765):
        self.fixtures = {}
        owner = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                token = self.headers.get('Authorization', '').removeprefix('Bearer ')
                fixture = owner.fixtures.get(token)
                if self.path != '/call' or fixture is None:
                    self.send_error(403)
                    return
                try:
                    length = int(self.headers.get('Content-Length', '0'))
                    if not 0 < length <= 131072:
                        raise ValueError('Invalid payload length')
                    data = json.loads(self.rfile.read(length))
                    result = fixture.call(data['name'], data['arguments'])
                except (ValueError, KeyError, TypeError):
                    self.send_error(400)
                    return
                body = json.dumps(result).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
        self.server = ThreadingHTTPServer((host, port), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def register(self, spec, host):
        token = secrets.token_hex(32)
        self.fixtures[token] = Fixture(spec)
        return token, {'url': f'http://{host}:{self.server.server_port}/call', 'token': token,
                       'tools': self.fixtures[token].schemas()}

    def release(self, token):
        return self.fixtures.pop(token).receipts

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


def client(connection):
    # This capability permits only this trial's mock calls, never access to scores or other trials.
    return '''import json, sys, urllib.request
connection = ''' + repr(connection) + '''
if len(sys.argv) != 3:
    raise SystemExit("Usage: python /workspace/fixture_tools.py TOOL_NAME 'JSON_OBJECT'")
request = urllib.request.Request(connection['url'], data=json.dumps({'name': sys.argv[1], 'arguments': json.loads(sys.argv[2])}).encode(), headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + connection['token']})
with urllib.request.urlopen(request, timeout=30) as response:
    print(response.read().decode())
'''
