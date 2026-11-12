# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Harbor 0.25 ACK lifecycle with explicit receipts and no implicit task replay.

Only prebuilt, digest-pinned, single-container Linux tasks are accepted. Harbor
still prepares task files and runs its official verifier. This adapter neither
changes task tests nor grants cluster credentials to the task container.
"""
import asyncio
import base64
import hashlib
import io
import json
from pathlib import Path
import tarfile
import uuid
import re
import shlex

from harbor.environments.ack import ACKEnvironment
from harbor.environments.base import ExecResult
from kubernetes.client.rest import ApiException
from kubernetes.stream import stream

from sandbox_eval.exec_transport import ExecutionOutcomeUnknown, read_complete_stream
from sandbox_eval.artifact_transfer import download_snapshot


class _CreateOnce:
    def __init__(self, api):
        self.api = api

    def __getattr__(self, name):
        return getattr(self.api, name)

    def create_namespaced_pod(self, *args, **kwargs):
        try:
            return self.api.create_namespaced_pod(*args, **kwargs)
        except ApiException as error:
            # Upstream ACK's 409 recovery deletes/recreates the old pod. A prior
            # attempt can contain unreported side effects, so preserve it instead.
            if error.status == 409:
                raise ExecutionOutcomeUnknown('Sandbox already exists; inspect its original trial') from error
            raise


class ManagedACKEnvironment(ACKEnvironment):
    def __init__(self, *args, default_exec_timeout_sec=600, **kwargs):
        super().__init__(*args, **kwargs)
        if default_exec_timeout_sec <= 0:
            raise ValueError("Execution timeout must be positive")
        self.default_exec_timeout_sec = default_exec_timeout_sec
        if not re.fullmatch(r'[^\s@]+@sha256:[0-9a-f]{64}', self.task_env_config.docker_image or ''):
            raise ValueError('Task image must be pinned by digest')
        if not self.task_env_config.cpus or not self.task_env_config.memory_mb or not self.task_env_config.storage_mb:
            raise ValueError('CPU, memory and storage must be declared before admission')
        if self.task_env_config.gpus or self.task_env_config.tpu:
            raise ValueError('This initial profile only supports CPU tasks')
        if self.use_sandbox_claim:
            raise ValueError('Shared sandbox pools are outside this evaluation profile')
        overrides = self.pod_overrides
        spec = overrides.setdefault('spec', {})
        spec['automountServiceAccountToken'] = False
        spec['serviceAccountName'] = 'task-sandbox'
        if spec.get('hostNetwork') or spec.get('hostPID') or spec.get('hostIPC'):
            raise ValueError('Task sandbox must not share host namespaces')
        if any('emptyDir' not in volume for volume in spec.get('volumes', [])):
            raise ValueError('Only fresh emptyDir task volumes are allowed')
        if spec.get('initContainers') or len(spec.get('containers', [{}])) != 1:
            raise ValueError('This initial profile only supports a single task container')
        container = spec.setdefault('containers', [{}])[0]
        if container.get('envFrom') or any('valueFrom' in item for item in container.get('env', [])):
            raise ValueError('Task containers must not import controller credentials or configuration')
        security = container.setdefault('securityContext', {})
        security.update(privileged=False, allowPrivilegeEscalation=False)
        resources = container.setdefault('resources', {})
        resources.setdefault('limits', {}).update(cpu=self.cpu_request, memory=self.memory_request)
        resources.setdefault('requests', {}).update(cpu=self.cpu_request, memory=self.memory_request)
        labels = overrides.setdefault('metadata', {}).setdefault('labels', {})
        labels.update({'managed-agents/evaluation': 'terminal-swe-20261103',
                       'alibabacloud.com/compute-class': 'general-purpose',
                       'alibabacloud.com/compute-qos': 'default'})
        self._command_lock = asyncio.Lock()

    async def _ensure_client(self):
        await super()._ensure_client()
        if not isinstance(self._core_api, _CreateOnce):
            self._core_api = _CreateOnce(self._core_api)

    async def start(self, force_build=False):
        if force_build:
            raise ValueError('Build and validate images separately before admitting a trial')
        await super().start(False)

    async def _wait_for_pod_ready(self, timeout_sec=None):
        # Large pinned SWE images can take more than ACK's default 300 seconds
        # to pull. Use the task's environment preparation budget, before a
        # Session is admitted; model and verifier deadlines remain unchanged.
        timeout = self.task_env_config.build_timeout_sec if timeout_sec is None else timeout_sec
        if timeout <= 0:
            raise ValueError('Pod preparation timeout must be positive')
        await super()._wait_for_pod_ready(int(timeout))

    async def exec(self, command, cwd=None, env=None, timeout_sec=None, user=None):
        # No model command is retried. The separate terminal process protocol
        # supplies asynchronous execution, polling, PTY input and cancellation.
        await self._ensure_client()
        timeout = self.default_exec_timeout_sec if timeout_sec is None else timeout_sec
        if timeout <= 0:
            raise ValueError('Execution timeout must be positive')
        assignments = self._merge_env(env) or {}
        argv = ['env', *[str(key) + '=' + str(value) for key, value in assignments.items()],
                'timeout', '--signal=TERM', '--kill-after=5s', str(timeout),
                'bash', '-lc', command]
        full = shlex.join(argv)
        directory = cwd or self.task_env_config.workdir
        if directory:
            full = 'cd ' + shlex.quote(str(directory)) + ' && ' + full
        resolved_user = self._resolve_user(user)
        if resolved_user is not None:
            if isinstance(resolved_user, int):
                user_arg = '"$(getent passwd ' + str(resolved_user) + ' | cut -d: -f1)"'
            else:
                user_arg = shlex.quote(resolved_user)
            full = 'su ' + user_arg + ' -s /bin/bash -c ' + shlex.quote(full)
        async with self._command_lock:
            try:
                connection = await asyncio.to_thread(
                    stream, self._exec_api.connect_get_namespaced_pod_exec,
                    self.pod_name, self.namespace, command=['sh', '-c', full],
                    stderr=True, stdin=False, stdout=True, tty=False, _preload_content=False)
                stdout, stderr, code = await asyncio.to_thread(read_complete_stream, connection, timeout + 15)
                return ExecResult(stdout=stdout, stderr=stderr, return_code=code)
            except ExecutionOutcomeUnknown:
                raise
            except Exception as error:
                raise ExecutionOutcomeUnknown('Sandbox command was not confirmed; do not replay it') from error

    async def _upload_archive(self, payload, target_dir):
        # ACK upstream closes tar streams after one second and retries writes.
        # Here each chunk has a confirmed exit, then the full archive is hashed.
        # A lost response leaves its temp file for inspection, never retries a write.
        remote = '/tmp/managed-transfer-' + uuid.uuid4().hex + '.tar'
        result = await self.exec('umask 077; set -o noclobber; : > ' + shlex.quote(remote), timeout_sec=30)
        if result.return_code != 0:
            raise RuntimeError('Cannot create fresh transfer artifact')
        for offset in range(0, len(payload), 32768):
            chunk = base64.b64encode(payload[offset:offset + 32768]).decode()
            result = await self.exec('printf %s ' + shlex.quote(chunk) + ' | base64 -d >> ' +
                                     shlex.quote(remote), timeout_sec=30)
            if result.return_code != 0:
                raise ExecutionOutcomeUnknown('Upload chunk did not complete; preserve artifact')
        expected = hashlib.sha256(payload).hexdigest()
        result = await self.exec('sha256sum ' + shlex.quote(remote), timeout_sec=30)
        if result.return_code != 0 or result.stdout.split()[0] != expected:
            raise ExecutionOutcomeUnknown('Uploaded archive hash mismatch')
        command = ('mkdir -p -- ' + shlex.quote(str(target_dir)) + ' && tar xf ' +
                   shlex.quote(remote) + ' -C ' + shlex.quote(str(target_dir)))
        result = await self.exec(command, timeout_sec=60)
        if result.return_code != 0:
            raise ExecutionOutcomeUnknown('Archive extraction did not complete')
        await self.exec('rm -- ' + shlex.quote(remote), timeout_sec=30)

    async def upload_file(self, source_path, target_path):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode='w') as archive:
            archive.add(str(source_path), arcname=Path(target_path).name)
        await self._upload_archive(buffer.getvalue(), Path(target_path).parent)

    async def upload_dir(self, source_dir, target_dir):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode='w') as archive:
            for child in sorted(Path(source_dir).iterdir()):
                archive.add(str(child), arcname=child.name)
        await self._upload_archive(buffer.getvalue(), target_dir)

    async def download_dir(self, source_dir, target_dir):
        target = Path(target_dir)
        target.parent.mkdir(parents=True, exist_ok=True)
        identity = uuid.uuid4().hex
        remote = '/tmp/managed-export-' + identity + '.tar.gz'
        partial = target.parent / ('.' + target.name + '-' + identity + '.tar.gz.partial')
        receipt = partial.with_suffix('.json')
        receipt.write_text(json.dumps({'source': str(source_dir), 'source_pod': self.pod_name,
                                       'remote_path': remote, 'complete': False}))
        # Snapshot once, then read bounded chunks and validate the complete
        # digest. Unknown transfers retain both snapshots for inspection.
        result = await self.exec('set -e; set -o noclobber; tar czf - -C ' +
                                 shlex.quote(str(source_dir)) + ' . > ' + shlex.quote(remote), timeout_sec=120)
        if result.return_code != 0:
            raise ExecutionOutcomeUnknown('Artifact export did not complete')
        metadata = await download_snapshot(self, remote, partial)
        try:
            with tarfile.open(partial, mode='r:gz') as archive:
                archive.extractall(target, filter='data')
        except (ValueError, tarfile.TarError) as error:
            raise ExecutionOutcomeUnknown('Invalid artifact archive') from error
        receipt.write_text(json.dumps({**metadata, 'source': str(source_dir),
                                       'source_pod': self.pod_name, 'complete': True}))
        partial.unlink()
