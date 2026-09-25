# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock

import yaml

SERVICE = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('release', SERVICE / 'release/release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTests(unittest.TestCase):
    def test_frontend_verification_preserves_tracked_placeholder(self):
        self.check_frontend_placeholder(build_fails=False)

    def test_failed_frontend_build_preserves_tracked_placeholder(self):
        self.check_frontend_placeholder(build_fails=True)

    def check_frontend_placeholder(self, build_fails):
        with tempfile.TemporaryDirectory() as directory:
            service = Path(directory)
            placeholder = service / 'aistio/ui/.gitkeep'
            placeholder.parent.mkdir(parents=True)
            placeholder.write_bytes(b'original contents\n')

            def simulated_npm(*args, **kwargs):
                if args == ('npm', 'run', 'build'):
                    placeholder.unlink()
                    (placeholder.parent / 'index.html').write_text('built dashboard')
                    if build_fails:
                        raise subprocess.CalledProcessError(1, args)

            with mock.patch.object(release, 'SERVICE', service), mock.patch.object(release, 'run', side_effect=simulated_npm):
                if build_fails:
                    with self.assertRaises(subprocess.CalledProcessError):
                        release.verify_npm(service / 'frontend')
                else:
                    release.verify_npm(service / 'frontend')
            self.assertEqual(placeholder.read_bytes(), b'original contents\n')
            self.assertEqual((placeholder.parent / 'index.html').read_text(), 'built dashboard')

    def test_version_cannot_escape_output_path(self):
        for value in ('../other', 'v1.0.0', '1.0', '1.0.0;touch x', '1.0.0+meta'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                release.validate_version(value)
        self.assertEqual(release.validate_version('2.0.3-rc.1'), '2.0.3-rc.1')

    def test_init_is_private_and_preserves_existing_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / 'init-env.sh'
            shutil.copy2(SERVICE / 'deploy/init-env.sh', script)
            subprocess.run([str(script), '2.0.3-rc.1', 'example.com/team'], check=True, capture_output=True)
            env = script.parent / '.env'
            initial = env.read_bytes()
            self.assertEqual(env.stat().st_mode & 0o777, 0o600)
            subprocess.run([str(script), '2.0.4', 'example.com/other'], check=True, capture_output=True)
            self.assertEqual(env.read_bytes(), initial)
            settings = dict(line.split('=', 1) for line in initial.decode().splitlines())
            self.assertGreaterEqual(len(settings['AISTIO_BOOTSTRAP_PASSWORD']), 12)
            self.assertNotEqual(settings['BUILDER_JWT_SECRET'], settings['BUILDER_INTERNAL_TOKEN'])

    def test_chart_has_four_planes_shared_storage_and_no_demo_users(self):
        rendered = subprocess.check_output(['helm', 'template', 'test', str(SERVICE / 'helm/agentscope-service'),
                                           '--set', 'imageRepository=example.com/test', '--set', 'existingSecret=credentials'], text=True)
        objects = [o for o in yaml.safe_load_all(rendered) if o]
        deployments = [o for o in objects if o['kind'] == 'Deployment']
        self.assertEqual(len(deployments), 4)
        claims = []
        for dep in deployments:
            pod = dep['spec']['template']['spec']
            self.assertFalse(pod['automountServiceAccountToken'])
            container = pod['containers'][0]
            env = {e['name']: e['value'] for e in container['env']}
            if container['name'] == 'control':
                self.assertEqual(env['AISTIO_SEED_USERS'], 'false')
                self.assertEqual(env['AISTIO_ENABLE_KUBERNETES'], 'false')
            if container['name'] == 'gateway':
                self.assertNotIn('envFrom', container)
            else:
                claims.append(next(v for v in pod['volumes'] if v['name'] == 'workspaces')['persistentVolumeClaim']['claimName'])
        self.assertEqual(len(claims), 3)
        self.assertEqual(len(set(claims)), 1)
        for pvc in [o for o in objects if o['kind'] == 'PersistentVolumeClaim']:
            self.assertEqual(pvc['metadata']['annotations']['helm.sh/resource-policy'], 'keep')

    def test_chart_requires_registry_and_secret(self):
        result = subprocess.run(['helm', 'template', 'test', str(SERVICE / 'helm/agentscope-service')], capture_output=True)
        self.assertNotEqual(result.returncode, 0)

    def test_compose_has_no_build_or_public_internal_ports(self):
        compose = yaml.safe_load((SERVICE / 'deploy/compose.yaml').read_text())
        for name, service in compose['services'].items():
            self.assertNotIn('build', service)
            if name != 'gateway':
                self.assertNotIn('ports', service)
        self.assertEqual(compose['services']['control']['environment']['AISTIO_SEED_USERS'], 'false')


if __name__ == '__main__':
    unittest.main()
