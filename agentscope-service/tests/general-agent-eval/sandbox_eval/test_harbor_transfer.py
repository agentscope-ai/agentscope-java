# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Run in the pinned Harbor environment: transfer failures cannot become success."""
import base64
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch

from sandbox_eval.exec_transport import ExecutionOutcomeUnknown
try:
    from sandbox_eval.harbor_environment import ManagedACKEnvironment
except ModuleNotFoundError as error:
    if error.name not in {'harbor', 'kubernetes'}:
        raise
    raise unittest.SkipTest('Run this test in the pinned Harbor controller environment') from error


class TransferTest(unittest.IsolatedAsyncioTestCase):
    async def test_incomplete_upload_never_extracts(self):
        environment = object.__new__(ManagedACKEnvironment)
        calls = []

        async def execute(command, **kwargs):
            calls.append(command)
            return SimpleNamespace(return_code=0, stdout='wronghash file\n', stderr='')
        environment.exec = execute
        with self.assertRaises(ExecutionOutcomeUnknown):
            await environment._upload_archive(b'binary\x00\xff', '/tests')
        self.assertFalse(any('tar xf' in command for command in calls))

    async def test_binary_download_and_exit_check(self):
        environment = object.__new__(ManagedACKEnvironment)
        environment.pod_name = 'artifact-test'
        buffer = io.BytesIO()
        payload = b'\x00\xff\xfe\x80'
        with tarfile.open(fileobj=buffer, mode='w:gz') as archive:
            info = tarfile.TarInfo('artifact.bin')
            info.size = len(payload)
            archive.addfile(info, io.BytesIO(payload))

        async def execute(command, **kwargs):
            if command.startswith('python3 -c '):
                stdout = json.dumps({'bytes': len(buffer.getvalue()),
                                     'sha256': hashlib.sha256(buffer.getvalue()).hexdigest()})
            elif 'dd if=' in command:
                stdout = base64.b64encode(buffer.getvalue()).decode()
            else:
                stdout = ''
            return SimpleNamespace(return_code=0, stdout=stdout)
        environment.exec = execute
        with tempfile.TemporaryDirectory() as root:
            await environment.download_dir('/logs', root)
            self.assertEqual((Path(root) / 'artifact.bin').read_bytes(), payload)

        async def failed(command, **kwargs):
            return SimpleNamespace(return_code=1, stdout=base64.b64encode(buffer.getvalue()).decode())
        environment.exec = failed
        with tempfile.TemporaryDirectory() as root, self.assertRaises(ExecutionOutcomeUnknown):
            await environment.download_dir('/logs', root)

    async def test_slow_image_pull_uses_preparation_budget_before_admission(self):
        environment = object.__new__(ManagedACKEnvironment)
        environment.task_env_config = SimpleNamespace(build_timeout_sec=600)
        environment.namespace = 'isolated-test'
        environment.pod_name = 'slow-image'
        calls = []

        def read(**kwargs):
            calls.append(kwargs)
            ready = len(calls) > 345
            return SimpleNamespace(status=SimpleNamespace(
                phase='Running' if ready else 'Pending',
                container_statuses=[SimpleNamespace(ready=True)] if ready else []))

        environment._core_api = SimpleNamespace(read_namespaced_pod=read)
        with patch('asyncio.sleep', new_callable=AsyncMock):
            await environment._wait_for_pod_ready()
            self.assertEqual(len(calls), 346)
            calls.clear()
            with self.assertRaisesRegex(RuntimeError, 'not ready after 3 seconds'):
                await environment._wait_for_pod_ready(3)
            self.assertEqual(len(calls), 3)


if __name__ == '__main__':
    unittest.main()
