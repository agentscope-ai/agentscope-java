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
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))


class OperationsTest(unittest.TestCase):


    def test_hidden_verifiers_reject_baseline_accept_correct_outputs(self):
        for task in ('repair-empty-input', 'aggregate-ledger'):
            with self.subTest(task=task), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                source = ROOT / 'datasets/harness' / task
                shutil.copytree(source / 'environment', root / 'workspace')
                shutil.copytree(source / 'tests', root / 'tests')
                (root / 'logs/verifier').mkdir(parents=True)
                code = (root / 'tests/verify.py').read_text()
                for prefix in ('/workspace', '/tests', '/logs'):
                    code = code.replace(prefix, str(root) + prefix)
                script = root / 'verify.py'
                script.write_text(code)
                subprocess.run([sys.executable, str(script)], check=True)
                self.assertEqual((root / 'logs/verifier/reward.txt').read_text(), '0')
                if task == 'repair-empty-input':
                    (root / 'workspace/stats.py').write_text('def mean(values):\n    values = list(values)\n    return sum(values) / len(values) if values else None\n')
                else:
                    (root / 'workspace/summary.json').write_text(json.dumps([
                        {'account': 'alpha', 'total_cents': 950}, {'account': 'beta', 'total_cents': 400}, {'account': 'delta', 'total_cents': 0}]))
                subprocess.run([sys.executable, str(script)], check=True)
                self.assertEqual((root / 'logs/verifier/reward.txt').read_text(), '1')
                if task == 'aggregate-ledger':
                    (root / 'workspace/ledger.csv').unlink()
                    subprocess.run([sys.executable, str(script)], check=True)
                    self.assertEqual((root / 'logs/verifier/reward.txt').read_text(), '0')
                    (root / 'tests/ledger.sha256').unlink()
                    result = subprocess.run([sys.executable, str(script)], capture_output=True)
                    self.assertNotEqual(result.returncode, 0)


if __name__ == '__main__':
    unittest.main()
