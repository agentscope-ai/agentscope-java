# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
import asyncio
import shlex
import subprocess
import sys
import tempfile
from pathlib import Path
from types import SimpleNamespace
import unittest

from sandbox_eval.preparation import apt_mirror_text, prepare_network, prepare_runtime


class PreparationTest(unittest.TestCase):
    def test_preserves_distribution_and_signature_configuration(self):
        original = ('Types: deb\nURIs: http://deb.debian.org/debian-security\n'
                    'Suites: bookworm-security\nComponents: main\n'
                    'Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg\n')
        self.assertEqual(apt_mirror_text(original), original.replace(
            'http://deb.debian.org/debian-security', 'https://mirrors.aliyun.com/debian-security'))

    def test_does_not_rewrite_other_repositories_or_rewrite_twice(self):
        text = 'deb https://deb.debian.org/debian-extra bookworm main\n'
        self.assertEqual(apt_mirror_text(text), text)
        text = 'deb http://archive.ubuntu.com/ubuntu/ noble main\n'
        changed = apt_mirror_text(text)
        self.assertEqual(changed, 'deb https://mirrors.aliyun.com/ubuntu/ noble main\n')
        self.assertEqual(apt_mirror_text(changed), changed)


class PythonDistributionTest(unittest.IsolatedAsyncioTestCase):
    async def test_scoped_proxy_does_not_overwrite_existing_apt_policy(self):
        with tempfile.TemporaryDirectory() as root:
            target = Path(root) / 'proxy.conf'
            class Environment:
                async def exec(self, command, timeout_sec):
                    script = shlex.split(command)[2]
                    source = "Path('/etc/apt/apt.conf.d/99managed-eval-debian-proxy')"
                    if source not in script:
                        return SimpleNamespace(return_code=0, stdout='{}')
                    script = script.replace(source, 'Path(' + repr(str(target)) + ')')
                    result = await asyncio.to_thread(subprocess.run, [sys.executable, '-c', script],
                                                     capture_output=True, text=True)
                    return SimpleNamespace(return_code=result.returncode, stdout=result.stdout)
            await prepare_network(Environment(), 'aliyun-apt-pypi-debian-proxy-v1')
            self.assertEqual(target.read_text(),
                'Acquire::http::Proxy::deb.debian.org '
                '"http://benchmark-debian-distribution.managed-agents-benchmark.svc.cluster.local:9014";\n')
            target.write_text('existing administrator policy')
            with self.assertRaises(RuntimeError):
                await prepare_network(Environment(), 'aliyun-apt-pypi-debian-proxy-v1')
            self.assertEqual(target.read_text(), 'existing administrator policy')

    async def test_declared_mirror_keeps_pip_certificate_and_version_options(self):
        with tempfile.TemporaryDirectory() as root:
            target = Path(root) / 'pip.conf'
            target.write_text('[global]\ncert=/etc/pki/custom.pem\n[install]\nconstraint=/opt/pins.txt\n')
            class Environment:
                async def exec(self, command, timeout_sec):
                    script = shlex.split(command)[2]
                    if "Path('/etc/pip.conf')" not in script:
                        return SimpleNamespace(return_code=0, stdout='{}')
                    script = script.replace("Path('/etc/pip.conf')", 'Path(' + repr(str(target)) + ')')
                    result = await asyncio.to_thread(subprocess.run, [sys.executable, '-c', script],
                                                     capture_output=True, text=True)
                    return SimpleNamespace(return_code=result.returncode, stdout=result.stdout)
            receipt = await prepare_network(Environment(), 'aliyun-apt-pypi-v1')
            text = target.read_text()
            self.assertIn('cert = /etc/pki/custom.pem', text)
            self.assertIn('constraint = /opt/pins.txt', text)
            self.assertIn('index-url = https://mirrors.aliyun.com/pypi/simple/', text)
            self.assertNotIn('trusted-host', text)
            self.assertNotEqual(receipt['files']['/etc/pip.conf']['before'],
                                receipt['files']['/etc/pip.conf']['after'])


class RuntimePreparationTest(unittest.IsolatedAsyncioTestCase):
    async def test_runtime_requires_successful_install_and_independent_version_receipt(self):
        class Environment:
            def __init__(self, code): self.code, self.calls = code, []
            async def exec(self, command, timeout_sec):
                self.calls.append(command)
                return SimpleNamespace(return_code=self.code, stderr='',
                    stdout='Python 3.13.9\n' if command=='python3 --version' else 'archive: OK\n')
        success = Environment(0)
        self.assertEqual((await prepare_runtime(success,'python3139-standalone-v1'))['python_version'],'3.13.9')
        self.assertEqual(len(success.calls),2)
        failed = Environment(1)
        with self.assertRaises(RuntimeError): await prepare_runtime(failed,'python3139-standalone-v1')
        self.assertEqual(len(failed.calls),1)


if __name__ == '__main__':
    unittest.main()
