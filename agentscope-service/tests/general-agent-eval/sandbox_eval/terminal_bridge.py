# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Common terminal capabilities; execution stays in the exact Harbor task Pod."""
import asyncio
import hashlib
import json
import shlex
import time
from pathlib import Path

from sandbox_eval.exec_transport import ExecutionOutcomeUnknown


TOOL_CATALOG = [
    {'name': 'terminal_start', 'description':
     'Start a bash command in the task sandbox. Read, create and edit task files with shell commands. '
     'Use a new operation_id for each new command; reuse the same ID only to recover its original receipt. '
     'For a persistent interactive shell start bash --noprofile --norc -i with pty=true, then use terminal_input. '
     'The returned process_id supports polling; running does not mean completed. Exit codes are real process codes.',
     'inputSchema': {'type': 'object', 'properties': {
         'operation_id': {'type': 'string', 'pattern': '^[A-Za-z0-9_-]{1,100}$'},
         'command': {'type': 'string', 'maxLength': 64000},
         'cwd': {'type': 'string'}, 'pty': {'type': 'boolean', 'default': False},
         'timeout_seconds': {'type': 'number', 'exclusiveMinimum': 0, 'maximum': 7200, 'default': 600}},
         'required': ['operation_id', 'command'], 'additionalProperties': False}},
    {'name': 'terminal_poll', 'description':
     'Read process status and paged stdout/stderr without repeating the command. Offsets are byte counts; '
     'use the prior next_offset to read further output. Unknown requires inspection, not command replay.',
     'inputSchema': {'type': 'object', 'properties': {
         'process_id': {'type': 'string'}, 'stdout_offset': {'type': 'integer', 'minimum': 0, 'default': 0},
         'stderr_offset': {'type': 'integer', 'minimum': 0, 'default': 0}},
         'required': ['process_id'], 'additionalProperties': False}},
    {'name': 'terminal_input', 'description':
     'Send text to a running process or persistent shell. Include a newline to submit a shell command. '
     'A unique operation_id identifies this input; reusing it retrieves the receipt without sending text twice. '
     'Poll the process to read its output. Control characters such as \\u0003 may be sent to a PTY.',
     'inputSchema': {'type': 'object', 'properties': {
         'process_id': {'type': 'string'}, 'operation_id': {'type': 'string'},
         'text': {'type': 'string', 'maxLength': 4096}},
         'required': ['process_id', 'operation_id', 'text'], 'additionalProperties': False}},
    {'name': 'terminal_cancel', 'description':
     'Terminate the selected process group. Poll to confirm its exit; this does not undo file changes.',
     'inputSchema': {'type': 'object', 'properties': {'process_id': {'type': 'string'}},
         'required': ['process_id'], 'additionalProperties': False}},
]


class TerminalBridge:
    """One instance per admitted trial, owned by the external Session API agent."""
    def __init__(self, environment, journal_dir, root='/tmp/managed-eval-terminal'):
        self.environment = environment
        self.root = root
        self.journal = Path(journal_dir)
        self.journal.mkdir(parents=True, exist_ok=True)
        self.closed = False
        self.lock = asyncio.Lock()

    async def setup(self):
        intent = self.journal / 'setup-intent.json'
        with intent.open('x') as stream:
            json.dump({'root': self.root, 'started': time.time()}, stream)
        result = await self.environment.exec('mkdir -p ' + shlex.quote(self.root), timeout_sec=30)
        if result.return_code != 0:
            raise RuntimeError('Cannot create terminal process directory')
        helper = self.root + '/terminal_process.py'
        await self.environment.upload_file(Path(__file__).with_name('terminal_process.py'), helper)
        # No background shell with inherited pipes: remote exec must close while
        # the process service continues independently inside the Pod.
        source = ('import subprocess; subprocess.Popen(' + repr(['python3', helper, 'serve', '--root', self.root]) +
                  ', stdin=subprocess.DEVNULL, stdout=open(' + repr(self.root + '/daemon.log') +
                  ', "w"), stderr=subprocess.STDOUT, start_new_session=True)')
        result = await self.environment.exec('python3 -c ' + shlex.quote(source), timeout_sec=30)
        if result.return_code != 0:
            raise RuntimeError('Terminal daemon launch was not successful')
        # A read-only readiness probe cannot spawn a duplicate daemon.
        result = await self.environment.exec(
            'for i in $(seq 1 30); do test -S ' + shlex.quote(self.root + '/terminal.sock') +
            ' && exit 0; sleep 0.1; done; exit 1', timeout_sec=10)
        if result.return_code != 0:
            raise RuntimeError('Terminal socket did not become ready; preserve launch evidence')
        (self.journal / 'setup-receipt.json').write_text(json.dumps({'ready': True, 'at': time.time()}))

    async def call(self, name, arguments):
        if name not in {tool['name'] for tool in TOOL_CATALOG}:
            raise ValueError('Unknown terminal tool')
        async with self.lock:
            if self.closed:
                raise ValueError('Trial terminal access is closed')
            request = {**arguments, 'action': name.removeprefix('terminal_')}
            index = str(time.time_ns())
            local_request = self.journal / (index + '-request.json')
            payload = json.dumps(request).encode()
            with local_request.open('xb') as stream:
                stream.write(payload)
            remote_request = self.root + '/rpc-' + index + '.json'
            # Keep model commands out of the RPC process command line. A task
            # using pkill -f must not accidentally kill the transport carrying
            # the same command. Unconfirmed uploads never dispatch the request.
            await self.environment.upload_file(local_request, remote_request)
            command = 'python3 ' + shlex.quote(self.root + '/terminal_process.py') + \
                      ' call --root ' + shlex.quote(self.root) + \
                      ' --request-file ' + shlex.quote(remote_request) + \
                      ' --request-sha256 ' + hashlib.sha256(payload).hexdigest()
            result = await self.environment.exec(command, timeout_sec=30)
            if result.return_code != 0:
                raise ExecutionOutcomeUnknown('Terminal RPC did not return a receipt; inspect the original operation')
            try:
                response = json.loads(result.stdout)
            except ValueError as error:
                raise ExecutionOutcomeUnknown('Terminal RPC returned an invalid receipt') from error
            (self.journal / (index + '-response.json')).write_text(json.dumps(response))
            return response

    async def close(self):
        async with self.lock:
            self.closed = True
