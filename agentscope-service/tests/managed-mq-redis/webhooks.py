#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Native webhook delivery, lost receipt, worker replacement and idle refill on owned replicas."""
import argparse
from collections import Counter
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.error
import urllib.request
import uuid

import replicas

sys.path.insert(0, str(replicas.SERVICE / 'control-plane/sdk/python'))
from managed_agents import ServiceClient

TOKEN = 'managed-replica-fixture-internal-token-2026'


def read_body(handler):
    if handler.headers.get('Transfer-Encoding', '').lower() != 'chunked':
        size = int(handler.headers.get('Content-Length', '0'))
        assert 0 <= size <= 1048576
        return handler.rfile.read(size)
    chunks = []
    total = 0
    while True:
        size = int(handler.rfile.readline().split(b';', 1)[0], 16)
        if size == 0:
            while handler.rfile.readline() not in {b'\r\n', b'\n', b''}:
                pass
            return b''.join(chunks)
        total += size
        assert total <= 1048576
        chunks.append(handler.rfile.read(size))
        assert handler.rfile.read(2) == b'\r\n'


def wait_for(check, seconds=180):
    deadline = time.monotonic() + seconds
    while True:
        value = check()
        if value:
            return value
        if time.monotonic() >= deadline:
            raise AssertionError('Acceptance observation deadline exceeded')
        time.sleep(.25)


