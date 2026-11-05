# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SERVICE = Path(__file__).resolve().parents[2]


class NativePreflightTests(unittest.TestCase):
    def test_missing_infrastructure_does_not_stop_an_existing_stack(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / 'scripts'
            scripts.mkdir()
            shutil.copy2(SERVICE / 'scripts/dev-up.sh', scripts / 'dev-up.sh')
            stopped = root / 'stop-called'
            (scripts / 'dev-down.sh').write_text('#!/bin/sh\ntouch "' + str(stopped) + '"\n')
            (scripts / 'dev-down.sh').chmod(0o755)
            env = {k: v for k, v in os.environ.items() if not k.startswith('BUILDER_')}
            result = subprocess.run(['bash', str(scripts / 'dev-up.sh')], env=env,
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('Set Redis URI', result.stderr)
            self.assertFalse(stopped.exists())
