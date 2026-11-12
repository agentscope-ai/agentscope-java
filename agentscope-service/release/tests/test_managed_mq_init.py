# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.

import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

import yaml


SERVICE = Path(__file__).resolve().parents[2]


class ManagedMqInitializationTests(unittest.TestCase):
    def initialize(self, failure, session_queue=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            admin = root / 'bin/mqadmin'
            admin.parent.mkdir()
            admin.write_text('''#!/bin/sh
set -eu
count=0
[ ! -f "$FIXTURE_COUNT" ] || count=$(cat "$FIXTURE_COUNT")
count=$((count + 1))
printf '%s\n' "$count" > "$FIXTURE_COUNT"
printf '%s\n' "$*" >> "$FIXTURE_CALLS"
if [ "$FIXTURE_FAILURE" = always ] || { [ "$FIXTURE_FAILURE" = once ] && [ "$count" = 1 ]; }; then
  printf '%s\n' '[error]NameServer request timed out'
  # mqadmin can misleadingly exit successfully after reporting an error.
  exit 0
fi
if [ "$FIXTURE_FAILURE" = mixed ]; then
  printf '%s\n' 'create topic to broker:10911 success.' '[error]Second broker unavailable'
  exit 0
fi
case "$1" in
  updateTopic) printf '%s\n' 'create topic to broker:10911 success.';;
  updateSubGroup) printf '%s\n' 'create subscription group to broker:10911 success.';;
  *) exit 2;;
esac
''')
            calls = root / 'calls'
            env = dict(os.environ, ROCKETMQ_HOME=str(root),
                       MANAGED_MQ_NAMESRV='namesrv:9876', MANAGED_MQ_CLUSTER='test',
                       FIXTURE_COUNT=str(root / 'count'), FIXTURE_CALLS=str(calls),
                       FIXTURE_FAILURE=failure)
            env['MANAGED_AGENTS_RUNTIME_SESSION_QUEUE_ENABLED'] = str(session_queue).lower()
            # Verify defaults independently of the developer's configured resources.
            env = {key: value for key, value in env.items()
                   if not key.startswith('MANAGED_AGENTS_RUNTIME_MQ_')}
            result = subprocess.run(['sh', str(SERVICE / 'deploy/managed-mq-init.sh')],
                                    env=env, capture_output=True, text=True, timeout=30)
            return result, [shlex.split(line) for line in calls.read_text().splitlines()]

    def test_error_with_zero_exit_is_retried_before_all_six_bindings(self):
        result, calls = self.initialize('once')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(calls), 14)
        self.assertEqual(calls[0], calls[1])
        topics = {call[call.index('-t') + 1] for call in calls if call[0] == 'updateTopic'}
        turns = {'ma_turns_v3'}
        self.assertEqual(topics, turns | {'DefaultHeartBeatSyncerTopic',
                                        'ma_tool_events_v3', 'ma_tool_execute_v1', 'ma_tool_control_v1',
                                        'ma_turn_resumes_v3', 'ma_session_projections_v3'})
        groups = [call for call in calls if call[0] == 'updateSubGroup']
        self.assertEqual(len(groups), 6)
        mq = yaml.safe_load((SERVICE / 'helm/managed-agents/values.yaml').read_text())['runtime']['mq']
        self.assertTrue(mq['toolEventsGroup'].startswith('ma_brain_'))
        bindings = {
            next(value.split('=', 1)[1] for value in group[-1].split(',')
                 if value.startswith('+lite.bind.topic=')): group[group.index('-g') + 1]
            for group in groups
        }
        expected = {mq['turnTopic']: mq['harnessRuntimeGroup'],
                    mq['toolEventTopic']: mq['toolEventsGroup'],
                    mq['toolExecuteTopic']: mq['toolExecuteGroup'],
                    mq['toolControlTopic']: mq['toolControlGroup'],
                    mq['resumeTopic']: mq['resumeGroup'],
                    'ma_session_projections_v3': 'ma_session_projections_v3'}
        self.assertEqual(bindings, expected)
        for group in groups:
            self.assertEqual(group[group.index('-m') + 1], 'true')
            self.assertEqual(group[group.index('-o') + 1], 'true')
            self.assertIn('+lite.sub.model=Shared', group[-1])
            self.assertIn('+lite.sub.wildcard=true', group[-1])

    def test_session_queue_adds_only_the_explicit_ready_binding(self):
        result, calls = self.initialize('never', session_queue=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        groups = [call for call in calls if call[0] == 'updateSubGroup']
        self.assertEqual(len(groups), 7)
        ready = [group for group in groups if group[group.index('-g') + 1] == 'ma_session_ready_v1']
        self.assertEqual(len(ready), 1)
        self.assertIn('+lite.bind.topic=ma_session_ready_v1', ready[0][-1])

    def test_persistent_error_with_zero_exit_stops_after_three_attempts(self):
        result, calls = self.initialize('always')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(calls), 3)
        self.assertIn('initialization was not confirmed', result.stderr)

    def test_partial_cluster_success_does_not_mask_an_error(self):
        result, calls = self.initialize('mixed')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(calls), 3)


if __name__ == '__main__':
    unittest.main()
