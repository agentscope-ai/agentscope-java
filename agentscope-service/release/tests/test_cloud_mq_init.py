# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
import os
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest import mock

SERVICE = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('cloud_mq', SERVICE / 'deploy/managed-cloud-mq-init.py')
cloud = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cloud)


class CloudMqTests(unittest.TestCase):
    def test_custom_names_are_used_for_both_creation_and_group_binding(self):
        calls = []

        def cli(command, **kwargs):
            calls.append(command)
            action = command[2]
            error = {'GetTopic': 'Topic.NotFound', 'GetConsumerGroup': 'ConsumerGroup.NotFound'}.get(action)
            value = {'error_code': error} if error else {'success': True, 'data': {}}
            return SimpleNamespace(returncode=1 if error else 0, stdout=json.dumps(value), stderr='')

        env = {k: v for k, v in os.environ.items() if not k.startswith('BUILDER_MANAGED_MQ_')}
        env.update(BUILDER_MANAGED_MQ_TURN_TOPIC='private_turns', BUILDER_MANAGED_MQ_BRAIN_GROUP='private_brain')
        with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(cloud.subprocess, 'run', side_effect=cli):
            cloud.initialize('fixture-instance', 'fixture-region')
        topics = [c[c.index('--topicName') + 1] for c in calls if c[2] == 'CreateTopic']
        groups = [c for c in calls if c[2] == 'CreateConsumerGroup']
        self.assertEqual(len(topics), 5)
        self.assertEqual(len(groups), 5)
        self.assertIn('private_turns', topics)
        self.assertNotIn('ma_turns_v3', topics)
        group = next(c for c in groups if c[c.index('--consumerGroupId') + 1] == 'private_brain')
        self.assertEqual(json.loads(group[group.index('--body') + 1])['topicName'], 'private_turns')
