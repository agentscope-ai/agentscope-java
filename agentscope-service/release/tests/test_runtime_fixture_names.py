"""Verify all MQ channels stay isolated when the deployment fixtures use renamed settings."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SERVICE = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('runtime_fixture', SERVICE / 'tests/agent_service_postgres_fixture.py')
FIXTURE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FIXTURE)


class RuntimeFixtureNamingTest(unittest.TestCase):
    def test_every_channel_and_consumer_group_uses_its_dedicated_override(self):
        environment = {'MANAGED_AGENTS_TEST_REDIS_URI': 'redis://redis.test:6379',
                       'MANAGED_AGENTS_TEST_MQ_ENDPOINTS': 'mq.test:8081',
                       'MANAGED_AGENTS_TEST_MQ_BROKER_CONTAINER': 'fixture-broker'}
        channels = [('TURN_TOPIC', 'HARNESS_RUNTIME_GROUP'),
                    ('TOOL_EVENT_TOPIC', 'TOOL_EVENTS_GROUP'),
                    ('TOOL_EXECUTE_TOPIC', 'TOOL_EXECUTE_GROUP'),
                    ('TOOL_CONTROL_TOPIC', 'TOOL_CONTROL_GROUP'),
                    ('RESUME_TOPIC', 'RESUME_GROUP')]
        with tempfile.TemporaryDirectory() as directory, patch.dict('os.environ', environment, clear=True), patch.object(FIXTURE.subprocess, 'run') as run:
            settings = FIXTURE.managed_environment(Path(directory), 'isolated')
            for topic, group in channels:
                for suffix in [topic, group]:
                    key = 'MANAGED_AGENTS_RUNTIME_MQ_' + suffix
                    self.assertTrue(settings[key].endswith('_isolated'), key)
                    self.assertIn(key + '=' + settings[key], run.call_args.args[0])
            self.assertEqual(len(settings), 12)
            self.assertEqual(run.call_args.args[0][-3:], ['fixture-broker', 'sh', '-s'])


if __name__ == '__main__':
    unittest.main()
