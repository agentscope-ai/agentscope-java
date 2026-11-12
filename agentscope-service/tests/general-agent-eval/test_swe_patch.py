# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import asyncio
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from types import SimpleNamespace
import unittest

from sandbox_eval.swe_patch import apply_to_clean_grader, export_patch, prepare_repository
from sandbox_eval.exec_transport import ExecutionOutcomeUnknown


class LocalEnvironment:
    def __init__(self, name):
        self.pod_name = name
        self.stdout_sizes = []

    async def exec(self, command, cwd=None, timeout_sec=None):
        # macOS base64 lacks -w; use Python for this transport-only test.
        command = command.replace('base64 -w 0',
            "python3 -c 'import sys,base64;sys.stdout.write(base64.b64encode(sys.stdin.buffer.read()).decode())'")
        result = await asyncio.to_thread(subprocess.run, ['bash', '-c', command], cwd=cwd,
                                         capture_output=True, text=True, timeout=timeout_sec)
        self.stdout_sizes.append(len(result.stdout))
        return SimpleNamespace(return_code=result.returncode, stdout=result.stdout, stderr=result.stderr)

    async def upload_file(self, source, destination):
        shutil.copyfile(source, destination)


class PatchTest(unittest.IsolatedAsyncioTestCase):
    async def test_explicit_upstream_setup_is_preserved_and_excluded_from_prediction(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root,
                                               stderr=subprocess.DEVNULL).decode().strip()
            def commit():
                git('add', '.')
                git('-c','user.name=Test','-c','user.email=test@example.invalid','commit','-m','fixture')
            git('init')
            config = root/'pyproject.toml'
            config.write_text('requires = ["setuptools"]\n')
            commit(); base = git('rev-parse','HEAD')
            config.write_text('requires = ["setuptools==68.0.0"]\n')
            commit(); setup = git('rev-parse','HEAD')
            environment = LocalEnvironment('source')
            with self.assertRaises(ExecutionOutcomeUnknown):
                await prepare_repository(environment,str(root),base,base)
            receipt = await prepare_repository(environment,str(root),base,setup)
            self.assertTrue(receipt['preserved_upstream_setup'])
            self.assertEqual(git('rev-parse','HEAD'),setup)
            (root/'model.py').write_text('model change\n')
            with self.assertRaises(ExecutionOutcomeUnknown):
                await prepare_repository(environment,str(root),base,setup)
            # Place the exported patch outside the worktree under examination.
            with tempfile.TemporaryDirectory() as output:
                patch = Path(output)/'prediction.patch'
                await export_patch(environment,str(root),setup,patch)
                self.assertIn('model.py',patch.read_text())
                self.assertNotIn('pyproject.toml',patch.read_text())
                self.assertEqual(config.read_text(),'requires = ["setuptools==68.0.0"]\n')

    async def test_fresh_image_mode_commit_normalizes_but_content_changes_are_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, stderr=subprocess.DEVNULL).decode().strip()
            def commit():
                git('add', '.')
                git('-c','user.name=Test','-c','user.email=test@example.invalid','commit','-m','fixture')
            git('init')
            source = root/'source.py'
            source.write_text('original\n');source.chmod(0o644);commit();base=git('rev-parse','HEAD')
            source.chmod(0o755);commit();setup=git('rev-parse','HEAD')
            receipt = await prepare_repository(LocalEnvironment('fresh'),str(root),base)
            self.assertEqual(receipt['image_head'],setup)
            self.assertEqual(receipt['normalized_modes'],1)
            self.assertEqual(git('rev-parse','HEAD'),base)
            source.write_text('changed\n');commit();changed=git('rev-parse','HEAD')
            with self.assertRaises(ExecutionOutcomeUnknown):
                await prepare_repository(LocalEnvironment('fresh'),str(root),base)
            self.assertEqual(git('rev-parse','HEAD'),changed)

    async def test_clean_grader_receives_binary_and_untracked_source_without_reusing_index(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            source, grader = root / 'source', root / 'grader'
            source.mkdir()
            def git(*args, cwd=source):
                return subprocess.check_output(['git', *args], cwd=cwd, stderr=subprocess.DEVNULL).decode().strip()
            git('init')
            (source / 'original.txt').write_text('before\n')
            git('add', '.')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-m', 'base')
            base = git('rev-parse', 'HEAD')
            git('clone', str(source), str(grader), cwd=root)
            (source / 'original.txt').write_text('after\n')
            binary = b'\x00\xff\x80\x00' + os.urandom(131072)
            (source / 'new.bin').write_bytes(binary)
            prior = (source / '.git/index').read_bytes()
            patch = root / 'prediction.patch'
            environment = LocalEnvironment('model')
            receipt = await export_patch(environment, str(source), base, patch)
            self.assertGreater(receipt['bytes'], 131072)
            self.assertLessEqual(max(environment.stdout_sizes), 10924)
            self.assertEqual((source / '.git/index').read_bytes(), prior)
            result = await apply_to_clean_grader(LocalEnvironment('grader'), str(grader), patch, receipt)
            self.assertTrue(result['applied'])
            self.assertEqual((grader / 'new.bin').read_bytes(), binary)
            self.assertEqual((grader / 'original.txt').read_text(), 'after\n')
            with self.assertRaises(ValueError):
                await apply_to_clean_grader(LocalEnvironment('model'), str(source), patch, receipt)
            with self.assertRaises(FileExistsError):
                await export_patch(LocalEnvironment('model'), str(source), base, patch)
            with self.assertRaises(ValueError):
                await apply_to_clean_grader(LocalEnvironment('grader'), str(grader), patch, receipt)

    async def test_truncated_chunk_preserves_partial_and_prevents_export_replay(self):
        import base64
        import hashlib
        import json
        class TruncatedEnvironment:
            pod_name = 'model'
            async def exec(self, command, **kwargs):
                if command.startswith('set -e'):
                    output = ''
                elif command.startswith('python3'):
                    output = json.dumps({'bytes': 4, 'sha256': hashlib.sha256(b'abcd').hexdigest()})
                else:
                    output = base64.b64encode(b'abc').decode()
                return SimpleNamespace(return_code=0, stdout=output)
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'prediction.patch'
            environment = TruncatedEnvironment()
            with self.assertRaises(ExecutionOutcomeUnknown):
                await export_patch(environment, '/testbed', 'a' * 40, path)
            self.assertFalse(path.exists())
            self.assertTrue(path.with_suffix('.patch.partial').exists())
            self.assertTrue(path.with_suffix('.patch.intent.json').exists())
            with self.assertRaises(FileExistsError):
                await export_patch(environment, '/testbed', 'a' * 40, path)


if __name__ == '__main__':
    unittest.main()
