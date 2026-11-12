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
import importlib.util
import json
import re
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from runtime.fixture_tools import ToolServer, Fixture
from runtime.task_score import evaluate, MissingEvidence, word_constraints, native_assistant_texts

spec = importlib.util.spec_from_file_location('verify_files', next((ROOT / 'datasets/capabilities').glob('*/tests/verify.py')))
file_checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(file_checks)


def message(text):
    return {'type': 'agent.message', 'content': [{'type': 'text', 'text': text}]}


class CapabilitiesTest(unittest.TestCase):
    def test_owned_tasks_have_checks_attribution_and_independent_fixtures(self):
        root = ROOT / 'datasets/capabilities'
        references = (root / 'README.md').read_text().split('## Excluded reference cases')[0]
        documented = set(re.findall(r'\| `(cap-[a-f0-9]+)` \|', references))
        self.assertEqual(documented, {p.parent.name for p in root.glob('*/task.toml')})
        self.assertEqual(len(documented), 127)
        for name in documented:
            folder = root / name
            scenario = json.loads((folder / 'scenario.json').read_text())
            self.assertTrue(json.loads((folder / 'tests/checks.json').read_text()))
            self.assertTrue(scenario['capabilities'])
            self.assertTrue(scenario['category'].startswith('capabilities/'))
            self.assertTrue({'source_url', 'source_file', 'source_function'}.isdisjoint(scenario))
            self.assertNotIn('unsupported_detail', scenario)
            if scenario.get('fixture_tools'): Fixture(scenario['fixture_tools'])
        self.assertFalse((ROOT / 'sources').exists())

    def test_synthetic_data_is_isolated_between_trials(self):
        from runtime.fixture_tools import fixture_module
        before = fixture_module('test_tool_usage_relational.py')
        before['USER_DATA'][0]['name'] = 'Changed'
        self.assertNotEqual(fixture_module('test_tool_usage_relational.py')['USER_DATA'][0]['name'], 'Changed')

    def test_remote_tools_are_isolated_and_receipts_are_immutable(self):
        server = ToolServer('127.0.0.1', 0)
        try:
            a, ca = server.register({'module': 'builtin', 'names': ['write_todos']}, '127.0.0.1')
            b, cb = server.register({'module': 'builtin', 'names': ['write_todos']}, '127.0.0.1')
            request = urllib.request.Request(ca['url'], data=json.dumps({'name': 'write_todos', 'arguments': {'todos': [{'content': 'A', 'status': 'pending'}]}}).encode(), headers={'Authorization': 'Bearer ' + a})
            with urllib.request.urlopen(request) as response:
                self.assertEqual(json.load(response)['result'], 'Todo list updated.')
            server.fixtures[a].todos[0]['status'] = 'completed'
            self.assertEqual(server.fixtures[a].receipts[0]['arguments']['todos'][0]['status'], 'pending')
            self.assertEqual(server.release(b), [])
            self.assertEqual(len(server.release(a)), 1)
            with self.assertRaises(urllib.error.HTTPError): urllib.request.urlopen(request)
        finally: server.close()

    def test_lookup_data_requires_real_fixture_calls(self):
        fixture = Fixture({'module': 'test_tool_usage_relational.py', 'names': ['get_current_user_id', 'get_user_name']})
        response = fixture.call('get_current_user_id', {})
        user = fixture.call('get_user_name', {'user_id': response['result']})
        self.assertIsInstance(user['result'], str)
        self.assertIn('error', fixture.call('read_all_data', {}))
        check = {'kind': 'required_fixture_calls', 'args': [[{'name': 'get_user_name', 'args_contains': {'user_id': response['result']}}]]}
        with tempfile.TemporaryDirectory() as tmp:
            no_call, _ = evaluate([check], [message(user['result'])], [], Path(tmp), {}, {})
            real_call, _ = evaluate([check], [message(user['result'])], fixture.receipts, Path(tmp), {}, {})
        self.assertFalse(all(no_call.values()))
        self.assertTrue(all(real_call.values()))

    def test_file_checks_reject_wrong_missing_and_symlink_outputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            checks = [{'kind': 'file_equals', 'args': ['/workspace/a', 'bar']}]
            self.assertFalse(all(file_checks.verify(checks, root).values()))
            (root / 'a').write_text('bar\n')
            self.assertFalse(all(file_checks.verify(checks, root).values()))
            (root / 'a').write_text('bar')
            self.assertTrue(all(file_checks.verify(checks, root).values()))
            (root / 'a').unlink()
            (root / 'a').symlink_to('/etc/passwd')
            self.assertFalse(all(file_checks.verify(checks, root).values()))

    def test_original_assertions_and_judge_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            checks = [{'kind': 'final_text_contains', 'args': ['DONE']}, {'kind': 'final_text_excludes', 'args': ['wrong']}]
            result, _ = evaluate(checks, [message('DONE wrong')], [], Path(tmp), {}, {})
            self.assertFalse(all(result.values()))
            with self.assertRaises(MissingEvidence): evaluate(checks, [], [], Path(tmp), {}, {})
            with self.assertRaises(ValueError): evaluate([{'kind': 'unknown', 'args': []}], [message('DONE')], [], Path(tmp), {}, {})
            def bad_judge(*args): raise RuntimeError('judge unavailable')
            with self.assertRaises(RuntimeError): evaluate([{'kind': 'llm_judge', 'args': ['must be good']}], [message('DONE')], [], Path(tmp), {}, {}, bad_judge)

    def test_word_constraint_matches_upstream_window_semantics(self):
        self.assertTrue(word_constraints('Here you go:\n\nZebra protocol has four words.', 5))
        self.assertFalse(word_constraints('Zebra protocol has four words.', 4))
        self.assertFalse(word_constraints('Some other sentence has five.', 5))

    def test_intermediate_public_text_is_retained_without_reasoning_or_children(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'native.jsonl'
            base = {'type': 'TEXT_BLOCK_DELTA', 'execution': {'agentId': 'HarnessAgent'}, 'replyId': 'r1', 'blockId': 'text'}
            path.write_text('\n'.join(json.dumps(x) for x in [
                {**base, 'delta': 'Fast'}, {**base, 'delta': 'API'},
                {**base, 'type': 'THINKING_BLOCK_DELTA', 'delta': 'hidden'},
                {**base, 'execution': {'agentId': 'child'}, 'delta': 'child text'}]))
            texts = native_assistant_texts('agentscope', [path])
            self.assertEqual(texts, ['FastAPI'])
            results, _ = evaluate([{'kind': 'all_text_contains', 'args': ['fastapi'], 'case_insensitive': True}],
                [message('Memory updated')], [], Path(tmp), {}, {}, native_texts=texts)
            self.assertTrue(all(results.values()))

    def test_framework_specific_cases_are_never_silently_scored(self):
        documentation = (ROOT / 'datasets/capabilities/README.md').read_text().split('## Excluded reference cases')[1]
        excluded = set(re.findall(r'\| `(cap-[a-f0-9]+)` \|', documentation))
        self.assertEqual(len(excluded), 12)
        for line in documentation.splitlines():
            if line.startswith('| `cap-'):
                self.assertIn('[original test](https://', line)
                self.assertTrue(line.split('|')[-2].strip())
        for path in (ROOT / 'configs/suites').glob('*.json'):
            self.assertFalse(excluded.intersection(json.loads(path.read_text())['tasks']))

    def test_read_save_order_accepts_native_memory_tool_and_fails_missing_save(self):
        check = {'kind': 'read_before_memory_write', 'args': ['/workspace/project/app.py', '/workspace/project/AGENTS.md']}
        read = {'type': 'agent.tool_use', 'name': 'read_file', 'input': {'path': 'project/app.py'}}
        save = {'type': 'agent.tool_use', 'name': 'memory_save', 'input': {'content': 'concise'}}
        with tempfile.TemporaryDirectory() as tmp:
            def grade(events): return all(evaluate([check], events + [message('DONE')], [], Path(tmp), {}, {})[0].values())
            self.assertTrue(grade([read, save]))
            self.assertFalse(grade([save, read]))
            self.assertFalse(grade([read]))

    def test_subagent_attempt_is_not_successful_delegation(self):
        check = {'kind': 'successful_delegation', 'args': ['89']}
        call = {'type': 'agent.tool_use', 'id': 'child', 'name': 'agent_spawn', 'input': {}}
        with tempfile.TemporaryDirectory() as tmp:
            for text, expected in [('Unknown model qwen3.8-max', False), ('status: ok\nreply:\nSunny at 89 F', True)]:
                response = {'type': 'agent.tool_result', 'tool_use_id': 'child', 'content': [{'type': 'text', 'text': text}]}
                actual, _ = evaluate([check], [call, response, message('89')], [], Path(tmp), {}, {})
                self.assertEqual(all(actual.values()), expected)

    def test_skill_alias_edits_original_and_is_outside_log_archive(self):
        from runtime.scenarios import SKILL_REGISTRY, skill_registration_command
        self.assertFalse(Path(SKILL_REGISTRY).is_relative_to('/logs'))
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'project/deployment'
            source.mkdir(parents=True)
            (source / 'SKILL.md').write_text('Original')
            target = root / 'registered'
            subprocess.run(skill_registration_command([str(source.parent)], str(target)), shell=True, check=True)
            (target / 'deployment/SKILL.md').write_text('Updated')
            self.assertEqual((source / 'SKILL.md').read_text(), 'Updated')


if __name__ == '__main__': unittest.main()
