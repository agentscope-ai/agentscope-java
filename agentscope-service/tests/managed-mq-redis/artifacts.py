#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Verify generated Sandbox artifacts through real replicas and the native Python SDK."""
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


def run(state):
    manifest = json.loads((state / 'manifest.json').read_text())
    output = state / f'artifacts-{time.time_ns()}.json'
    record = {'passed': False, 'model': manifest['model'], 'sandbox': manifest['sandbox'],
              'mq_profile': manifest['mq_profile'], 'mq_test_only_derivative': manifest['mq_test_only_derivative'],
              'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    client = ServiceClient('http://127.0.0.1:19084').managed_sessions()
    other = ServiceClient('http://127.0.0.1:19184').managed_sessions()

    def save(phase):
        record['phase'] = phase
        output.write_text(json.dumps(record, indent=2) + '\n')
        print('ARTIFACT PHASE', phase, flush=True)

    def events(session):
        values, after = [], ''
        for _ in range(128):
            page = other.events(session, after=after, limit=256)
            values.extend(page['data'])
            if not page['has_more']:
                return values
            after = page['next_cursor']
        raise AssertionError('Artifact fixture exceeded its event audit bound')

    def restart(name):
        current = json.loads((state / 'manifest.json').read_text())
        old = current['processes'][name]['pid']
        replicas.signal_replica(state, name, signal.SIGKILL)
        deadline = time.monotonic() + 30
        while str(state) in subprocess.run(['ps', '-p', str(old), '-o', 'command='], capture_output=True, text=True).stdout:
            assert time.monotonic() < deadline, f'{name} did not stop'
            time.sleep(.1)
        return {'old': old, 'new': replicas.restart_replica(state, name)}

    save('create')
    try:
        environment, agent, label = replicas.fixture_agent(max_iters=8, extra_tools=('publish_artifact',))
        session = client.create_session({'agent': agent['id']}, idempotency_key=uuid.uuid4().hex)['id']
        record.update(environment=environment['id'], agent=agent['id'], session=session, label=label)
        first = client.submit(session, message='fixture:artifact:' + label, idempotency_key='publish-' + label)
        second = other.submit(session, message='fixture:echo:artifact-successor-' + label, idempotency_key='next-' + label)
        record['turns'] = [first, second]
        save('wait-for-archived-tool-and-successor')
        replicas.wait_turn('/v1/sessions/' + session, first['id'], {'completed'}, timeout=600)
        replicas.wait_turn('/v1/sessions/' + session, second['id'], {'completed'}, timeout=600)
        history = events(session)
        published = [event for event in history if event['type'] == 'artifact.published']
        assert len(published) == 1, published
        artifact = published[0]['data']
        expected = bytes((n + 19) % 256 for n in range(256)) * 8192
        digest = hashlib.sha256(expected).hexdigest()
        assert artifact['turn_id'] == first['id'] and artifact['sha256'] == digest, artifact
        assert artifact['size'] == len(expected) and artifact['name'] == 'report.bin', artifact
        assert 'brain-artifact-confirmed-' + label in json.dumps(history), history
        assert 'brain-artifact-successor-' + label in json.dumps(history), history
        first_completed = next(index for index, event in enumerate(history)
                               if event['type'] == 'turn.completed' and event['data'].get('turn_id') == first['id'])
        next_running = next(index for index, event in enumerate(history)
                            if event['type'] == 'turn.running' and event['data'].get('turn_id') == second['id'])
        assert first_completed < next_running, 'Successor advanced before original Turn completion'
        assert all('"content"' not in json.dumps(event) or 'base64' not in json.dumps(event) for event in history)
        assert other.artifact_content(session, artifact['artifact_id']) == expected
        assert other.file_content(session, artifact['file_id']) == expected
        assert len(other.artifacts(session)['data']) == 1
        assert len(other.files(session)['data']) == 1
        scope = replicas.fact(state, "SELECT scope_key FROM ma.session WHERE session_id=:'session';", {'session': session})
        workspace = state / 'sandbox' / scope
        assert (workspace / 'output/report.bin').read_bytes() == b'changed'
        assert (workspace / 'artifact-effects.txt').read_text().splitlines() == ['create-' + label, 'change-' + label]
        record.update(artifact=artifact, sha256=digest, events=history,
                      checkpoints=replicas.checkpoint_evidence(state, session))
        save('replace-brains-and-hands')
        record['replacements'] = {name: restart(name) for name in ['brain-a', 'brain-b', 'hands-a', 'hands-b']}
        assert client.artifact_content(session, artifact['artifact_id']) == expected
        assert other.artifact_metadata(session, artifact['artifact_id']) == artifact
        assert len([event for event in events(session) if event['type'] == 'artifact.published']) == 1
        facts = json.loads(replicas.fact(state, """
            SELECT json_build_object(
              'artifacts',(SELECT count(*) FROM ma.artifact WHERE scope_key=:'scope'),
              'files',(SELECT count(*) FROM ma.file WHERE scope_key=:'scope'),
              'exports',(SELECT count(*) FROM ma.tool_task WHERE scope_key=:'scope' AND tool_name='artifact.publish' AND status='succeeded'),
              'unfinished_tools',(SELECT count(*) FROM ma.tool_task WHERE scope_key=:'scope' AND status NOT IN ('succeeded','failed','cancelled')),
              'unfinished_turns',(SELECT count(*) FROM ma.turn WHERE scope_key=:'scope' AND status NOT IN ('succeeded','failed','cancelled')));
            """, {'scope': scope}))
        assert facts == {'artifacts': 1, 'files': 1, 'exports': 1, 'unfinished_tools': 0, 'unfinished_turns': 0}, facts
        record.update(facts=facts, passed=True)
        save('complete')
        print('PASSED generated artifact archive, Brain metadata, cross-Portal download and replacement:', output, flush=True)
    except BaseException:
        record['error'] = traceback.format_exc()
        save('failed')
        raise
    finally:
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
