#!/usr/bin/env python3
"""Public Hosted Team Job with real Agent Service/Go/PostgreSQL and a fenced fake Host.

Use a dedicated local schema. The configuration source is a fixture and the
control-plane process is stopped before execution. No provider/model is called.
Build Agent Service, Go runtime binaries, control-plane/sdk/dsh and java-client
(including dependency:build-classpath output) before running; public Python,
compiled DSH and Java clients are exercised through real HTTP.
"""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
from urllib.error import URLError
from urllib.parse import urlparse

from orchestration_runtime_host_smoke import ROOT, SERVICE_SECRET, SIGNING_SECRET, port, request
from orchestration_runtime_native_smoke import seed, run as native_run


def run(version, dsn, cancel=False, team=True, daemon=False, approval=None):
    if urlparse(dsn).hostname not in ('127.0.0.1', 'localhost'):
        raise ValueError('Use a dedicated loopback PostgreSQL fixture')
    kind = 'team' if team else 'hosted'
    folder = Path(tempfile.mkdtemp(prefix='managed-agents-' + kind + '-runtime-smoke-'))
    environment = {'PATH': os.environ['PATH'], 'TZ': 'UTC', 'MANAGED_AGENTS_INTERNAL_TOKEN': SERVICE_SECRET,
                   'MANAGED_AGENTS_JWT_SECRET': SIGNING_SECRET,
                   'MANAGED_AGENTS_VAULT_MASTER_KEY': 'team-smoke-runtime-encryption-key-only'}
    if os.environ.get('JAVA_HOME'):
        environment['JAVA_HOME'] = os.environ['JAVA_HOME']
    processes, handles, passed = [], [], False

    def stop(process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    def start(name, arguments, health):
        handle = (folder / (name+'.log')).open('a')
        handles.append(handle)
        process = subprocess.Popen(arguments, env=environment, stdout=handle, stderr=subprocess.STDOUT)
        processes.append(process)
        deadline = time.monotonic()+45
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(name+' exited; inspect '+str(folder))
            try:
                if request(health)[0] == 200:
                    return process
            except (URLError, TimeoutError):
                pass
            time.sleep(0.2)
        raise RuntimeError(name+' did not become ready; inspect '+str(folder))

    try:
        with (folder / 'migration.log').open('w') as migration_log:
            subprocess.run([str(ROOT/'control-plane/bin/control-plane'),
                '--storage-driver=postgres', '--storage-dsn='+dsn, '--migrate-only'], env=environment,
                stdout=migration_log, stderr=subprocess.STDOUT, check=True, timeout=90)
        control_port, runtime_port, agent_service_port = port(), port(), port()
        control_url = 'http://127.0.0.1:'+str(control_port)
        runtime_url = 'http://127.0.0.1:'+str(runtime_port)
        control = start('seed-control', [str(ROOT/'control-plane/bin/control-plane'),
            '--enable-kubernetes=false', '--enable-product=false', '--enable-runtime-protocol=false',
            '--local-dev=true', '--scope-mode=single', '--default-tenant=fixture-tenant',
            '--default-namespace=fixture-space', '--enable-runtime-workers=false',
            '--storage-driver=postgres', '--storage-dsn='+dsn,
            '--http-bind-address=127.0.0.1:'+str(control_port),
            '--metrics-bind-address=127.0.0.1:'+str(port()),
            '--health-probe-bind-address=127.0.0.1:'+str(port()),
            '--artifact-root='+str(folder/'artifacts')], control_url+'/readyz')
        fixture = folder/(kind + '.json')
        seed(control_url, fixture, 'job', team=team)
        stop(control)
        start('runtime', [str(ROOT/'control-plane/bin/control-plane'),
            '--enable-kubernetes=false', '--enable-runtime-protocol=false', '--scope-mode=multi', '--enable-runtime-workers=true',
            '--http-bind-address=127.0.0.1:'+str(runtime_port), '--storage-driver=postgres',
            '--storage-dsn='+dsn, '--agent-service-url=http://127.0.0.1:'+str(agent_service_port),
            '--artifact-root='+str(folder/'artifacts')], runtime_url+'/readyz')
        native_run(version, runtime_url, agent_service_port, fixture, exercise_cancel=cancel,
                   exercise_daemon=daemon, approval=approval, public_create=True, agent_service_dsn=dsn)
        passed = True
    finally:
        for process in reversed(processes):
            stop(process)
        for handle in handles:
            handle.close()
        if passed:
            shutil.rmtree(folder)
        else:
            print(kind.capitalize() + ' wrapper logs retained: '+str(folder))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--version', default='2.1.0-SNAPSHOT')
    parser.add_argument('--fixture-dsn', required=True, help='Dedicated local test PostgreSQL DSN')
    parser.add_argument('--cancel', action='store_true')
    args = parser.parse_args()
    run(args.version, args.fixture_dsn, args.cancel)
