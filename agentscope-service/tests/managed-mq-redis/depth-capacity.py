#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Verify parent/child progress with one callback slot per depth and Brain replica.

Requires a fresh replicas.py deployment with --callbacks 1.
The model is deterministic; the Sandbox is a real local process, not ACS.
"""
import argparse
import json
from pathlib import Path
import time
import traceback
import uuid

import replicas
import subagents


def capacity(state, sessions):
    return json.loads(replicas.fact(state, """
        WITH roots AS (SELECT scope_key FROM ma.session WHERE session_id IN
          (SELECT jsonb_array_elements_text(:'sessions'::jsonb))),
        children AS (SELECT child_scope_key AS scope_key FROM ma.subagent_call c
          JOIN roots r ON r.scope_key=c.parent_scope_key WHERE child_scope_key IS NOT NULL),
        waiting AS (SELECT t.turn_id,t.status,s.scope_key,s.session_id FROM ma.turn t
          JOIN ma.session s USING(scope_key) WHERE t.status IN ('running','waiting_tool'))
        SELECT json_build_object(
          'roots', (SELECT coalesce(json_agg(row_to_json(w)), '[]'::json)
             FROM waiting w JOIN roots r USING(scope_key)),
          'children', (SELECT coalesce(json_agg(row_to_json(w)), '[]'::json)
             FROM waiting w JOIN children c USING(scope_key)));
        """, {"sessions": json.dumps(sessions)}))


def run(state, seconds=30, continue_record=None):
    manifest = json.loads((state / 'manifest.json').read_text())
    assert manifest['turn_callbacks'] == manifest['child_turn_callbacks'] == 1, manifest
    assert len(manifest['mq']['physical_bindings']) == 19, manifest['mq']
    if seconds < 20 or seconds > 90:
        raise ValueError('Tool duration must be 20..90 seconds')
    output = state / f'depth-capacity-{time.time_ns()}.json'
    if continue_record is None:
        record = {'passed': False, 'seconds': seconds, 'callback_capacity_per_depth_per_brain': 1,
                  'model': 'explicit deterministic model', 'sandbox': 'real local process, not ACS',
                  'transport': 'real RocketMQ / Redis / PostgreSQL', 'roots': []}
    else:
        continue_record = continue_record.resolve()
        assert continue_record.parent == state and continue_record.name.startswith('depth-capacity-'), continue_record
        record = json.loads(continue_record.read_text())
        assert not record['passed'] and len(record['roots']) == 5, record
        record['continued_from'] = str(continue_record)
        record['previous_error'] = record.pop('error', None)
        record.pop('traceback', None)
        seconds = record['seconds']
    # Full Harness setup/finalization uses Hands too; the single-slot fixture queues that work.
    record['observation_budget_seconds'] = seconds * 5 + 600

    def save():
        output.write_text(json.dumps(record, indent=2) + '\n')

    save()
    try:
        if continue_record is None:
            environment, agent, identity = replicas.fixture_agent(
                max_iters=8, extra_tools=('agent_spawn', 'agent_send', 'agent_list'))
            record.update(environment=environment['id'], agent=agent['id'], identity=identity)
            for index in range(5):
                label = f'cap-{identity}-{index}'
                sessionPortal = replicas.PORTS['portal-a' if index % 2 == 0 else 'portal-b']
                session = replicas.request(sessionPortal, '/v1/sessions', {'agent': agent['id']}, uuid.uuid4().hex)
                base = '/v1/sessions/' + session['id']
                turn = replicas.request(sessionPortal, base + '/turns',
                    {'input': {'message': f'fixture:delegate:{seconds}:{label}'}}, uuid.uuid4().hex)
                record['roots'].append({'session': session['id'], 'turn': turn['id'], 'label': label})
                if index == 0:
                    record['successor'] = replicas.request(replicas.PORTS['portal-b'], base + '/turns',
                        {'input': {'message': f'fixture:echo:after-depth-{identity}'}}, uuid.uuid4().hex)
                save()
        else:
            identity = record.get('identity') or record['roots'][0]['label'].removeprefix('cap-').rsplit('-', 1)[0]
        deadline = time.monotonic() + record['observation_budget_seconds']
        while time.monotonic() < deadline:
            facts = capacity(state, [root['session'] for root in record['roots']])
            if 'capacity_observed' not in record and facts['roots'] and facts['children']:
                record['capacity_observed'] = facts
                save()
                print('CAPACITY root and child Turns progress with one callback per depth', flush=True)
            statuses = [replicas.request(replicas.PORTS['portal-b'],
                '/v1/sessions/' + root['session'] + '/turns/' + root['turn'])['status']
                for root in record['roots']]
            if any(status in {'failed', 'cancelled'} for status in statuses):
                raise AssertionError(f'Parent failed under callback saturation: {statuses}')
            if all(status == 'completed' for status in statuses):
                break
            time.sleep(.5)
        assert all(status == 'completed' for status in statuses), statuses
        assert 'capacity_observed' in record, 'Concurrent root and child work was not observed'
        for root in record['roots']:
            facts = subagents.snapshot(state, root['session'], root['turn'])
            assert len(facts['calls']) == 1 and facts['calls'][0]['status'] == 'succeeded', facts
            assert facts['legacy_sessions'] == 0 and facts['suspensions'] >= 2, facts
            tasks = [task for task in facts['tasks']
                     if task['native_call'] == 'replica-execute-child-one-' + root['label']]
            assert len(tasks) == 1 and tasks[0]['status'] == 'succeeded', tasks
            paths = subagents.effects(state, root['label'])
            assert len(paths) == 1 and paths[0].read_text().splitlines() == [
                'start-child-one-' + root['label'], 'done-child-one-' + root['label']], paths
            root.update(facts=facts, side_effect=str(paths[0]),
                        checkpoint=replicas.checkpoint_evidence(state, root['session']),
                        child_checkpoint=replicas.checkpoint_evidence(state, facts['calls'][0]['child_session']))
        first = record['roots'][0]
        successor = replicas.request(replicas.PORTS['portal-a'],
            '/v1/sessions/' + first['session'] + '/turns/' + record['successor']['id'])
        assert successor['status'] == 'completed', successor
        events = replicas.request(replicas.PORTS['portal-b'],
            '/v1/sessions/' + first['session'] + '/events?limit=256')
        assert not events['has_more'] and 'harness-runtime-after-depth-' + identity in json.dumps(events), events
        record.update(passed=True, successor=successor, first_session_events=events)
        save()
        print('PASS five real parent/child Sessions with one callback slot per depth and queued root successor', output, flush=True)
    except Exception as failure:
        record.update(passed=False, error=str(failure), traceback=traceback.format_exc())
        save()
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--state', type=Path, required=True)
    parser.add_argument('--seconds', type=int, default=30)
    parser.add_argument('--continue-record', type=Path, help='Observe existing admitted requests; preserve the original failure record')
    args = parser.parse_args()
    run(args.state.resolve(), args.seconds, args.continue_record)
