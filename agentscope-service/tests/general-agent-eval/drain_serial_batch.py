# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Drain a serial scheduler only at a verified recorded, completed case boundary.

The scheduler is briefly frozen after results.jsonl closes. If another attempt
has begun, it is resumed immediately. No Session or tool is cancelled.
"""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import select
import signal
import sqlite3
import sys
import time
from urllib.request import Request, urlopen


def completed_boundary(part):
    raw = (part / 'results.jsonl').read_bytes()
    if not raw.endswith(b'\n'):
        return None
    rows = [json.loads(x) for x in raw.splitlines()]
    if not rows or rows[-1]['status'] != 'completed':
        return None
    recorded = {r['trial'] for r in rows}
    for path in part.glob('*.json'):
        data = json.loads(path.read_text())
        if isinstance(data, dict) and all(k in data for k in ['trial', 'target', 'task', 'repeat', 'status']) \
                and data['trial'] not in recorded:
            return None
    with sqlite3.connect('file:/eval/evaluation-gateway.sqlite?mode=ro', uri=True) as db:
        if db.execute('SELECT COUNT(*) FROM eval_case WHERE status<>?', ('closed',)).fetchone()[0] or \
                db.execute('SELECT COUNT(*) FROM eval_request WHERE status=?', ('pending',)).fetchone()[0]:
            return None
    return rows[-1], len(rows), hashlib.sha256(raw).hexdigest()


def main(args):
    root, part = Path(args.batch_root), Path(args.batch_root) / args.part
    marker = root / 'serial-drain.json'
    if marker.exists():
        raise ValueError('Drain already started; inspect its receipt')
    command_path = str(root / (args.part + '-runner.py'))
    candidates = []
    for path in Path('/proc').glob('[0-9]*/cmdline'):
        try:
            command = path.read_bytes().decode().split('\0')
            if command_path in command:
                candidates.append(int(path.parent.name))
        except (FileNotFoundError, PermissionError):
            pass
    if len(candidates) != 1:
        raise ValueError('Expected exactly one serial scheduler')
    pid = candidates[0]
    sys.path.insert(0, str(root / 'code'))
    from session_client import SessionClient
    config = json.loads(Path(args.config).read_text())
    lease = json.loads((part / 'environment-lease.json').read_text())
    libc = ctypes.CDLL(None, use_errno=True)
    fd = libc.inotify_init1(os.O_NONBLOCK)
    if fd < 0 or libc.inotify_add_watch(fd, os.fsencode(part / 'results.jsonl'), 0x00000008) < 0:
        raise OSError(ctypes.get_errno(), 'Cannot watch serial results close')
    deadline = time.monotonic() + 1800
    frozen = False
    try:
        while time.monotonic() < deadline:
            if not Path('/proc', str(pid), 'cmdline').exists():
                print(json.dumps({'status': 'scheduler_exited_naturally', 'pid': pid}))
                return
            if not select.select([fd], [], [], 5)[0]:
                continue
            os.read(fd, 65536)
            os.kill(pid, signal.SIGSTOP)
            frozen = True
            # Wait for the kernel to report the scheduler stopped before inspecting files.
            for _ in range(100):
                if '\nState:\tT' in Path('/proc', str(pid), 'status').read_text():
                    break
                time.sleep(.001)
            else:
                raise ValueError('Scheduler stop unconfirmed')
            boundary = completed_boundary(part)
            if boundary is None:
                os.kill(pid, signal.SIGCONT); frozen = False
                continue
            row, count, sha = boundary
            target = next(t for t in config['targets'] if t['name'] == row['target'])
            client = SessionClient(target['cluster_url'], target['headers'])
            turn = client.api('/v1/sessions/' + row['session'] + '/turns/' + row['turn'])
            turns = client.api('/v1/sessions/' + row['session'] + '/turns?limit=100')
            if turn['status'] != 'completed' or turns.get('has_more') or not all(
                    t['status'] in {'completed', 'failed', 'cancelled'} for t in turns['data']):
                raise ValueError('Last Session is not terminal')
            evidence = {'status': 'boundary_verified', 'pid': pid, 'part': args.part,
                        'last_trial': row['trial'], 'part_attempts': count, 'results_sha256': sha,
                        'turn': turn, 'turns': turns, 'at_epoch': time.time(),
                        'source_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                        'reason': 'User-authorized parallel cutover; no new trial admitted, no Session/tool cancellation'}
            with marker.open('x') as output:
                json.dump(evidence, output, indent=2)
            # SIGTERM remains pending until SIGCONT; the scheduler exits before running more code.
            os.kill(pid, signal.SIGTERM)
            os.kill(pid, signal.SIGCONT); frozen = False
            exit_file = root / (args.part + '-exit.json')
            for _ in range(100):
                if exit_file.exists():
                    break
                time.sleep(.1)
            else:
                raise ValueError('Scheduler termination receipt missing; preserve lease')
            evidence['scheduler_exit'] = json.loads(exit_file.read_text())
            if evidence['scheduler_exit']['returncode'] != -signal.SIGTERM or completed_boundary(part) != boundary:
                raise ValueError('Boundary changed or unexpected scheduler exit; preserve lease')
            request = Request(config['control_url'].rstrip('/') + '/lease/release',
                json.dumps({'slot': 'appworld', 'owner': lease['owner']}).encode(),
                {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + config['control_key']})
            with urlopen(request, timeout=30) as response:
                evidence['lease_release'] = json.load(response)
            evidence['status'] = 'drained'
            evidence['finished_at_epoch'] = time.time()
            marker.write_text(json.dumps(evidence, indent=2))
            print(json.dumps({k: evidence[k] for k in ['status', 'part', 'last_trial', 'part_attempts', 'lease_release']}))
            return
        raise TimeoutError('No safe boundary observed; serial execution left running')
    finally:
        if frozen:
            os.kill(pid, signal.SIGCONT)
        os.close(fd)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['batch-root', 'part', 'config']:
        parser.add_argument('--' + name, required=True)
    main(parser.parse_args())
