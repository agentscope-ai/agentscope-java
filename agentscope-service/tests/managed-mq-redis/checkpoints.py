#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Historical native restore/Fork against owned real services and actual Sandbox files."""
import argparse
import hashlib
import json
from pathlib import Path
import signal
import subprocess
import sys
import time
import traceback
import uuid

import replicas

sys.path.insert(0, str(replicas.SERVICE / 'service-controlplane/sdk/python'))
from agentscope_service import ServiceClient


def wait_for(check, timeout=600):
    deadline = time.monotonic() + timeout
    while True:
        value = check()
        if value:
            return value
        assert time.monotonic() < deadline, 'Checkpoint observation deadline exceeded'
        time.sleep(.25)


def run(state):
    manifest = json.loads((state / 'manifest.json').read_text())
    output = state / f'checkpoints-{time.time_ns()}.json'
    record = {'passed': False, 'model': manifest['model'], 'sandbox': manifest['sandbox'],
              'mq_profile': manifest['mq_profile'], 'mq_test_only_derivative': manifest['mq_test_only_derivative'],
              'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'sessions': {}, 'turns': [], 'operations': []}
    client = ServiceClient('http://127.0.0.1:19084').managed_sessions()
    other = ServiceClient('http://127.0.0.1:19184').managed_sessions()

    def save():
        output.write_text(json.dumps(record, indent=2) + '\n')

    def phase(value):
        record['phase'] = value
        save()
        print('CHECKPOINT PHASE', value, flush=True)

    def session(name, agent):
        value = client.create_session({'agent': agent}, idempotency_key=uuid.uuid4().hex)['id']
        record['sessions'][name] = value
        save()
        return value

    def turn(session_id, action, version):
        phase(f'{session_id}:{action}:{version}')
        value = client.submit(session_id, message=f'fixture:checkpoint:{action}:{version}:{record["label"]}',
                              idempotency_key=uuid.uuid4().hex)
        record['turns'].append({'session': session_id, 'id': value['id'], 'action': action, 'version': version})
        save()
        replicas.wait_turn('/v1/sessions/' + session_id, value['id'], {'completed'}, timeout=600)
        history = events(session_id)
        assert any(f'brain-checkpoint-{action}-{version}-{record["label"]}' in json.dumps(event)
                   for event in history), history
        return value['id']

    def events(session_id):
        values, after = [], ''
        for _ in range(128):
            page = other.events(session_id, after=after, limit=256)
            values.extend(page['data'])
            if not page['has_more']:
                return values
            after = page['next_cursor']
        raise AssertionError('Fixture exceeded its public event audit bound')

    def checkpoint(session_id, turn_id):
        values, after, through = [], '', ''
        for _ in range(128):
            page = other.checkpoints(session_id, after=after, through=through, limit=100)
            values.extend(page['data'])
            through = page['as_of']
            if not page['has_more']:
                break
            after = page['next_cursor']
        else:
            raise AssertionError('Fixture exceeded its checkpoint page bound')
        selected = [value for value in values if value.get('turn_id') == turn_id][-1]
        assert 'stateJson' not in json.dumps(selected)
        return selected

    def operation(source, target, selected, expected='completed'):
        phase(f'{"restore" if source == target else "fork"}:{source}:{target}:{selected["checkpoint_id"]}')
        key = uuid.uuid4().hex
        if source == target:
            first = client.restore_checkpoint(source, selected['checkpoint_id'], idempotency_key=key)
            second = other.restore_checkpoint(source, selected['checkpoint_id'], idempotency_key=key)
        else:
            first = client.fork_checkpoint(source, target, selected['checkpoint_id'], idempotency_key=key)
            second = other.fork_checkpoint(source, target, selected['checkpoint_id'], idempotency_key=key)
        assert first['operation_id'] == second['operation_id'], (first, second)
        item = {'source': source, 'target': target, 'checkpoint': selected['checkpoint_id'],
                'key': key, 'id': first['operation_id'], 'accepted': first, 'expected': expected}
        record['operations'].append(item)
        save()

        def done():
            receipt = other.checkpoint_operation(source, first['operation_id'])
            item['latest'] = receipt
            save()
            if receipt['status'] in {'completed', 'rejected', 'cancelled'}:
                assert receipt['status'] == expected, receipt
                assert receipt['source_session_id'] == source and receipt['session_id'] == target, receipt
                return receipt
            return None
        return wait_for(done)

    def restart(name):
        old = manifest['processes'][name]['pid']
        replicas.signal_replica(state, name, signal.SIGKILL)
        wait_for(lambda: str(state) not in subprocess.run(['ps', '-p', str(old), '-o', 'command='],
                 capture_output=True, text=True).stdout, 30)
        return {'old': old, 'new': replicas.restart_replica(state, name)}

    save()
    try:
        environment, agent, label = replicas.fixture_agent(max_iters=8)
        record.update(environment=environment['id'], agent=agent['id'], label=label)
        origin = session('source', agent['id'])
        record['initial_capabilities'] = client.capabilities(origin)
        binary = bytes((n * 31) % 256 for n in range(256)) * 8192
        uploaded = client.upload_file(origin, 'checkpoint-input.bin', binary, idempotency_key='original-' + label)
        record['file'] = uploaded
        record['file_sha256'] = hashlib.sha256(binary).hexdigest()
        save()
        first_turn = turn(origin, 'write', 'v1')
        first = checkpoint(origin, first_turn)
        assert first['file_count'] == 1, first
        record['first_checkpoint'] = first
        target = session('first_fork', agent['id'])
        operation(origin, target, first)
        assert other.checkpoint(origin, first['checkpoint_id'])['workspace_available']
        assert client.file_content(target, uploaded['file_id']) == binary
        assert len(client.files(target)['data']) == 1
        late = client.upload_file(origin, 'after-checkpoint.txt', b'after-selected-checkpoint', idempotency_key='late-' + label)
        record['late_file'] = late
        second_turn = turn(origin, 'write', 'v2')
        second = checkpoint(origin, second_turn)
        record['second_checkpoint'] = second
        record['artifact'] = client.publish_artifact(origin, {'name': 'Source-owned publication',
                'uri': 'https://fixture.example/source-only'}, idempotency_key='artifact-' + label)
        phase('replace-both-brains-and-both-hands')
        record['replacements'] = {name: restart(name) for name in ['brain-a', 'brain-b', 'hands-a', 'hands-b']}
        save()
        turn(target, 'read', 'v1')
        turn(target, 'write', 'fork')
        historical = session('historical_fork', agent['id'])
        operation(origin, historical, first)
        turn(historical, 'read', 'v1')
        assert client.file_content(historical, uploaded['file_id']) == binary
        assert [value['file_id'] for value in client.files(historical)['data']] == [uploaded['file_id']]
        assert other.artifacts(historical)['data'] == []
        turn(origin, 'read', 'v2')
        unavailable = session('unavailable_history_target', agent['id'])
        rejected = operation(origin, unavailable, second, expected='rejected')
        assert rejected['error']['code'] == 'checkpoint_workspace_unavailable', rejected
        assert other.files(unavailable)['data'] == []
        operation(origin, origin, first)
        turn(origin, 'read', 'v1')
        # Uploaded resources stay immutable assets; execution restoration does not delete later uploads.
        assert other.file_content(origin, late['file_id']) == b'after-selected-checkpoint'
        assert other.artifact_metadata(origin, record['artifact']['artifact_id'])['artifact_id'] == record['artifact']['artifact_id']
        assert other.file_content(target, uploaded['file_id']) == binary
        record['histories'] = {name: events(session_id) for name, session_id in record['sessions'].items()}
        record['final_operations'] = [other.checkpoint_operation(item['source'], item['id']) for item in record['operations']]
        record['passed'] = True
        phase('complete')
        print('PASSED native historical workspace/context/files restore and independent Fork:', output, flush=True)
    except BaseException:
        record['error'] = traceback.format_exc()
        raise
    finally:
        save()
        replicas.stop(state)
        deadline = time.monotonic() + 90
        while True:
            current = json.loads((state / 'manifest.json').read_text())
            stopped = {name: {'pid': value['pid'], 'stopped': str(state) not in subprocess.run(
                ['ps', '-p', str(value['pid']), '-o', 'command='], capture_output=True, text=True).stdout}
                for name, value in current['processes'].items()}
            if all(value['stopped'] for value in stopped.values()):
                break
            assert time.monotonic() < deadline, stopped
            time.sleep(.25)
        (state / 'stopped.json').write_text(json.dumps(stopped, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', type=Path, required=True)
    run(parser.parse_args().state.resolve())
