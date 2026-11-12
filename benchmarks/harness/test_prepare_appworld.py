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
from io import BytesIO
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from prepare_appworld import materialize


class BundleIntegrityTest(unittest.TestCase):
    def test_lfs_pointer_hash_and_size_are_checked_before_replacement(self):
        data = b"official encrypted bundle fixture"
        pointer = "version https://git-lfs.github.com/spec/v1\noid sha256:" + hashlib.sha256(data).hexdigest() + "\nsize " + str(len(data)) + "\n"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / ".source").mkdir()
            for name in ("apps.bundle", "tests.bundle"):
                (root / ".source" / name).write_text(pointer)
            spec = SimpleNamespace(submodule_search_locations=[temporary])
            with patch("importlib.util.find_spec", return_value=spec), patch("urllib.request.urlopen", return_value=BytesIO(b"tampered")):
                with self.assertRaises(ValueError):
                    materialize("a" * 40)
            self.assertEqual((root / ".source/apps.bundle").read_text(), pointer)
            with patch("importlib.util.find_spec", return_value=spec), patch("urllib.request.urlopen", side_effect=lambda *args, **kwargs: BytesIO(data)):
                materialize("a" * 40)
            self.assertEqual((root / ".source/apps.bundle").read_bytes(), data)


if __name__ == "__main__":
    unittest.main()
