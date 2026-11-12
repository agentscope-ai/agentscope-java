# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Dependency-free terminal process service, installed inside one task Pod.

The Unix socket is local to the task; it carries no cloud credentials. A caller
supplies a stable operation ID. Lost replies may be queried, never re-executed.
PTY sessions can run an interactive shell with persistent cwd and variables.
"""
import argparse
import errno
import fcntl
import hashlib
import json
import os
from pathlib import Path
import pty
import re
import select
import signal
import socket
import socketserver
import subprocess
import threading
import time


MAX_REQUEST = 128 * 1024
MAX_OUTPUT = 64 * 1024 * 1024
PAGE_BYTES = 4 * 1024


def atomic_json(path, value):
    temporary = path.with_suffix('.tmp')
    with temporary.open('w') as stream:
        json.dump(value, stream)
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)


def identity(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', value):
        raise ValueError('Invalid operation ID')
    return value


class ProcessStore:
    def __init__(self, root):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.guard = (self.root / 'daemon.lock').open('a')
        try:
            fcntl.flock(self.guard, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.guard.close()
            raise
        self.lock = threading.RLock()
        self.live = {}

    def _path(self, process):
        return self.root / identity(process)

    def _signal(self, record, sig):
        try:
            os.killpg(record['process'].pid, sig)
        except ProcessLookupError:
            pass

    def start(self, request):
        process = identity(request['operation_id'])
        command = request['command']
        timeout = request.get('timeout_seconds', 600)
        if not isinstance(command, str) or not command or len(command) > 64000:
            raise ValueError('Invalid command')
        if type(timeout) not in (int, float) or not 0 < timeout <= 7200:
            raise ValueError('Timeout must be within 1..7200 seconds')
        if type(request.get('pty', False)) is not bool:
            raise ValueError('pty must be boolean')
        normalized = {'command': command, 'cwd': request.get('cwd'),
                      'pty': request.get('pty', False), 'timeout_seconds': timeout}
        directory = self._path(process)
        with self.lock:
            if directory.exists():
                prior = json.loads((directory / 'intent.json').read_text())
                if prior != normalized:
                    raise ValueError('Operation ID belongs to another command')
                return self.poll({'process_id': process})
            directory.mkdir()
            atomic_json(directory / 'intent.json', normalized)
            # A crash after this intent and before a receipt is UNKNOWN, not a
            # license to spawn another copy of the command.
            master = slave = None
            try:
                if normalized['pty']:
                    master, slave = pty.openpty()
                child = subprocess.Popen(['bash', '-lc', command], cwd=normalized['cwd'],
                    stdin=slave if slave is not None else subprocess.PIPE,
                    stdout=slave if slave is not None else subprocess.PIPE,
                    stderr=slave if slave is not None else subprocess.PIPE,
                    start_new_session=True, close_fds=True)
            except Exception as error:
                if master is not None:
                    os.close(master)
                atomic_json(directory / 'receipt.json', {'process_id': process,
                    'status': 'start_failed', 'return_code': None, 'error': type(error).__name__})
                raise
            finally:
                if slave is not None:
                    os.close(slave)
            record = {'process': child, 'input': master if master is not None else child.stdin,
                      'reason': None, 'bytes': 0}
            os.set_blocking(master if master is not None else child.stdin.fileno(), False)
            self.live[process] = record
            atomic_json(directory / 'receipt.json', {'process_id': process, 'pid': child.pid,
                'status': 'running', 'return_code': None, 'started': time.time()})
            readers = []
            for name, handle in ([('stdout', master)] if master is not None else
                                 [('stdout', child.stdout), ('stderr', child.stderr)]):
                reader = threading.Thread(target=self._read_output,
                    args=(record, directory / (name + '.bin'), handle), daemon=True)
                reader.start()
                readers.append(reader)
            threading.Thread(target=self._finish, args=(process, directory, record, readers, timeout),
                             daemon=True).start()
            return self.poll({'process_id': process})

    def _read_output(self, record, path, handle):
        fd = handle if isinstance(handle, int) else handle.fileno()
        try:
            with path.open('wb', buffering=0) as output:
                while True:
                    try:
                        chunk = os.read(fd, 16384)
                    except BlockingIOError:
                        select.select([fd], [], [], 1)
                        continue
                    except OSError as error:
                        if error.errno == errno.EIO:  # PTY EOF on Linux.
                            break
                        raise
                    if not chunk:
                        break
                    with self.lock:
                        record['bytes'] += len(chunk)
                        if record['bytes'] > MAX_OUTPUT:
                            record['reason'] = 'output_limit'
                            self._signal(record, signal.SIGKILL)
                            break
                    output.write(chunk)
        except OSError:
            with self.lock:
                record['reason'] = 'output_read_error'
                self._signal(record, signal.SIGKILL)
        finally:
            if not isinstance(handle, int):
                handle.close()

    def _finish(self, process, directory, record, readers, timeout):
        try:
            code = record['process'].wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            with self.lock:
                record['reason'] = 'timeout'
                self._signal(record, signal.SIGTERM)
            try:
                code = record['process'].wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._signal(record, signal.SIGKILL)
                code = record['process'].wait()
        for reader in readers:
            reader.join(1)
        # Remaining background children keep the process open for grading.
        # Do not claim quiescence merely because the shell returned.
        with self.lock:
            receipt = json.loads((directory / 'receipt.json').read_text())
            receipt.update(status='exited', return_code=code, finished=time.time(),
                           reason=record['reason'], output_closed=all(not r.is_alive() for r in readers))
            atomic_json(directory / 'receipt.json', receipt)
            if isinstance(record['input'], int) and receipt['output_closed']:
                os.close(record['input'])
            elif not isinstance(record['input'], int):
                record['input'].close()

    def poll(self, request):
        process = identity(request['process_id'])
        directory = self._path(process)
        if not directory.is_dir():
            raise ValueError('Unknown process')
        with self.lock:
            path = directory / 'receipt.json'
            result = json.loads(path.read_text()) if path.exists() else {
                'process_id': process, 'status': 'unknown', 'return_code': None}
            if result['status'] == 'running' and process not in self.live:
                result['status'] = 'unknown'
            for name in ['stdout', 'stderr']:
                offset = request.get(name + '_offset', 0)
                if type(offset) is not int or offset < 0:
                    raise ValueError('Offsets must be nonnegative byte counts')
                file = directory / (name + '.bin')
                chunk = b''
                size = file.stat().st_size if file.exists() else 0
                if file.exists():
                    with file.open('rb') as stream:
                        stream.seek(offset)
                        chunk = stream.read(PAGE_BYTES)
                result[name] = chunk.decode('utf-8', errors='replace')
                result[name + '_next_offset'] = offset + len(chunk)
                result[name + '_bytes'] = size
                result[name + '_more'] = offset + len(chunk) < size
            return result

    def input(self, request):
        process = identity(request['process_id'])
        operation = identity(request['operation_id'])
        value = request.get('text', '')
        if not isinstance(value, str) or len(value.encode()) > 4096:
            raise ValueError('Input must be at most 4096 bytes')
        with self.lock:
            directory = self._path(process)
            path = directory / ('input-' + operation + '.json')
            if path.exists():
                receipt = json.loads(path.read_text())
                if receipt['text'] != value:
                    raise ValueError('Input operation ID belongs to different input')
                return receipt
            record = self.live.get(process)
            if record is None or record['process'].poll() is not None:
                raise ValueError('Process is not accepting input')
            receipt = {'operation_id': operation, 'text': value, 'status': 'unknown'}
            atomic_json(path, receipt)
            handle = record['input']
            fd = handle if isinstance(handle, int) else handle.fileno()
            data = value.encode()
            written = os.write(fd, data)
            receipt.update(status='written' if written == len(data) else 'partial', bytes=written)
            atomic_json(path, receipt)
            return receipt

    def cancel(self, request):
        process = identity(request['process_id'])
        with self.lock:
            record = self.live.get(process)
            if record is None:
                return self.poll(request)
            record['reason'] = 'cancelled'
            self._signal(record, signal.SIGKILL)
        return self.poll(request)

    def dispatch(self, request):
        action = request.get('action')
        if action not in {'start', 'poll', 'input', 'cancel'}:
            raise ValueError('Unsupported action')
        return getattr(self, action)(request)


def serve(root):
    store = ProcessStore(root)
    address = str(Path(root) / 'terminal.sock')
    if Path(address).exists():
        Path(address).unlink()  # Exclusive daemon lock is held.

    class Handler(socketserver.StreamRequestHandler):
        def handle(self):
            body = self.rfile.readline(MAX_REQUEST + 1)
            try:
                if len(body) > MAX_REQUEST:
                    raise ValueError('Request too large')
                result = {'result': store.dispatch(json.loads(body))}
            except Exception as error:
                result = {'error': type(error).__name__ + ': ' + str(error)}
            self.wfile.write(json.dumps(result).encode() + b'\n')

    with socketserver.ThreadingUnixStreamServer(address, Handler) as server:
        server.daemon_threads = True
        os.chmod(address, 0o600)
        server.serve_forever()


def call(root, request):
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(20)
        client.connect(str(Path(root) / 'terminal.sock'))
        client.sendall(json.dumps(request).encode() + b'\n')
        with client.makefile('rb') as response:
            return json.loads(response.readline(256 * 1024))


def read_request_file(path, expected_sha256):
    with Path(path).open('rb') as stream:
        payload = stream.read(MAX_REQUEST + 1)
    if len(payload) > MAX_REQUEST:
        raise ValueError('Request too large')
    if hashlib.sha256(payload).hexdigest() != expected_sha256:
        raise ValueError('Request file hash mismatch; do not dispatch')
    return json.loads(payload)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['serve', 'call'])
    parser.add_argument('--root', required=True)
    requests = parser.add_mutually_exclusive_group()
    requests.add_argument('--request')
    requests.add_argument('--request-file')
    parser.add_argument('--request-sha256')
    arguments = parser.parse_args()
    if arguments.mode == 'serve':
        serve(arguments.root)
    else:
        request = (read_request_file(arguments.request_file, arguments.request_sha256)
                   if arguments.request_file else json.loads(arguments.request))
        print(json.dumps(call(arguments.root, request)))
