# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
import asyncio
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import AsyncMock, patch

from runtime.datasets import materialize_tasks
from runtime.external_datasets import definitions
from runtime.scenarios import ScenarioMixin

ROOT = Path(__file__).resolve().parents[1]


class CoreScopeTest(unittest.TestCase):
    def test_profile_validation_needs_no_cloud_sdk(self):
        code = '''
import builtins
original = builtins.__import__
def isolated_import(name, *args, **kwargs):
    if name.split('.')[0] in {'oss2', 'kubernetes', 'harbor'}:
        raise ImportError('Infrastructure dependency loaded: ' + name)
    return original(name, *args, **kwargs)
builtins.__import__ = isolated_import
from release_cli import main
main(['validate', '--definitions-only'])
'''
        process = subprocess.run([sys.executable, '-c', code], cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(process.returncode, 0, process.stderr)
        entries = json.loads(process.stdout)
        self.assertEqual(len(entries), 11)
        self.assertTrue(all(entry['valid'] for entry in entries))

    def test_owned_exports_and_external_templates_are_accepted_by_harbor(self):
        import toml
        from harbor.models.task.config import TaskConfig
        config = json.loads((ROOT / 'configs/suites/full.json').read_text())
        config['tasks'] = [name for name in config['tasks'] if name not in definitions(ROOT)]
        with tempfile.TemporaryDirectory() as tmp:
            tasks = materialize_tasks(ROOT, config, Path(tmp) / 'tasks')
            self.assertEqual(len(tasks), 26)
            for name, folder in tasks.items():
                with self.subTest(task=name):
                    task = TaskConfig.model_validate(toml.loads((folder / 'task.toml').read_text()))
                    self.assertEqual(task.verifier.environment_mode.value, 'separate')
                    self.assertTrue(task.artifacts)
                    # Smoke verifiers stay hidden; some upstream tasks deliberately
                    # include public tests or data generators in their environment.
                    if name in ('repair-empty-input', 'aggregate-ledger'):
                        self.assertFalse((folder / 'environment/tests').exists())
        external = list((ROOT / 'external/terminal-bench/overlays').glob('*/task.toml'))
        external.append(ROOT / 'external/business/template/task.toml')
        self.assertEqual(len(external), 9)
        for path in external:
            task = TaskConfig.model_validate(toml.loads(path.read_text()))
            self.assertEqual(task.verifier.environment_mode.value, 'separate')
            self.assertTrue(task.artifacts)

    def test_business_task_cannot_silently_run_without_backend(self):
        class Native:
            def __init__(self):
                self.called = False
        class Agent(ScenarioMixin, Native):
            eval_name = 'agentscope'
            async def run_native(self, *args):
                self.called = True
        agent = Agent(eval_spec={'business_case': {'dataset': 'tau', 'task_id': 'retail:0'}})
        with patch('runtime.harbor_agents.record_request'):
            with self.assertRaisesRegex(RuntimeError, 'isolated official environment backend'):
                asyncio.run(agent.run('instruction', None, None))
        self.assertFalse(agent.called)

    def test_business_backend_is_injected_and_closed(self):
        class Native:
            def __init__(self):
                self.called = False
        class Agent(ScenarioMixin, Native):
            eval_name = 'agentscope'
            async def run_native(self, *args):
                self.called = True
        class Business:
            closed = False
            async def start(self):
                return {'system': 'fixture', 'observation': 'task', 'tools': []}
            def client(self):
                return '# fixture client'
            async def request(self, action):
                return {'done': True} if action == 'status' else {'score': 1}
            async def close(self):
                self.closed = True
        backend = Business()
        case = {'dataset': 'tau', 'task_id': 'retail:0'}
        seen = []
        def factory(value):
            seen.append(value)
            return backend
        with tempfile.TemporaryDirectory() as tmp, patch('runtime.harbor_agents.record_request'):
            agent = Agent(eval_spec={'business_case': case}, business_factory=factory)
            agent.logs_dir = Path(tmp)
            asyncio.run(agent.run('instruction', AsyncMock(), None))
            self.assertEqual(json.loads((Path(tmp) / 'business-result.json').read_text()), {'score': 1})
        self.assertEqual(seen, [case])
        self.assertTrue(agent.called)
        self.assertTrue(backend.closed)


if __name__ == '__main__':
    unittest.main()