def run(state):
    manifest_path = state / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    assert manifest['webhooks'], 'Start a new fixture with --webhooks'
    output = state / f'webhooks-{time.time_ns()}.json'
    record = {'passed': False, 'model': manifest['model'], 'sandbox': manifest['sandbox'],
              'mq_profile': manifest['mq_profile'], 'mq_test_only_derivative': manifest['mq_test_only_derivative'],
              'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'notifications': [], 'dropped_receipts': [], 'receiver_side_effects': []}
    lock = threading.RLock()
    errors = []
    servers = []
    workers = {}
    secret = None
    dropped = threading.Event()

    def save():
        with lock:
            output.write_text(json.dumps(record, indent=2) + '\n')

    def internal(path, value, port=19084):
        request = urllib.request.Request(f'http://127.0.0.1:{port}/api/internal/runtime/webhooks/{path}',
                                         json.dumps(value).encode(), {'Content-Type': 'application/json',
                                         'X-Managed-Agents-Internal-Token': TOKEN})
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.load(response)

    class Receiver(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            try:
                raw = read_body(self)
                timestamp = self.headers['X-Managed-Agents-Timestamp']
                expected = 'v1=' + hmac.new(secret.encode(), timestamp.encode() + b'.' + raw, hashlib.sha256).hexdigest()
                assert hmac.compare_digest(expected, self.headers['X-Managed-Agents-Signature'])
                assert abs(time.time() - int(timestamp)) < 60
                payload = json.loads(raw)
                assert set(payload) == {'id', 'type', 'session_id', 'cursor', 'created_at', 'event_url'}
                delivery = self.headers['X-Managed-Agents-Delivery']
                with lock:
                    if delivery not in record['receiver_side_effects']:
                        record['receiver_side_effects'].append(delivery)
                    record['notifications'].append({'delivery': delivery, 'payload': payload,
                        'raw_sha256': hashlib.sha256(raw).hexdigest(), 'signature_verified': True,
                        'received_at': time.time()})
                    save()
                self.send_response(204)
                self.end_headers()
            except BaseException:
                errors.append(traceback.format_exc())
                record['server_errors'] = list(errors)
                save()
                self.send_response(400)
                self.end_headers()

    class ReceiptDropProxy(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            try:
                raw = read_body(self)
                if self.path.endswith('/receipts'):
                    with lock:
                        if not dropped.is_set():
                            record['dropped_receipts'].append(json.loads(raw))
                        record['dropped_receipt_requests'] = record.get('dropped_receipt_requests', 0) + 1
                        save()
                    dropped.set()
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()
                    return  # Receiver accepted, but no receipt reached Agent Service.
                request = urllib.request.Request('http://127.0.0.1:19084' + self.path, raw,
                    {'Content-Type': 'application/json', 'X-Managed-Agents-Internal-Token': TOKEN})
                try:
                    with urllib.request.urlopen(request, timeout=10) as response:
                        status, body = response.status, response.read()
                except urllib.error.HTTPError as response:
                    status, body = response.code, response.read()
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except BaseException:
                errors.append(traceback.format_exc())
                record['server_errors'] = list(errors)
                save()
                self.close_connection = True

    def server(handler, port):
        instance = ThreadingHTTPServer(('127.0.0.1', port), handler)
        threading.Thread(target=instance.serve_forever, daemon=True).start()
        servers.append(instance)

    def start_worker(name, port):
        command = [str(state / 'webhook-worker-fixture'), '-test.run=^TestWebhookReplicaFixture$', '-test.timeout=0']
        environment = {**os.environ, 'MANAGED_AGENTS_WEBHOOK_REPLICA_FIXTURE': '1',
                       'MANAGED_AGENTS_AGENT_SERVICE_URL': f'http://127.0.0.1:{port}',
                       'MANAGED_AGENTS_INTERNAL_TOKEN': TOKEN, 'MANAGED_AGENTS_WEBHOOK_SLOTS': '2',
                       'MANAGED_AGENTS_WEBHOOK_ALLOW_LOOPBACK_HTTP': 'true'}
        log = state / (name + '.log')
        with log.open('ab') as stream:
            process = subprocess.Popen(command, cwd=replicas.ROOT, env=environment, stdout=stream, stderr=subprocess.STDOUT)
        workers[name] = process
        manifest['processes'][name] = {'pid': process.pid, 'command': command}
        manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')

        def ready():
            assert process.poll() is None, log.read_text()[-4000:]
            return 'READY production webhook worker' in log.read_text()
        wait_for(ready, 30)
        replicas.signal_replica(state, name, 0)

    save()
    try:
        subprocess.run(['go', 'test', '-c', '-o', str(state / 'webhook-worker-fixture'), './internal/product'],
                       cwd=replicas.SERVICE / 'control-plane', check=True)
        record['worker_binary_sha256'] = hashlib.sha256((state / 'webhook-worker-fixture').read_bytes()).hexdigest()
        server(Receiver, 19382)
        server(ReceiptDropProxy, 19383)
        _, agent, identity = replicas.fixture_agent()
        client = ServiceClient('http://127.0.0.1:19084').managed_sessions()
        session = client.create_session({'agent': agent['id']}, idempotency_key=uuid.uuid4().hex)['id']
        subscription = client.webhook(session, 'http://127.0.0.1:19382/notify',
            event_types=['turn.completed', 'artifact.published'], idempotency_key='webhook-' + identity)
        secret = subscription.pop('signing_secret')
        record.update(session=session, webhook=subscription, agent=agent['id'])
        save()
        start_worker('webhook-a', 19383)
        turns = [client.submit(session, message='fixture:echo:webhook-' + identity,
                    idempotency_key='first-' + identity),
                 client.submit(session, message='fixture:echo:webhook-next-' + identity,
                    idempotency_key='second-' + identity)]
        record['turns'] = [turn['id'] for turn in turns]
        save()
        def receipt_dropped():
            assert not errors, errors
            return dropped.is_set()
        wait_for(receipt_dropped, 240)
        assert not errors, errors
        replicas.signal_replica(state, 'webhook-a', signal.SIGKILL)
        workers['webhook-a'].wait(timeout=10)
        pending = json.loads(replicas.fact(state, """
            SELECT json_build_object('event',pending_event,'sequence',pending_seq,'cursor',cursor_seq,
                'fence',claim_fence,'until',claim_until) FROM portal.builder_portal_webhook WHERE id=:'id';
            """, {'id': subscription['id']}))
        assert pending['cursor'] < pending['sequence'] and pending['until'] > time.time() * 1000, pending
        record['pending_after_lost_receipt'] = pending
        save()
        start_worker('webhook-b', 19184)
        start_worker('webhook-c', 19084)
        for turn in turns:
            replicas.wait_turn('/v1/sessions/' + session, turn['id'], {'completed'}, timeout=240)

        def delivered(count):
            assert not errors, errors
            rows = client.webhook_deliveries(session, subscription['id'])['data']
            return rows if len(rows) == count and all(row['status'] == 'delivered' for row in rows) else None
        receipts = wait_for(lambda: delivered(2), 180)
        first = next(row for row in receipts if row['event_id'] == pending['event'])
        assert first['attempts'] == 2, receipts
        notifications = record['notifications']
        matches = [item for item in notifications if item['payload']['id'] == pending['event']]
        assert len(matches) == 2 and matches[0]['delivery'] == matches[1]['delivery'], matches
        assert matches[0]['payload'] == matches[1]['payload'], matches
        unique = list(dict.fromkeys(item['payload']['id'] for item in notifications))
        history = client.events(session, limit=256)['data']
        expected = [event for event in history if event['type'] == 'turn.completed']
        assert unique == [event['id'] for event in expected], (unique, expected)
        for item in notifications:
            page = replicas.request(19184, item['payload']['event_url'])
            assert len(page['data']) == 1 and page['data'][0]['id'] == item['payload']['id'], page
            assert page['data'][0]['cursor'] == item['payload']['cursor'], page
        for action, value in [('validate', record['dropped_receipts'][0]['proof']),
                              ('receipts', record['dropped_receipts'][0])]:
            try:
                internal(action, value, 19184)
            except urllib.error.HTTPError as error:
                assert error.code == 409, error
            else:
                raise AssertionError('Stale worker proof was accepted')
        wait_for(lambda: client.webhooks(session)['data'][0]['status'] == 'idle', 30)
        artifact = client.publish_artifact(session, {'name': 'Webhook idle refill',
            'uri': 'https://fixture.example/deliverable'}, idempotency_key='artifact-' + identity)
        record['artifact'] = artifact
        save()
        receipts = wait_for(lambda: delivered(3), 30)
        artifact_notification = next(item for item in record['notifications'] if item['payload']['type'] == 'artifact.published')
        assert len(artifact_notification['payload']['id']) > 64, artifact_notification
        page = replicas.request(19084, artifact_notification['payload']['event_url'])
        assert page['data'][0]['id'] == artifact_notification['payload']['id']
        assert len(record['notifications']) == 4 and len(record['receiver_side_effects']) == 3
        assert Counter(item['delivery'] for item in record['notifications'])[first['id']] == 2
        client.delete_webhook(session, subscription['id'])
        assert client.webhooks(session)['data'][0]['status'] == 'deleted'
        record['receipts'] = receipts
        record['passed'] = True
        print('PASSED native webhook signatures, lost receipt, replacement, FIFO and idle refill:', output, flush=True)
    except BaseException:
        record['error'] = traceback.format_exc()
        raise
    finally:
        save()
        for instance in servers:
            instance.shutdown()
            instance.server_close()
        replicas.stop(state)
        deadline = time.monotonic() + 60
        while True:
            stopped = {name: {'pid': value['pid'], 'stopped': str(state) not in subprocess.run(
                ['ps', '-p', str(value['pid']), '-o', 'command='], capture_output=True, text=True).stdout}
                for name, value in manifest['processes'].items()}
            if all(value['stopped'] for value in stopped.values()):
                break
            assert time.monotonic() < deadline, stopped
            time.sleep(.25)
        (state / 'stopped.json').write_text(json.dumps(stopped, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', type=Path, required=True)
    run(parser.parse_args().state.resolve())
