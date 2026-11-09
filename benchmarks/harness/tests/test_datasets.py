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
from collections import Counter
import json
from pathlib import Path
import shutil
import tempfile
import unittest

from runtime.datasets import materialize_tasks, resolve_task, task_files, validate_selection
from runtime.external_datasets import definitions
from runtime.release import freeze_contract

ROOT = Path(__file__).resolve().parents[1]


class DatasetTest(unittest.TestCase):
    def fixture(self, root, category='harness'):
        source = root / 'datasets' / category / 'example'
        (source / 'tests').mkdir(parents=True)
        for name in ('instruction.md', 'task.toml', 'tests/test.sh'):
            (source / name).write_text(name)
        return source

    def test_single_library_has_unique_ids_and_all_tasks_are_selected(self):
        paths = list((ROOT / 'datasets').glob('*/*/task.toml'))
        self.assertEqual(Counter(p.parent.parent.name for p in paths),
                         {'harness': 26, 'capabilities': 127})
        ids = {p.parent.name for p in paths}
        self.assertEqual(len(ids), len(paths))
        selected = set()
        for path in (ROOT / 'configs/suites').glob('*.json'):
            config = json.loads(path.read_text())
            self.assertNotIn('dataset_root', config)
            validate_selection(ROOT, config)
            selected.update(config['tasks'])
        self.assertEqual(selected, ids | definitions(ROOT).keys())
        self.assertEqual(Counter(d['kind'] for d in definitions(ROOT).values()), {'business': 807, 'terminal-bench': 8})
        self.assertFalse(list((ROOT / 'datasets').rglob('references.json')))
        self.assertEqual({p.name for p in (ROOT / 'datasets').iterdir() if p.is_dir()},
                         {'harness', 'capabilities'})

    def test_suites_export_the_same_canonical_task_content(self):
        profiles = [json.loads((ROOT / 'configs/suites' / name).read_text())
                    for name in ('smoke.json', 'full.json', 'release.json')]
        # Exercise the shared owned tasks without downloading unrelated external suites.
        external = definitions(ROOT)
        for config in profiles:
            config['tasks'] = [name for name in config['tasks'] if name not in external]
        with tempfile.TemporaryDirectory() as tmp:
            exports = [materialize_tasks(ROOT, config, Path(tmp) / str(i))
                       for i, config in enumerate(profiles)]
            for name in profiles[0]['tasks']:
                canonical = task_files(resolve_task(ROOT, name))
                for exported in exports:
                    self.assertEqual(task_files(exported[name]), canonical)
                    self.assertFalse(exported[name].is_symlink())

    def test_duplicate_missing_and_legacy_inputs_fail_before_export(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root)
            for config in ({'tasks': ['example', 'example']}, {'tasks': ['missing']},
                           {'tasks': ['example'], 'dataset_root': 'datasets/old'}):
                with self.assertRaises(ValueError):
                    materialize_tasks(root, config, root / 'out')
                self.assertFalse((root / 'out').exists())
            self.fixture(root, 'other')
            with self.assertRaisesRegex(ValueError, 'exactly one definition'):
                resolve_task(root, 'example')

    def test_invalid_paths_and_symlinks_cannot_escape_dataset(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = self.fixture(root)
            for name in ('../example', '/example', 'group/example'):
                with self.assertRaisesRegex(ValueError, 'Invalid task identity'):
                    resolve_task(root, name)
            source.rename(root / 'outside')
            source.symlink_to(root / 'outside', target_is_directory=True)
            with self.assertRaisesRegex(ValueError, 'escapes the datasets'):
                resolve_task(root, 'example')

    def test_task_and_runtime_source_changes_change_frozen_contract(self):
        config = json.loads((ROOT / 'configs/suites/smoke.json').read_text())
        config['tasks'] = ['example']
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            task = self.fixture(root)
            for relative in ('adapters/agentscope-java/src/main/java/io/agentscope/examples/eval/HarnessEvalCli.java',
                             'environments/Dockerfile.sandbox', 'reports/unified.py'):
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / relative, target)
            source = root / 'runtime/fixtures/example.py'
            source.parent.mkdir(parents=True)
            source.write_text('original fixture')
            before = freeze_contract(root, config)['sha256']
            (task / 'instruction.md').write_text('changed task')
            after = freeze_contract(root, config)['sha256']
            self.assertNotEqual(before, after)
            source.write_text('changed fixture')
            self.assertNotEqual(after, freeze_contract(root, config)['sha256'])


if __name__ == '__main__':
    unittest.main()
