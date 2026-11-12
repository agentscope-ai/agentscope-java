# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Admission guards for the independent SkillsBench preparation path."""
import asyncio
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, Mock, patch

from sandbox_eval.skillsbench_inventory import task_digest, verified_tasks


class ReleaseIntegrityTests(unittest.TestCase):
    def test_renaming_same_content_changes_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'task.md').write_text('same content')
            first, files = task_digest(root)
            expected = hashlib.sha256(b'task.md\0' + hashlib.sha256(b'same content').digest())
            self.assertEqual(first, 'sha256:' + expected.hexdigest())
            self.assertEqual(set(files), {'task.md'})
            (root / 'task.md').rename(root / 'oracle.py')
            self.assertNotEqual(first, task_digest(root)[0])

    def test_symlink_cannot_import_private_controller_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'link').symlink_to('/etc/passwd')
            with self.assertRaisesRegex(ValueError, 'Symlink'):
                task_digest(root)

    def test_duplicate_roster_rejected_before_filesystem_access(self):
        registry = {'name': 'skillsbench', 'version': '1.1',
                    'tasks': [{'name': 'duplicate'} for _ in range(87)]}
        with self.assertRaisesRegex(ValueError, '87 unique'):
            verified_tasks(registry, '/not-a-dataset')


class PodIdentityTests(unittest.IsolatedAsyncioTestCase):
    async def test_privileged_exec_does_not_source_agent_controlled_bash_env(self):
        from sandbox_eval.benchflow_ack import BenchflowACK
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            marker = root / 'sourced-as-controller'
            startup = root / 'startup.sh'
            startup.write_text('touch ' + str(marker) + '\n')
            env = BenchflowACK.__new__(BenchflowACK)
            env.uid, env.pod_name, env.namespace = 'uid', 'trial', 'test'
            env.task_env_config = SimpleNamespace(workdir=None)
            env._lock, env.api, env.exec_api = asyncio.Lock(), Mock(), Mock()
            env.api.read_namespaced_pod.return_value = SimpleNamespace(
                metadata=SimpleNamespace(uid='uid'))

            def execute(*args, **kwargs):
                return subprocess.run(kwargs['command'], capture_output=True, text=True)

            with patch.dict(os.environ, {'BASH_ENV': str(startup)}), \
                    patch('sandbox_eval.benchflow_ack.stream', side_effect=execute), \
                    patch('sandbox_eval.benchflow_ack.read_complete_stream',
                          side_effect=lambda p, _: (p.stdout, p.stderr, p.returncode)):
                result = await env.exec('printf verifier', user='root')
            self.assertEqual(result.return_code, 0)
            self.assertEqual(result.stdout, 'verifier')
            self.assertFalse(marker.exists())

    async def test_existing_pod_is_not_deleted_or_recreated(self):
        from kubernetes.client.rest import ApiException
        from sandbox_eval.benchflow_ack import BenchflowACK
        with tempfile.TemporaryDirectory() as directory:
            env = BenchflowACK.__new__(BenchflowACK)
            env.pod_name, env.namespace, env.image = 'trial', 'test', 'image'
            env.receipt_dir = Path(directory)
            env.task_env_config = SimpleNamespace(env={}, cpus=1, memory_mb=4096,
                                                  storage_mb=10240, build_timeout_sec=1)
            env.api = Mock()
            env.api.create_namespaced_pod.side_effect = ApiException(status=409)
            with self.assertRaises(ApiException):
                await env.start()
            env.api.create_namespaced_pod.assert_called_once()
            env.api.delete_namespaced_pod.assert_not_called()
            pod = env.api.create_namespaced_pod.call_args.args[1]
            self.assertNotIn('command', pod['spec']['containers'][0])
            self.assertEqual(pod['spec']['containers'][0]['args'][0], '/bin/sh')

    async def test_other_service_cannot_be_routed_to_main(self):
        from sandbox_eval.benchflow_ack import BenchflowACK
        env = BenchflowACK.__new__(BenchflowACK)
        with self.assertRaisesRegex(ValueError, 'single main'):
            await env.exec('touch /should-not-exist', service='database')


class AgentPrivilegeTests(unittest.IsolatedAsyncioTestCase):
    async def test_failed_process_fence_refuses_verifier_admission(self):
        from sandbox_eval.skillsbench_runtime import quiesce_agent
        environment = Mock(exec=AsyncMock(return_value=SimpleNamespace(
            return_code=1, stdout='still active', stderr='')))
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, 'verifier refused'):
                await quiesce_agent(environment, Path(directory))
            self.assertEqual(json.loads((Path(directory) / 'agent-process-fence.json').read_text())
                             ['return_code'], 1)

    async def test_terminal_cannot_choose_root(self):
        from sandbox_eval.skillsbench_runtime import AgentSandbox
        environment = Mock(exec=AsyncMock())
        sandbox = AgentSandbox(environment, '/app')
        with self.assertRaisesRegex(ValueError, 'non-root'):
            await sandbox.exec('id', user='root')
        environment.exec.assert_not_called()
        await sandbox.exec('id')
        environment.exec.assert_awaited_once_with('id', user='agent', cwd='/app')

    async def test_bridge_cannot_write_outside_its_directory(self):
        from sandbox_eval.skillsbench_runtime import AgentSandbox
        environment = Mock(upload_file=AsyncMock())
        sandbox = AgentSandbox(environment, '/app')
        with self.assertRaisesRegex(ValueError, 'terminal directory'):
            await sandbox.upload_file('/tmp/request', '/verifier/test.sh')
        environment.upload_file.assert_not_called()


class ReferenceAdmissionTests(unittest.TestCase):
    def test_new_output_does_not_allow_duplicate_slot(self):
        from sandbox_eval.run_skillsbench import claim_slot
        with tempfile.TemporaryDirectory() as directory:
            entry = {'task': 'task', 'digest': 'verified'}
            claim_slot(directory, 'pi', entry, 'first-controller')
            with self.assertRaises(FileExistsError):
                claim_slot(directory, 'pi', entry, 'another-controller')
            receipt = json.loads((Path(directory) / 'pi--task.json').read_text())
            self.assertEqual(receipt['trial'], 'first-controller')
            claim_slot(directory, 'codex', entry, 'distinct-framework')

    def test_unapproved_no_skill_condition_is_rejected(self):
        from sandbox_eval.run_skillsbench import validate_entry
        with self.assertRaisesRegex(ValueError, 'authorized'):
            validate_entry({'skill_mode': 'no-skill', 'repeat': 0}, '/unused')

    def test_reference_for_another_image_cannot_admit_model(self):
        from evaluation import digest
        from sandbox_eval.run_skillsbench import validate_entry
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            receipt = {'complete': True, 'reward': 1, 'model_calls': 0}
            (root / 'reference-result.json').write_text(json.dumps(receipt))
            (root / 'reference-intent.json').write_text(json.dumps({
                'image': 'another-image', 'task_digest': 'digest'}))
            entry = {'task': 'task', 'skill_mode': 'with-skill', 'repeat': 0,
                     'digest': 'digest', 'files': {}, 'image': 'accepted-image',
                     'reference_receipt': str(root / 'reference-result.json'),
                     'reference_receipt_hash': digest(receipt)}
            with patch('sandbox_eval.run_skillsbench.task_digest', return_value=('digest', {})):
                with self.assertRaisesRegex(ValueError, 'reference acceptance'):
                    validate_entry(entry, root)


if __name__ == '__main__':
    unittest.main()
