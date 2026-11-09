# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
import asyncio
import unittest
from runtime.scenarios import ScenarioMixin

class Base:
    async def exec_as_agent(self, environment, command, **kwargs):return command

class Probe(ScenarioMixin, Base):pass

class NativeStdoutTest(unittest.TestCase):
    def test_only_native_tee_stdout_is_discarded_and_log_destination_is_preserved(self):
        for agent, command in (
            ('codex', 'codex exec request 2>&1 </dev/null | tee /logs/agent/codex.txt'),
            ('pi', 'pi --print --mode json request | grep -v message_update | stdbuf -oL tee /logs/agent/pi.txt')):
            probe=Probe();probe.eval_name=agent
            self.assertEqual(asyncio.run(probe.exec_as_agent(None,command)),command+' >/dev/null')
            unrelated='cat /logs/agent/'+agent+'.txt'
            self.assertEqual(asyncio.run(probe.exec_as_agent(None,unrelated)),unrelated)

    def test_cancellation_wraps_the_same_native_process_and_redirect(self):
        probe=Probe(eval_spec={'evidence':'cancel'});probe.eval_name='pi'
        command=asyncio.run(probe.exec_as_agent(None,'pi --print request | tee /logs/agent/pi.txt'))
        self.assertTrue(command.startswith('setsid sh -c '))
        self.assertIn('tee /logs/agent/pi.txt >/dev/null', command)
