# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
import asyncio
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from runtime.scenarios import ScenarioMixin, signal_process_group_command

class CancellationSignalTest(unittest.TestCase):
    @unittest.skipUnless(os.name=='posix','Native process-group probe requires POSIX')
    def test_signal_command_works_through_posix_sh(self):
        with tempfile.TemporaryDirectory() as tmp:
            target=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'],start_new_session=True)
            try:
                pid=Path(tmp)/'pid';pid.write_text(str(target.pid))
                result=subprocess.run(['sh','-c',signal_process_group_command(str(pid))],
                    env={**os.environ,'PATH':str(Path(sys.executable).parent)+os.pathsep+os.environ['PATH']},capture_output=True,text=True)
                self.assertEqual(result.returncode,0,result.stderr)
                self.assertEqual(target.wait(timeout=5),-15)
            finally:
                if target.poll() is None:target.kill();target.wait()

    def test_failed_signal_is_not_reported_as_a_harness_cancellation_failure(self):
        class Probe(ScenarioMixin):
            async def run_native(self,*args):await asyncio.Event().wait()
        class Environment:
            def __init__(self):self.commands=[]
            async def exec(self,command,**kwargs):
                self.commands.append(command)
                return SimpleNamespace(return_code=0 if command=='test -f /workspace/started' else 2)
        environment=Environment();probe=Probe(eval_spec={})
        with self.assertRaisesRegex(RuntimeError,'signal delivery failed'):
            asyncio.run(probe.cancel_probe('instruction',environment,None))
        self.assertEqual(len(environment.commands),2)
