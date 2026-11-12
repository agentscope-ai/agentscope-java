# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""One replaceable environment process group; state and locks live on the PVC."""
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


def main():
    root = Path('/eval')
    code = root / 'code'
    logs = root / 'service-logs'
    logs.mkdir(exist_ok=True)
    env = {**os.environ, 'APPWORLD_ROOT': '/eval/appworld-root',
           'EVAL_LEASE_DB': '/eval/environment-leases.sqlite',
           'EVAL_GATEWAY_DB': '/eval/evaluation-gateway.sqlite', 'PYTHONUNBUFFERED': '1'}
    app = '/eval/venvs/appworld/bin/appworld'
    tau = '/eval/venvs/tau-bench/bin/python'
    commands = [
        ('apis', ['/eval/venvs/appworld/bin/python', str(code / 'appworld_api.py')]),
        ('mcp', [app, 'serve', 'mcp', 'http', '--output-type', 'content_only', '--root', '/eval/appworld-root', '--port', '10000', '--remote-apis-url', 'http://127.0.0.1:9000']),
        ('tau', [tau, str(code / 'tau_environment.py')]),
        ('fixture', [tau, str(code / 'acceptance_tools.py')]),
        ('audit', [tau, str(code / 'evaluation_gateway.py'), '--state', env['EVAL_GATEWAY_DB'],
                   '--leases', env['EVAL_LEASE_DB'], '--profile', str(code / 'active-evaluation-profile.json'),
                   '--upstream', 'https://dashscope.aliyuncs.com/compatible-mode/v1'])]
    if env.get('EVAL_TERMINAL_BACKEND'):
        commands[-1][1].extend(['--terminal', env['EVAL_TERMINAL_BACKEND']])
    children = []
    def stop(*_):
        for process in children:
            if process.poll() is None:
                process.terminate()
        deadline = time.monotonic() + 10
        for process in children:
            try:
                process.wait(timeout=max(.1, deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                process.kill()
        raise SystemExit(1)
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    for name, command in commands:
        with (logs / (name + '.log')).open('ab') as log:
            children.append(subprocess.Popen(command, cwd=code, env=env, stdout=log, stderr=log))
    while True:
        if any(p.poll() is not None for p in children):
            stop()
        time.sleep(1)


if __name__ == '__main__':
    main()
