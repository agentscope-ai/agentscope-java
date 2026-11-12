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
import hashlib
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

from runtime.datasets import materialize_tasks, prepare_tasks, resolve_task
from runtime.external_datasets import definitions, file_hashes, prepared_path


def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value))


class ExternalDatasetTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = {'tasks': ['tb-example']}
        self.contents = {'instruction.md': b'Fix a file.\n', 'task.toml': b'version = "1.0"\n',
                         'tests/test.sh': b'echo verifier\n', 'environment/input.txt': b'input\n'}
        overlay = self.root / 'external/terminal-bench/overlays/tb-example'
        dump(overlay / 'scenario.json', {'category': 'terminal-bench'})
        expected = {name: hashlib.sha256(data).hexdigest() for name, data in self.contents.items()}
        expected.update(file_hashes(overlay))
        expected['tests/upstream-test.sh'] = expected['tests/test.sh']
        self.lock = {'repository': 'https://github.com/example/tasks', 'revision': 'a' * 40,
                     'tasks': {'tb-example': {'path': 'example',
                         'files': {name: hashlib.sha256(data).hexdigest() for name, data in self.contents.items()},
                         'prepared_files': expected}}}
        self.lock_path = self.root / 'external/terminal-bench/source.lock.json'
        dump(self.lock_path, self.lock)

    def fetch(self, url, **kwargs):
        self.assertIn('/' + 'a' * 40 + '/example/', url)
        return io.BytesIO(self.contents[url.rsplit('/example/', 1)[1]])

    def test_cold_fetch_offline_rebuild_export_and_tampering(self):
        with self.assertRaisesRegex(ValueError, 'not prepared'):
            resolve_task(self.root, 'tb-example')
        with patch('urllib.request.urlopen', side_effect=self.fetch) as network:
            original = prepare_tasks(self.root, self.config)['tb-example']
            self.assertEqual(network.call_count, 4)
        expected = file_hashes(original)
        shutil.rmtree(original.parent)
        with patch('urllib.request.urlopen', side_effect=AssertionError('offline network access')):
            rebuilt = prepare_tasks(self.root, self.config, offline=True)['tb-example']
            self.assertEqual(file_hashes(rebuilt), expected)
            exported = materialize_tasks(self.root, self.config, self.root / 'export')
            self.assertEqual(file_hashes(exported['tb-example']), expected)
        manifest = json.loads((self.root / 'export/manifest.json').read_text())
        self.assertEqual(manifest['tb-example']['source']['revision'], 'a' * 40)
        (rebuilt / 'instruction.md').write_text('tampered')
        with self.assertRaisesRegex(ValueError, 'content changed'):
            resolve_task(self.root, 'tb-example')

    def test_wrong_download_and_missing_offline_resource_never_publish_task(self):
        with self.assertRaisesRegex(ValueError, 'Offline resource is missing'):
            prepare_tasks(self.root, self.config, offline=True)
        with patch('urllib.request.urlopen', return_value=io.BytesIO(b'wrong content')):
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                prepare_tasks(self.root, self.config)
        with self.assertRaisesRegex(ValueError, 'not prepared'):
            resolve_task(self.root, 'tb-example')
        self.assertEqual(list((self.root / 'artifacts/datasets/downloads').iterdir()), [])

    def test_lock_and_overlay_changes_invalidate_prepared_identity(self):
        with patch('urllib.request.urlopen', side_effect=self.fetch):
            old = prepare_tasks(self.root, self.config)['tb-example']
        self.lock['revision'] = 'b' * 40
        dump(self.lock_path, self.lock)
        new = prepared_path(self.root, 'tb-example', definitions(self.root)['tb-example'])
        self.assertNotEqual(old, new)
        with self.assertRaisesRegex(ValueError, 'not prepared'):
            resolve_task(self.root, 'tb-example')
        overlay = self.root / 'external/terminal-bench/overlays/tb-example/scenario.json'
        overlay.write_text('{}')
        with self.assertRaisesRegex(ValueError, 'differs from locked content'):
            prepare_tasks(self.root, self.config, offline=True)

    def test_invalid_paths_and_duplicate_definitions_are_rejected(self):
        self.lock['tasks']['tb-example']['files']['../outside'] = 'a' * 64
        dump(self.lock_path, self.lock)
        # Invalid resource paths cannot escape the prepared task directory.
        with patch('urllib.request.urlopen', side_effect=self.fetch):
            with self.assertRaisesRegex(ValueError, 'Invalid dataset resource path'):
                prepare_tasks(self.root, self.config)
        folder = self.root / 'datasets/harness/tb-example'
        for name in ('task.toml', 'instruction.md', 'tests/test.sh'):
            path = folder / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture')
        with self.assertRaisesRegex(ValueError, 'exactly one definition'):
            prepare_tasks(self.root, self.config)

    def test_business_uses_one_template_and_preserves_official_identity(self):
        base = self.root / 'external/business'
        dump(base / 'inventory.json', {'tau': {'retail': [0, 1]}, 'appworld': {'dev': ['abc_1']}})
        source = {'repository': 'https://github.com/example/tasks', 'revision': 'a' * 40,
                  'files': {'example/instruction.md': hashlib.sha256(self.contents['instruction.md']).hexdigest()}}
        dump(base / 'sources.lock.json', {'tau': source, 'appworld': source})
        for name, content in {'instruction.md': 'Business', 'task.toml': 'tags = ["business", "tau-retail"]\n',
                              'tests/test.sh': 'echo verify'}.items():
            path = base / 'template' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        config = {'tasks': ['tau-retail-0', 'appworld-abc_1']}
        with patch('urllib.request.urlopen', side_effect=self.fetch) as network:
            tasks = prepare_tasks(self.root, config)
            self.assertEqual(network.call_count, 1)
        self.assertEqual(json.loads((tasks['appworld-abc_1'] / 'scenario.json').read_text()),
                         {'category': 'appworld-dev', 'business_case': {'dataset': 'appworld', 'task_id': 'abc_1'}})
        self.assertIn('appworld-dev', (tasks['appworld-abc_1'] / 'task.toml').read_text())
        self.assertFalse((self.root / 'datasets/business').exists())


if __name__ == '__main__':
    unittest.main()
