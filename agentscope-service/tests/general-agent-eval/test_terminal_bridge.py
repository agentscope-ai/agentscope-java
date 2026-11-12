# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import hashlib
import json
from pathlib import Path
import shlex
import tempfile
from types import SimpleNamespace
import unittest

from sandbox_eval.exec_transport import ExecutionOutcomeUnknown
from sandbox_eval.terminal_bridge import TerminalBridge


class Environment:
    def __init__(self, fail_upload=False, fail_receipt=False):
        self.uploads = {}
        self.commands = []
        self.fail_upload = fail_upload
        self.fail_receipt = fail_receipt

    async def upload_file(self, source, target):
        if self.fail_upload:
            raise ExecutionOutcomeUnknown('Upload acknowledgement lost')
        self.uploads[target] = Path(source).read_bytes()

    async def exec(self, command, **kwargs):
        self.commands.append(command)
        if self.fail_receipt:
            raise ExecutionOutcomeUnknown('RPC acknowledgement lost')
        argv = shlex.split(command)
        if '--request' in argv:
            self.request = json.loads(argv[argv.index('--request') + 1])
            return SimpleNamespace(return_code=0, stdout=json.dumps({'result': {'status': 'running'}}))
        self.request = json.loads(self.uploads[argv[argv.index('--request-file') + 1]])
        expected = argv[argv.index('--request-sha256') + 1]
        actual = hashlib.sha256(self.uploads[argv[argv.index('--request-file') + 1]]).hexdigest()
        if expected != actual:
            raise AssertionError('Request changed during transport')
        return SimpleNamespace(return_code=0, stdout=json.dumps({'result': {'status': 'running'}}))


class TerminalBridgeTest(unittest.IsolatedAsyncioTestCase):
    async def test_cleanup_command_is_not_exposed_in_rpc_process_arguments(self):
        with tempfile.TemporaryDirectory() as root:
            env = Environment()
            bridge = TerminalBridge(env, root)
            command = "pkill -f cleanup_unique_marker; printf '%s' 'spaces and 中文'"
            result = await bridge.call('terminal_start', {'operation_id': 'one', 'command': command})
            self.assertEqual(result['result']['status'], 'running')
            self.assertEqual(env.request['command'], command)
            self.assertEqual(env.request['operation_id'], 'one')
            self.assertEqual(len(env.commands), 1)
            self.assertNotIn('cleanup_unique_marker', env.commands[0])
            self.assertEqual(len(list(Path(root).glob('*-request.json'))), 1)

    async def test_unconfirmed_upload_does_not_dispatch_model_command(self):
        with tempfile.TemporaryDirectory() as root:
            env = Environment(fail_upload=True)
            bridge = TerminalBridge(env, root)
            with self.assertRaises(ExecutionOutcomeUnknown):
                await bridge.call('terminal_start', {'operation_id': 'one', 'command': 'touch once'})
            self.assertEqual(env.commands, [])
            self.assertEqual(len(list(Path(root).glob('*-request.json'))), 1)

    async def test_lost_rpc_receipt_keeps_intent_and_never_replays(self):
        with tempfile.TemporaryDirectory() as root:
            env = Environment(fail_receipt=True)
            bridge = TerminalBridge(env, root)
            with self.assertRaises(ExecutionOutcomeUnknown):
                await bridge.call('terminal_start', {'operation_id': 'one', 'command': 'touch once'})
            self.assertEqual(len(env.commands), 1)
            self.assertEqual(len(list(Path(root).glob('*-request.json'))), 1)
            self.assertEqual(list(Path(root).glob('*-response.json')), [])


if __name__ == '__main__':
    unittest.main()
