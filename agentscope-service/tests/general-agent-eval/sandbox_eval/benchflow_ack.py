# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""BenchFlow sandbox protocol over a single isolated, digest-pinned ACK Pod.

This module deliberately does not import Harbor: its pinned dependency set
conflicts with BenchFlow 0.6.3. The receipt reader and snapshot protocol are the
same ones used by the existing terminal bridge. Unknown executions never retry.
"""
import asyncio
import base64
import hashlib
import io
import json
from pathlib import Path
import re
import shlex
import tarfile
import time
from types import SimpleNamespace
import uuid

from kubernetes import client, config
from kubernetes.stream import stream

from sandbox_eval.artifact_transfer import download_snapshot
from sandbox_eval.exec_transport import ExecutionOutcomeUnknown, read_complete_stream


class BenchflowACK:
    is_mounted = False
    supports_snapshot = False

    def __init__(self, *, pod_name, namespace, image, environment, receipt_dir):
        if not re.fullmatch(r'[^\s@]+@sha256:[0-9a-f]{64}', image):
            raise ValueError('A verified image digest is required')
        if environment.gpus or environment.tpu:
            raise ValueError('GPU/TPU tasks require a separately accepted adapter')
        if not all((environment.cpus, environment.memory_mb, environment.storage_mb)):
            raise ValueError('Explicit task resources are required')
        config.load_incluster_config()
        self.api = client.CoreV1Api()
        # Kubernetes stream mutates its API client's request function; keep a
        # dedicated execution client rather than sharing it with lifecycle calls.
        self.exec_api = client.CoreV1Api(client.ApiClient())
        self.pod_name, self.namespace, self.image = pod_name, namespace, image
        self.task_env_config = environment
        self.receipt_dir = Path(receipt_dir)
        self.receipt_dir.mkdir(parents=True, exist_ok=True)
        self._lock = asyncio.Lock()
        self.uid = None

    @staticmethod
    def _service(service):
        if service != 'main':
            raise ValueError('Only the single main service has been accepted')

    async def start(self):
        env = self.task_env_config
        if env.env:
            raise ValueError('Environment variables require explicit reviewed resolution')
        pod = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': self.pod_name,
               'labels': {'managed-agents/evaluation': 'skillsbench-20261106',
                          'alibabacloud.com/compute-class': 'general-purpose',
                          'alibabacloud.com/compute-qos': 'default'}},
               'spec': {'automountServiceAccountToken': False, 'serviceAccountName': 'task-sandbox',
                        'restartPolicy': 'Never', 'containers': [{
                            'name': 'task', 'image': self.image,
                            # Preserve image ENTRYPOINT (some tasks start their
                            # bundled API there); replace only the default CMD.
                            'args': ['/bin/sh', '-c', 'while :; do sleep 3600; done'],
                            'securityContext': {'privileged': False, 'allowPrivilegeEscalation': False},
                            'resources': {'requests': {'cpu': str(env.cpus),
                                'memory': str(env.memory_mb) + 'Mi',
                                'ephemeral-storage': str(env.storage_mb) + 'Mi'},
                                'limits': {'cpu': str(env.cpus), 'memory': str(env.memory_mb) + 'Mi'}}}]}}
        with (self.receipt_dir / 'create-intent.json').open('x') as out:
            json.dump(pod, out)
        # A 409 is not recovered by deleting someone else's existing trial.
        created = await asyncio.to_thread(self.api.create_namespaced_pod, self.namespace, pod)
        self.uid = created.metadata.uid
        (self.receipt_dir / 'created.json').write_text(json.dumps({'uid': self.uid,
            'pod': self.pod_name, 'namespace': self.namespace, 'image': self.image}))
        deadline = time.monotonic() + env.build_timeout_sec
        while time.monotonic() < deadline:
            current = await asyncio.to_thread(self.api.read_namespaced_pod, self.pod_name, self.namespace)
            if current.metadata.uid != self.uid:
                raise ExecutionOutcomeUnknown('Task Pod identity changed')
            if any(c.type == 'Ready' and c.status == 'True' for c in current.status.conditions or []):
                return
            if current.status.phase in ('Failed', 'Succeeded'):
                raise RuntimeError('Task Pod terminated during preparation')
            await asyncio.sleep(2)
        raise TimeoutError('Official task environment preparation deadline exceeded')

    async def exec(self, command=None, *, cmd=None, cwd=None, env=None, timeout_sec=600,
                   user=None, service='main'):
        self._service(service)
        if timeout_sec <= 0:
            raise ValueError('Positive execution timeout required')
        command = command if command is not None else cmd
        if not isinstance(command, str):
            raise ValueError('Command must be text')
        directory = cwd or self.task_env_config.workdir
        if directory:
            command = 'cd ' + shlex.quote(directory) + ' && ' + command
        # Some official workspaces are /root and become agent-writable during
        # setup. Privileged verifier/control commands must not source profiles
        # from that workspace. Keep the image's inherited PATH and environment.
        privileged = user in (None, 'root', 0)
        argv = ['env', *(['-u', 'BASH_ENV', '-u', 'ENV'] if privileged else []),
                *[str(k) + '=' + str(v) for k, v in (env or {}).items()],
                'timeout', '--signal=TERM', '--kill-after=5s', str(timeout_sec), 'bash',
                *(['--noprofile', '--norc', '-c'] if privileged else ['-lc']), command]
        full = shlex.join(argv)
        if user not in (None, 'root', 0):
            if not isinstance(user, str) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_-]*', user):
                raise ValueError('Unsupported sandbox user')
            full = shlex.join(['su', user, '-s', '/bin/bash', '-c', full])
        async with self._lock:
            try:
                if not self.uid:
                    raise ExecutionOutcomeUnknown('No confirmed task Pod identity')
                current = await asyncio.to_thread(self.api.read_namespaced_pod, self.pod_name, self.namespace)
                if current.metadata.uid != self.uid:
                    raise ExecutionOutcomeUnknown('Task Pod identity changed before execution')
                connection = await asyncio.to_thread(stream,
                    self.exec_api.connect_get_namespaced_pod_exec,
                    self.pod_name, self.namespace, container='task', command=['sh', '-c', full],
                    stderr=True, stdin=False, stdout=True, tty=False, _preload_content=False)
                stdout, stderr, code = await asyncio.to_thread(read_complete_stream, connection, timeout_sec + 15)
                return SimpleNamespace(stdout=stdout, stderr=stderr, return_code=code)
            except ExecutionOutcomeUnknown:
                raise
            except Exception as error:
                raise ExecutionOutcomeUnknown('Task command outcome unknown; do not replay') from error

    async def _upload(self, payload, target_dir):
        remote = '/tmp/skillsbench-upload-' + uuid.uuid4().hex + '.tar'
        result = await self.exec('set -o noclobber; : > ' + shlex.quote(remote), timeout_sec=30)
        if result.return_code:
            raise ExecutionOutcomeUnknown('Cannot create upload artifact')
        for offset in range(0, len(payload), 32768):
            chunk = base64.b64encode(payload[offset:offset + 32768]).decode()
            result = await self.exec('printf %s ' + shlex.quote(chunk) + ' | base64 -d >> ' +
                                     shlex.quote(remote), timeout_sec=30)
            if result.return_code:
                raise ExecutionOutcomeUnknown('Upload chunk failed; preserve artifact')
        result = await self.exec('sha256sum ' + shlex.quote(remote), timeout_sec=30)
        if result.return_code or result.stdout.split()[0] != hashlib.sha256(payload).hexdigest():
            raise ExecutionOutcomeUnknown('Upload digest mismatch')
        result = await self.exec('mkdir -p -- ' + shlex.quote(str(target_dir)) + ' && tar xf ' +
                                 shlex.quote(remote) + ' -C ' + shlex.quote(str(target_dir)), timeout_sec=60)
        if result.return_code:
            raise ExecutionOutcomeUnknown('Upload extraction failed')

    async def upload_file(self, source_path, target_path):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode='w') as archive:
            archive.add(source_path, arcname=Path(target_path).name)
        await self._upload(buffer.getvalue(), Path(target_path).parent)

    async def upload_dir(self, source_dir, target_dir, service='main'):
        self._service(service)
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode='w') as archive:
            for child in sorted(Path(source_dir).iterdir()):
                archive.add(child, arcname=child.name)
        await self._upload(buffer.getvalue(), target_dir)

    async def download_file(self, source_path, target_path):
        target = Path(target_path)
        target.parent.mkdir(parents=True, exist_ok=True)
        snapshot = '/tmp/skillsbench-download-' + uuid.uuid4().hex
        result = await self.exec('cp -- ' + shlex.quote(str(source_path)) + ' ' + shlex.quote(snapshot))
        if result.return_code:
            raise ExecutionOutcomeUnknown('File snapshot failed')
        partial = target.with_name(target.name + '.' + uuid.uuid4().hex + '.partial')
        await download_snapshot(self, snapshot, partial)
        partial.replace(target)

    async def download_dir(self, source_dir, target_dir, service='main'):
        self._service(service)
        target = Path(target_dir)
        target.parent.mkdir(parents=True, exist_ok=True)
        identity = uuid.uuid4().hex
        snapshot = '/tmp/skillsbench-export-' + identity + '.tar.gz'
        partial = target.parent / ('.' + target.name + '-' + identity + '.tar.gz.partial')
        result = await self.exec('set -o noclobber; tar czf - -C ' + shlex.quote(str(source_dir)) +
                                 ' . > ' + shlex.quote(snapshot), timeout_sec=120)
        if result.return_code:
            raise ExecutionOutcomeUnknown('Directory snapshot failed')
        metadata = await download_snapshot(self, snapshot, partial)
        with tarfile.open(partial, mode='r:gz') as archive:
            archive.extractall(target, filter='data')
        partial.with_suffix('.json').write_text(json.dumps({**metadata, 'pod_uid': self.uid,
                                                          'source': str(source_dir), 'complete': True}))
        partial.unlink()

    async def stop(self, *, delete=True):
        if not delete or not self.uid:
            return
        # The caller closes all exact model/tool requests before cleanup.
        await asyncio.to_thread(self.api.delete_namespaced_pod, self.pod_name, self.namespace,
            body=client.V1DeleteOptions(preconditions=client.V1Preconditions(uid=self.uid)))
