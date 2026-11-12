# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Evaluation-only model audit and fenced MCP transport; never implements an agent loop.

One process per environment. State and audit records survive process restarts. An
unconfirmed tool request blocks reuse until explicitly inspected by the operator.
Provider credentials are forwarded only to the fixed upstream and never persisted.
"""
import argparse
from contextlib import contextmanager
from decimal import Decimal
import hashlib
import hmac
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import os
import sqlite3
import threading
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit
import uuid

from environment_leases import EnvironmentLeases
from evaluation import digest


MAX_BODY = 64 * 1024 * 1024


def schema_digest(value):
    """Compare JSON numeric values, not language-specific 0 versus 0.0 spelling.

    Decimal tuples preserve large integers exactly; converting everything to
    float would incorrectly hide lost integer precision or changed constraints.
    Typed nodes keep numeric normalization distinct from ordinary schema objects.
    """
    def canonical(item):
        if isinstance(item, bool) or item is None:
            return ['literal', item]
        if isinstance(item, (int, float)):
            number = Decimal(str(item))
            if not number.is_finite():
                raise ValueError('Non-finite JSON schema number')
            if not number:
                return ['number', 0, '0', 0]
            sign, digits, exponent = number.as_tuple()
            text = ''.join(map(str, digits))
            trimmed = text.rstrip('0')
            return ['number', sign, trimmed, exponent + len(text) - len(trimmed)]
        if isinstance(item, dict):
            return ['object', [[key, canonical(val)] for key, val in sorted(item.items())]]
        if isinstance(item, list):
            return ['array', [canonical(val) for val in item]]
        return ['string', item]
    return digest(canonical(value))


def schema_number_repairs(received, original, path=''):
    """Find only binary64 precision loss, rejecting every other schema change."""
    numeric = lambda value: isinstance(value, (int, float)) and not isinstance(value, bool)
    if numeric(received) and numeric(original):
        if schema_digest(received) == schema_digest(original):
            return []
        if isinstance(original, int) and abs(original) > 2**53 - 1 and float(received) == float(original):
            return [path]
        return None
    if isinstance(received, dict) and isinstance(original, dict) and received.keys() == original.keys():
        children = [schema_number_repairs(received[key], value, path + '/' + key)
                    for key, value in original.items()]
    elif isinstance(received, list) and isinstance(original, list) and len(received) == len(original):
        children = [schema_number_repairs(left, right, path + '/' + str(index))
                    for index, (left, right) in enumerate(zip(received, original))]
    elif type(received) is type(original) and received == original:
        return []
    else:
        return None
    return None if any(child is None for child in children) else [item for child in children for item in child]


def rpc_result(body):
    """Read JSON or a finite MCP JSON-RPC response carried in SSE."""
    try:
        return json.loads(body)
    except (ValueError, UnicodeDecodeError):
        for line in body.decode('utf8').splitlines():
            if line.startswith('data:'):
                try:
                    value = json.loads(line[5:])
                    if isinstance(value, dict) and ('result' in value or 'error' in value):
                        return value
                except ValueError:
                    pass
    raise ValueError('Missing JSON-RPC response')


from provider_stream import stream_model


def strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, list):
        for item in value:
            yield from strings(item)
    elif isinstance(value, dict):
        for item in value.values():
            yield from strings(item)


def request_audit(request, policy, system):
    """Export hashes, public tool schemas and controlled parameters, never message text."""
    expected = policy['request']
    actual = {k: request.get(k) for k in expected}
    violations = [k for k, value in expected.items() if actual[k] != value]
    auxiliary = bool(policy.get('allowNativeAuxiliaryRequests') and not request.get('tools'))
    if auxiliary and type(actual.get('max_output_tokens')) is int and \
            0 < actual['max_output_tokens'] <= expected['max_output_tokens']:
        # Native summarizers may choose a smaller response budget. The shared
        # ceiling still applies; forcing a larger request changes their strategy.
        violations = [key for key in violations if key != 'max_output_tokens']
    model_input = {k: request[k] for k in ['instructions', 'input', 'messages', 'tools'] if k in request}
    size = len(json.dumps(model_input, ensure_ascii=False).encode())
    if policy.get('maxContextBytes') and size > policy['maxContextBytes']:
        violations.append('context_limit')
    # The complete business policy must reach the model, regardless of native wrappers.
    system_input = [request.get('instructions', '')] + [item.get('content', '')
        for item in request.get('input', request.get('messages', []))
        if isinstance(item, dict) and item.get('role') in {'system', 'developer'}]
    if system and not auxiliary and not any(system in text for text in strings(system_input)):
        violations.append('business_system_missing')
    prompts = []
    if request.get('instructions'):
        prompts.append({'role': 'instructions', 'sha256': digest(request['instructions'])})
    for item in request.get('input', request.get('messages', [])):
        if isinstance(item, dict) and item.get('role') in {'system', 'developer'}:
            prompts.append({'role': item['role'], 'sha256': digest(item.get('content'))})
    tools = []
    for item in request.get('tools', []):
        fn = item.get('function', item)
        name = fn.get('name', '')
        tools.append({'name': name, 'canonical_name': name.removeprefix('hands_'),
                      'parameters_sha256': schema_digest(fn.get('parameters', {})),
                      'description_sha256': digest(fn.get('description', ''))})
        if item.get('type') != 'function':
            violations.append('undeclared_builtin_tool')
    results = []
    for item in request.get('input', []):
        if not isinstance(item, dict) or item.get('type') != 'function_call_output':
            continue
        value = item.get('output')
        if isinstance(value, list) and len(value) == 1 and isinstance(value[0], dict) and value[0].get('type') in {'input_text', 'output_text', 'text'}:
            value = value[0].get('text')
        if isinstance(value, str):
            try:
                value = json.loads(value)
            except ValueError:
                pass
        results.append({'call_id': item.get('call_id'), 'result_sha256': digest(value)})
    history = [{'type': item.get('type', 'message'), 'role': item.get('role'),
                'sha256': digest(item), 'bytes': len(json.dumps(item, ensure_ascii=False).encode())}
               for item in request.get('input', request.get('messages', [])) if isinstance(item, dict)]
    return {'parameters': actual, 'request_sha256': digest(request), 'history_manifest': history,
            'business_system_sha256': digest(system), 'system_messages': prompts,
            'tools': tools, 'context_bytes': size, 'violations': violations,
            'request_class': 'native_auxiliary_no_tools' if auxiliary else 'reasoning',
            'tool_results': results}


class CaseStore:
    def __init__(self, path):
        self.path = str(path)
        self.locks = {slot: threading.RLock() for slot in ['tau', 'appworld', 'fixture', 'terminal']}
        with self.db() as db:
            db.execute('CREATE TABLE IF NOT EXISTS eval_case (trial TEXT PRIMARY KEY, slot TEXT NOT NULL, '
                       'target TEXT NOT NULL, owner TEXT NOT NULL, status TEXT NOT NULL, configuration TEXT NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS eval_request (id TEXT PRIMARY KEY, trial TEXT NOT NULL, '
                       'kind TEXT NOT NULL, started REAL NOT NULL, finished REAL, status TEXT NOT NULL, audit TEXT NOT NULL)')
            db.execute('CREATE UNIQUE INDEX IF NOT EXISTS active_eval_slot ON eval_case(slot) WHERE status="active"')
            db.execute('CREATE UNIQUE INDEX IF NOT EXISTS active_eval_target ON eval_case(target) WHERE status="active"')
            db.execute('CREATE TABLE IF NOT EXISTS eval_initial (input_key TEXT PRIMARY KEY, sha256 TEXT NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS eval_tools (input_key TEXT PRIMARY KEY, sha256 TEXT NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS fixture_calls (id INTEGER PRIMARY KEY AUTOINCREMENT, '
                       'trial TEXT NOT NULL, value TEXT NOT NULL, started REAL NOT NULL, finished REAL)')

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def start(self, body):
        for key in ['trial', 'slot', 'target', 'owner']:
            if not isinstance(body.get(key), str) or not body[key] or len(body[key]) > 200:
                raise ValueError('Invalid case identity')
        if body['slot'] not in self.locks:
            raise ValueError('Invalid slot')
        if body.get('fault') and (body['slot'] != 'fixture' or body['fault'] not in {'provider_429', 'disconnect', 'model_delay'}):
            raise ValueError('Fault injection is restricted to synthetic acceptance cases')
        with self.locks[body['slot']], self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            if db.execute('SELECT 1 FROM eval_case WHERE status<>"closed" AND (slot=? OR target=?)',
                          (body['slot'], body['target'])).fetchone():
                raise sqlite3.IntegrityError('An unfinished case owns this environment or target')
            if body.get('input_key') and body.get('initial_hash'):
                prior = db.execute('SELECT sha256 FROM eval_initial WHERE input_key=?', (body['input_key'],)).fetchone()
                if prior and prior[0] != body['initial_hash']:
                    raise ValueError('Initial database state differs from the frozen task')
                db.execute('INSERT OR IGNORE INTO eval_initial VALUES (?,?)', (body['input_key'], body['initial_hash']))
            db.execute('INSERT INTO eval_case VALUES (?,?,?,?,?,?)',
                       (body['trial'], body['slot'], body['target'], body['owner'], 'active', json.dumps(body)))
        return {'trial': body['trial'], 'status': 'active'}

    def interrupt_active(self):
        # AppWorld/tau state lives in companion processes. A process replacement
        # cannot prove that their in-memory state survived, even without pending I/O.
        with self.db() as db:
            return db.execute('UPDATE eval_case SET status="interrupted" WHERE status="active"').rowcount

    def owned(self, trial, owner):
        with self.db() as db:
            row = db.execute('SELECT * FROM eval_case WHERE trial=? AND owner=?', (trial, owner)).fetchone()
        if row is None:
            raise ValueError('Case owner mismatch')
        return dict(row)

    def active(self, target=None, trial=None):
        with self.db() as db:
            row = db.execute('SELECT * FROM eval_case WHERE status="active" AND ' +
                             ('target=?' if target is not None else 'trial=?'), (target or trial,)).fetchone()
        if row is None:
            raise ValueError('No active evaluation case')
        return dict(row)

    def check_tools(self, case, audit):
        configuration = json.loads(case['configuration'])
        key = configuration.get('input_key')
        if not key or audit.get('request_class') == 'native_auxiliary_no_tools':
            return
        namespace = configuration.get('declared_tool_namespace')
        if namespace:
            catalogs = [r['audit']['catalog_tools'] for r in self.export(case['trial'])
                        if r['status'] == 'succeeded' and 'catalog_tools' in r['audit']]
            expected = {namespace + '__' + tool['name']: tool for catalog in catalogs for tool in catalog}
            actual = [tool['canonical_name'] for tool in audit['tools']]
            if not catalogs or set(actual) != set(expected) or len(actual) != len(expected):
                audit['violations'].append('undeclared_or_missing_mcp_tool')
            for tool in audit['tools']:
                original = expected.get(tool['canonical_name'])
                if original and any(tool[key] != original[key] for key in
                                    ['parameters_sha256', 'description_sha256']):
                    audit['violations'].append('original_mcp_schema_changed')
        # Keep historical observations while versioning the stronger catalog contract.
        key = digest({'contract': 'mcp-original-schema-v4-numeric-values', 'input_key': key})
        tools = sorted([{k: v for k, v in tool.items() if k != 'name'} for tool in audit['tools']],
                       key=lambda tool: tool['canonical_name'])
        fingerprint = digest(tools)
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            old = db.execute('SELECT sha256 FROM eval_tools WHERE input_key=?', (key,)).fetchone()
            if old and old[0] != fingerprint:
                audit['violations'].append('effective_tool_catalog_drift')
            elif not old and not audit['violations']:
                db.execute('INSERT INTO eval_tools VALUES (?,?)', (key, fingerprint))

    def restore_tool_number_precision(self, case, request):
        configuration = json.loads(case['configuration'])
        namespace = configuration.get('declared_tool_namespace')
        if not namespace:
            return []
        original = {namespace + '__' + tool['name']: tool['parameters']
                    for row in self.export(case['trial']) if row['status'] == 'succeeded'
                    for tool in row['audit'].get('catalog_tools', []) if 'parameters' in tool}
        repairs = []
        for tool in request.get('tools', []):
            function = tool.get('function', tool)
            name = function.get('name', '').removeprefix('hands_')
            received = function.get('parameters', {})
            if name not in original:
                continue
            paths = schema_number_repairs(received, original[name])
            if paths:
                repairs.append({'tool': name, 'paths': paths,
                                'received_schema_sha256': schema_digest(received),
                                'forwarded_schema_sha256': schema_digest(original[name])})
                function['parameters'] = original[name]
        return repairs

    @contextmanager
    def invocation(self, case, kind, audit):
        # Tool requests are serialized through completion, including across cases.
        with self.locks[case['slot']]:
            current = self.active(trial=case['trial'])
            identity = str(uuid.uuid4())
            with self.db() as db:
                db.execute('BEGIN IMMEDIATE')
                if db.execute('SELECT 1 FROM eval_request WHERE trial=? AND status="pending"',
                              (case['trial'],)).fetchone():
                    raise ValueError('Unconfirmed prior execution; inspect before continuing')
                db.execute('INSERT INTO eval_request VALUES (?,?,?,?,NULL,?,?)',
                           (identity, current['trial'], kind, time.time(), 'pending', json.dumps(audit)))
            result = {'status': 'unknown', 'audit': audit}
            try:
                yield result
            finally:
                with self.db() as db:
                    db.execute('UPDATE eval_request SET finished=?,status=?,audit=? WHERE id=?',
                               (time.time(), result['status'] if result['status'] != 'unknown' else 'pending',
                                json.dumps(result['audit']), identity))

    def export(self, trial):
        with self.db() as db:
            rows = db.execute('SELECT id,kind,started,finished,status,audit FROM eval_request WHERE trial=? '
                              'ORDER BY started,id', (trial,)).fetchall()
        return [{**dict(row), 'audit': json.loads(row['audit'])} for row in rows]

    def finish(self, trial, owner):
        # Trusted recovery may close an interrupted case once all I/O is confirmed.
        case = self.owned(trial, owner)
        with self.locks[case['slot']], self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            if case['owner'] != owner:
                raise ValueError('Case owner mismatch')
            if db.execute('SELECT 1 FROM eval_request WHERE trial=? AND status="pending"', (trial,)).fetchone():
                raise ValueError('Unconfirmed execution prevents environment reuse')
            db.execute('UPDATE eval_case SET status="closed" WHERE trial=? AND owner=?', (trial, owner))
        return {'trial': trial, 'status': 'closed', 'requests': self.export(trial)}

    def settle_model_failure(self, trial, owner, request_id, terminal):
        """Trusted recovery of a finished legacy model transport; never settle tools.

        The Runner must read the exact terminal Turn first. Provider billing remains
        unknown; closing this transport cannot replay a model call or business tool.
        """
        case = self.owned(trial, owner)
        if not isinstance(terminal, dict) or terminal.get('status') not in {'failed', 'cancelled', 'completed'} or \
                not all(isinstance(terminal.get(k), str) and terminal[k] for k in ['session', 'turn']):
            raise ValueError('Confirmed terminal Turn is required')
        with self.locks[case['slot']], self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT * FROM eval_request WHERE id=? AND trial=?', (request_id, trial)).fetchone()
            if row is None or row['kind'] != 'model' or row['status'] != 'pending' or row['finished'] is None:
                raise ValueError('Only a finished pending model transport may be settled')
            audit = json.loads(row['audit'])
            audit.update(usage_unknown=True, transport_recovery={'terminal': terminal, 'settled_at': time.time(),
                         'reason': 'legacy_finished_model_transport_outcome_unknown'})
            db.execute('UPDATE eval_request SET status=?,audit=? WHERE id=?',
                       ('provider_error', json.dumps(audit), request_id))
        return {'request_id': request_id, 'status': 'provider_error', 'usage_unknown': True}


def make_handler(store, leases, policy, upstream, appworld, control_key, tool_key,
                 tau='http://127.0.0.1:8000/mcp', terminal=None):
    source_sha256 = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    class Handler(BaseHTTPRequestHandler):
        # MCP sends initialization, notifications and discovery on pooled connections.
        # Every ordinary response is length-delimited; provider SSE explicitly closes.
        protocol_version = 'HTTP/1.1'

        def log_message(self, *_args):
            pass

        def reply(self, status, body, content_type='application/json', headers=None):
            if not isinstance(body, bytes):
                body = json.dumps(body).encode()
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(body)))
            for key, value in (headers or {}).items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == '/healthz':
                return self.reply(200, {'status': 'ok', 'policy_sha256': digest(policy), 'source_sha256': source_sha256})
            # MCP clients may open an optional SSE channel during initialization.
            # An empty 405 declines it without feeding a JSON error into an SSE parser.
            self.reply(405, b'', headers={'Allow': 'POST, DELETE'})

        def do_DELETE(self):
            self.reply(200, {})  # Stateless MCP session cleanup has no environment side effect.

        def do_POST(self):
            try:
                size = int(self.headers.get('Content-Length', '0'))
                if not 0 < size <= MAX_BODY:
                    raise ValueError('Invalid body size')
                body = self.rfile.read(size)
                request = json.loads(body)
                if self.path.startswith('/control/'):
                    if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + control_key):
                        return self.reply(403, {'error': 'forbidden'})
                    leases.operate('check', request['slot'], request['owner'])
                    if self.path == '/control/start':
                        result = store.start(request)
                    elif self.path == '/control/finish':
                        result = store.finish(request['trial'], request['owner'])
                    elif self.path == '/control/audit':
                        case = store.owned(request['trial'], request['owner'])
                        result = {'status': case['status'], 'requests': store.export(request['trial'])}
                    elif self.path == '/control/settle-model-failure':
                        result = store.settle_model_failure(request['trial'], request['owner'],
                                                           request['request_id'], request['terminal'])
                    elif self.path == '/control/fixture-state':
                        # Recovery must inspect a fenced case after an environment restart.
                        case = store.owned(request['trial'], request['owner'])
                        if case['slot'] != 'fixture' or case['owner'] != request['owner']:
                            raise ValueError('Not a fixture owner')
                        with store.db() as db:
                            result = {'calls': [dict(row) for row in db.execute(
                                'SELECT id,started,finished FROM fixture_calls WHERE trial=? ORDER BY id',
                                (request['trial'],)).fetchall()]}
                    elif self.path == '/control/fixture-fault':
                        case = store.active(trial=request['trial'])
                        if case['slot'] != 'fixture' or case['owner'] != request['owner'] or \
                                request.get('fault') not in {'provider_429', 'disconnect', 'model_delay'} or store.export(case['trial']):
                            raise ValueError('Fault injection must precede synthetic execution')
                        configuration = json.loads(case['configuration'])
                        configuration['fault'] = request['fault']
                        with store.db() as db:
                            db.execute('UPDATE eval_case SET configuration=? WHERE trial=?',
                                       (json.dumps(configuration), case['trial']))
                        result = {'status': 'armed'}
                    else:
                        raise ValueError('Unknown control operation')
                    return self.reply(200, result)
                parts = self.path.strip('/').split('/')
                if len(parts) == 4 and parts[0] == 'models' and parts[2:] == ['v1', 'responses']:
                    case = store.active(target=parts[1])
                    config = json.loads(case['configuration'])
                    incoming_sha256 = digest(request)
                    repairs = store.restore_tool_number_precision(case, request)
                    if repairs:
                        # Official catalog numbers remain exact on the provider wire,
                        # even when a JavaScript SDK cannot represent a large integer.
                        body = json.dumps(request, ensure_ascii=False, separators=(',', ':')).encode()
                    audit = request_audit(request, policy, config.get('system', ''))
                    if repairs:
                        audit.update(incoming_request_sha256=incoming_sha256, schema_numeric_restorations=repairs)
                    store.check_tools(case, audit)
                    with store.invocation(case, 'model', audit) as record:
                        if audit['violations']:
                            record['status'] = 'rejected'
                            return self.reply(422, {'error': {'code': 'evaluation_contract',
                                'message': ','.join(audit['violations'])}})
                        if config.get('fault') == 'model_delay':
                            audit['injected_model_delay_seconds'] = 75
                            # Synthetic acceptance only: keep the native model request
                            # open across multiple SQL renewals and legacy repair scans.
                            time.sleep(75)
                        elif config.get('fault'):
                            audit['injected_fault'] = config['fault']
                            record['status'] = 'provider_error'
                            if config['fault'] == 'provider_429':
                                return self.reply(429, {'error': {'code': 'rate_limit_exceeded', 'message': 'Synthetic acceptance fault'}})
                            # A valid stream prefix followed by EOF, without a completion event.
                            return self.reply(200, b'data: {"type":"response.created","response":{"id":"fixture","status":"in_progress","object":"response","output":[]}}\n\n', 'text/event-stream')
                        headers = {'Authorization': self.headers.get('Authorization', ''),
                                   'Content-Type': 'application/json'}
                        target = upstream.rstrip('/') + '/responses'
                        def begin(code, content_type):
                            self.send_response(code)
                            self.send_header('Content-Type', content_type)
                            self.send_header('Connection', 'close')
                            self.end_headers()
                            self.close_connection = True
                            self.response_started = True
                        def emit(chunk):
                            self.wfile.write(chunk)
                            self.wfile.flush()
                        try:
                            record['status'] = stream_model(target, body, headers, policy['modelTimeoutSeconds'],
                                                            begin, emit, audit)
                        except (TimeoutError, OSError, http.client.HTTPException):
                            record['status'] = 'provider_error' if audit.get('failure_stage', '').startswith('upstream_') else 'failed'
                            raise
                        return
                if len(parts) == 3 and parts[0] in {'appworld', 'tau', 'fixture', 'terminal'} and parts[2] == 'mcp':
                    if parts[0] == 'terminal' and not terminal:
                        return self.reply(404, {'error': 'terminal_backend_not_configured'})
                    if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + tool_key):
                        return self.reply(403, {'error': 'forbidden'})
                    case = store.active(trial=parts[1])
                    if case['slot'] != parts[0]:
                        raise ValueError('Wrong environment')
                    params = request.get('params', {})
                    audit = {'method': request.get('method'), 'name': params.get('name'),
                             'arguments_sha256': digest(params.get('arguments')), 'meta': {
                                 k: v for k, v in params.get('_meta', {}).items()
                                 if k in {'io.agentscope/toolCallId', 'io.agentscope/execution'}}}
                    with store.invocation(case, 'tool' if request.get('method') == 'tools/call' else 'mcp', audit) as record:
                        headers = {'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream',
                                   'Authorization': 'Bearer ' + tool_key, 'X-Eval-Trial': case['trial']}
                        for key in ['Mcp-Session-Id', 'MCP-Protocol-Version']:
                            if self.headers.get(key):
                                headers[key] = self.headers[key]
                        tool_upstream = {'appworld': appworld, 'tau': tau, 'fixture': 'http://127.0.0.1:8003/mcp',
                                         'terminal': terminal}[parts[0]]
                        code, response, content_type, response_headers = self.forward(tool_upstream,
                            body, headers, policy['toolTimeoutSeconds'])
                        audit.update(http_status=code, response_sha256=hashlib.sha256(response).hexdigest(), response_bytes=len(response))
                        if response and 200 <= code < 300:
                            parsed = rpc_result(response)
                            if request.get('method') == 'tools/call':
                                audit['result_sha256'] = digest(parsed.get('result'))
                            if request.get('method') == 'tools/list':
                                audit['catalog_sha256'] = digest(parsed.get('result', {}).get('tools', []))
                                audit['catalog_tool_names'] = [tool['name'] for tool in parsed.get('result', {}).get('tools', [])]
                                audit['catalog_tools'] = [
                                    {'name': tool['name'], 'parameters_sha256': schema_digest(tool.get('inputSchema', {})),
                                     'parameters': tool.get('inputSchema', {}),
                                     'description_sha256': digest(tool.get('description', ''))}
                                    for tool in parsed.get('result', {}).get('tools', [])]
                            if request.get('method') == 'tools/call' and len(response) > policy['maxToolResultBytes']:
                                # Preserve the original outcome hash; never silently clip JSON/text.
                                parsed = {'jsonrpc': '2.0', 'id': request.get('id'), 'result': {
                                    'isError': True, 'content': [{'type': 'text', 'text': json.dumps({
                                        'code': 'tool_output_limit', 'bytes': len(response),
                                        'sha256': audit['response_sha256'], 'limit': policy['maxToolResultBytes']})}]}}
                                response, content_type = json.dumps(parsed).encode(), 'application/json'
                                audit['overflow'] = True
                            if request.get('method') == 'tools/call':
                                audit['delivered_result_sha256'] = digest(parsed.get('result'))
                        record['status'] = 'succeeded' if 200 <= code < 300 else 'failed'
                        return self.reply(code, response, content_type, response_headers)
                self.reply(404, {'error': 'not_found'})
            except (ValueError, sqlite3.IntegrityError):
                if getattr(self, 'response_started', False):
                    self.close_connection = True
                    return
                self.reply(409, {'error': 'evaluation_guard_rejected'})
            except (urllib.error.URLError, TimeoutError, OSError, http.client.HTTPException):
                # Tool outcomes remain pending. Model transport failures preserve
                # unknown usage, but a closed model socket is not a pending tool.
                if getattr(self, 'response_started', False):
                    self.close_connection = True
                    return
                try:
                    self.reply(502, {'error': 'upstream_outcome_unknown'})
                except OSError:
                    pass

        @staticmethod
        def forward(url, body, headers, timeout):
            class NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self, *_args, **_kwargs):
                    return None
            req = urllib.request.Request(url, body, headers)
            try:
                response = urllib.request.build_opener(NoRedirect).open(req, timeout=timeout)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                data = response.read(MAX_BODY + 1)
                if len(data) > MAX_BODY:
                    raise ValueError('Upstream response too large')
                return response.status, data, response.headers.get('Content-Type', 'application/json'), {
                    key: response.headers[key] for key in ['Mcp-Session-Id'] if key in response.headers}
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', required=True)
    parser.add_argument('--leases', required=True)
    parser.add_argument('--profile', required=True)
    parser.add_argument('--upstream', required=True)
    parser.add_argument('--appworld', default='http://127.0.0.1:10000/mcp/')
    parser.add_argument('--terminal', help='Fixed terminal MCP backend, disabled when omitted')
    parser.add_argument('--port', type=int, default=8002)
    args = parser.parse_args()
    if urlsplit(args.upstream).scheme != 'https' or urlsplit(args.upstream).hostname != 'dashscope.aliyuncs.com':
        raise ValueError('Evaluation upstream must be the declared DashScope HTTPS endpoint')
    os.umask(0o077)
    policy = json.loads(Path(args.profile).read_text())['gateway']
    store = CaseStore(args.state)
    store.interrupt_active()
    handler = make_handler(store, EnvironmentLeases(args.leases), policy,
                           args.upstream, args.appworld, os.environ['EVAL_CONTROL_KEY'], os.environ['EVAL_TOOL_KEY'],
                           terminal=args.terminal)
    ThreadingHTTPServer(('0.0.0.0', args.port), handler).serve_forever()


if __name__ == '__main__':
    main()
