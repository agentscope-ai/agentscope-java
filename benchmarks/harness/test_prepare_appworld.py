# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
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
