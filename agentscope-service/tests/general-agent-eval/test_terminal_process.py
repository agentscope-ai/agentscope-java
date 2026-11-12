# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import json
import hashlib
import os
from pathlib import Path
import tempfile
import subprocess
import sys
import time
import unittest

from sandbox_eval.terminal_process import ProcessStore, PAGE_BYTES, MAX_REQUEST, read_request_file


class TerminalProcessTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.store = ProcessStore(self.root / 'state')

    def tearDown(self):
        for process, record in self.store.live.items():
            if record['process'].poll() is None:
                self.store.cancel({'process_id': process})
                self.wait(process)
        self.store.guard.close()
        self.directory.cleanup()

    def wait(self, process, predicate=None):
        deadline = time.monotonic() + 5
        predicate = predicate or (lambda row: row['status'] == 'exited')
        while time.monotonic() < deadline:
            row = self.store.poll({'process_id': process})
            if predicate(row):
                return row
            time.sleep(.02)
        self.fail('Process did not reach expected state: ' + str(row))

    def test_lost_start_receipt_queries_same_process_without_replay(self):
        request = {'operation_id': 'one', 'command': 'echo once >> count; exit 7',
                   'cwd': str(self.root)}
        self.store.start(request)
        self.assertEqual(self.wait('one')['return_code'], 7)
        self.assertEqual(self.store.start(request)['return_code'], 7)
        self.assertEqual((self.root / 'count').read_text(), 'once\n')
        with self.assertRaisesRegex(ValueError, 'another command'):
            self.store.start({**request, 'command': 'echo twice >> count'})

    def test_unconfirmed_start_intent_never_replayed_after_daemon_restart(self):
        directory = self.root / 'state' / 'uncertain'
        directory.mkdir()
        request = {'command': 'echo bad > duplicate', 'cwd': str(self.root),
                   'pty': False, 'timeout_seconds': 600}
        (directory / 'intent.json').write_text(json.dumps(request))
        result = self.store.start({'operation_id': 'uncertain', **request})
        self.assertEqual(result['status'], 'unknown')
        self.assertFalse((self.root / 'duplicate').exists())

    def test_pty_shell_preserves_cwd_and_variables_and_input_is_not_replayed(self):
        self.store.start({'operation_id': 'shell', 'command': 'bash --noprofile --norc -i',
                          'pty': True, 'cwd': str(self.root)})
        self.store.input({'process_id': 'shell', 'operation_id': 'input1',
                          'text': 'mkdir work; cd work; export ANSWER=retained\n'})
        request = {'process_id': 'shell', 'operation_id': 'input2',
                   'text': 'echo "$ANSWER" >> answer; exit\n'}
        self.store.input(request)
        self.wait('shell')
        self.assertEqual(self.store.input(request)['status'], 'written')
        self.assertEqual((self.root / 'work' / 'answer').read_text(), 'retained\n')

    def test_timeout_and_cancel_preserve_real_exit_and_output(self):
        self.store.start({'operation_id': 'slow', 'command': 'echo ready; sleep 30',
                          'timeout_seconds': 1})
        row = self.wait('slow')
        self.assertEqual(row['reason'], 'timeout')
        self.assertNotEqual(row['return_code'], 0)
        self.assertIn('ready', row['stdout'])
        self.store.start({'operation_id': 'cancelled', 'command': 'sleep 30'})
        self.store.cancel({'process_id': 'cancelled'})
        row = self.wait('cancelled')
        self.assertEqual(row['reason'], 'cancelled')
        self.assertNotEqual(row['return_code'], 0)

    def test_output_pagination_preserves_all_bytes_and_stderr(self):
        self.store.start({'operation_id': 'pages', 'command':
                          "printf '%10000s' x; echo problem >&2"})
        row = self.wait('pages')
        self.assertEqual(len(row['stdout']), PAGE_BYTES)
        self.assertEqual(row['stderr'], 'problem\n')
        content = row['stdout']
        while row['stdout_more']:
            row = self.store.poll({'process_id': 'pages', 'stdout_offset': row['stdout_next_offset']})
            content += row['stdout']
        self.assertEqual(len(content), 10000)
        self.assertTrue(content.endswith('x'))

    def test_cross_process_daemon_ownership_and_invalid_identity(self):
        with self.assertRaises(BlockingIOError):
            other = ProcessStore(self.root / 'state')
            other.guard.close()
        with self.assertRaises(ValueError):
            self.store.start({'operation_id': '../escape', 'command': 'true'})

    def test_request_file_integrity_checked_before_dispatch(self):
        path = self.root / 'request.json'
        payload = json.dumps({'action': 'start', 'operation_id': 'one', 'command': 'touch once'}).encode()
        path.write_bytes(payload)
        digest = hashlib.sha256(payload).hexdigest()
        self.assertEqual(read_request_file(path, digest)['command'], 'touch once')
        path.write_bytes(payload.replace(b'once', b'evil'))
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            read_request_file(path, digest)
        path.write_bytes(b'x' * (MAX_REQUEST + 1))
        with self.assertRaisesRegex(ValueError, 'too large'):
            read_request_file(path, digest)

    def test_request_file_cli_round_trip_and_receipt_recovery(self):
        helper = Path(__file__).parent / 'sandbox_eval/terminal_process.py'
        # Short path also fits Unix socket limits on macOS.
        with tempfile.TemporaryDirectory(dir='/tmp', prefix='rpc-') as directory:
            root = Path(directory)
            daemon = subprocess.Popen([sys.executable, str(helper), 'serve', '--root', directory],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            try:
                deadline = time.monotonic() + 5
                while not (root / 'terminal.sock').exists():
                    if daemon.poll() is not None or time.monotonic() > deadline:
                        self.fail('Process service did not become ready')
                    time.sleep(.02)
                payload = json.dumps({'action': 'start', 'operation_id': 'once',
                                      'command': 'echo once >> count', 'cwd': directory}).encode()
                request = root / 'rpc.json'
                request.write_bytes(payload)
                args = [sys.executable, str(helper), 'call', '--root', directory,
                        '--request-file', str(request), '--request-sha256', hashlib.sha256(payload).hexdigest()]
                for _ in range(2):
                    response = subprocess.run(args, capture_output=True, text=True, timeout=5, check=True)
                    self.assertEqual(json.loads(response.stdout)['result']['process_id'], 'once')
                deadline = time.monotonic() + 5
                while not (root / 'count').exists() and time.monotonic() < deadline:
                    time.sleep(.02)
                self.assertEqual((root / 'count').read_text(), 'once\n')
                request.write_bytes(payload + b' ')
                response = subprocess.run(args, capture_output=True, text=True, timeout=5)
                self.assertNotEqual(response.returncode, 0)
                self.assertIn('hash mismatch', response.stderr)
                self.assertEqual((root / 'count').read_text(), 'once\n')
            finally:
                daemon.terminate()
                daemon.wait(timeout=5)
                daemon.stderr.close()


if __name__ == '__main__':
    unittest.main()
