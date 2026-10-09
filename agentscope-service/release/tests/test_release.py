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
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import tarfile
import unittest
from types import SimpleNamespace
from unittest import mock

import yaml

SERVICE = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('release', SERVICE / 'release/release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTests(unittest.TestCase):
    def test_distribution_packages_include_installation_inputs_without_local_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            service = Path(directory)
            for name in ('deploy', 'helm/agentscope-service', 'release'):
                (service / name).parent.mkdir(parents=True, exist_ok=True)
                shutil.copytree(SERVICE / name, service / name,
                                ignore=shutil.ignore_patterns('dist', '__pycache__'))
            (service / 'deploy/.env').write_text('PRIVATE_VALUE=must-not-ship\n')
            args = SimpleNamespace(version='2.1.0-BETA1', repository='example.com/team',
                                   output=service / 'dist', platforms='darwin/arm64', distributions_only=True)
            original_run = release.run

            def build(*command, **kwargs):
                if command[:2] == ('go', 'build'):
                    output = Path(command[command.index('-o') + 1])
                    output.write_bytes(b'fake executable')
                    output.chmod(0o755)
                    return
                self.assertEqual(command[:2], ('helm', 'package'))
                return original_run(*command, **kwargs)

            with mock.patch.object(release, 'SERVICE', service), mock.patch.object(release, 'run', side_effect=build), mock.patch.object(release, 'manifest', return_value={'sourceDirty': False, 'platforms': {}}):
                release.package(args)
            for archive in args.output.glob('*.tar.gz'):
                with tarfile.open(archive) as package:
                    self.assertFalse(any(Path(name).name == '.env' for name in package.getnames()))
            with tarfile.open(args.output / 'agentscope-service-2.1.0-BETA1-kubernetes.tar.gz') as package:
                prefix = 'agentscope-service-kubernetes/'
                for name in ('agentscope-service-2.1.0-BETA1.tgz', 'kubernetes.env.example', 'postgres-init.sql'):
                    self.assertIn(prefix + name, package.getnames())
                readme = package.extractfile(prefix + 'README.md').read().decode()
                self.assertIn('--set imageRepository=example.com/team', readme)
                self.assertNotIn('@SERVICE_VERSION@', readme)
            with tarfile.open(args.output / 'agentscope-cli-2.1.0-BETA1-darwin-arm64.tar.gz') as package:
                self.assertEqual(set(package.getnames()), {'as', 'agentscope-runtime-host', 'LICENSE', 'README.md'})
            metadata = json.loads((args.output / 'release-manifest.json').read_text())
            self.assertEqual(set(metadata['artifacts']), {p.name for p in args.output.glob('*.tar.gz')} | {'agentscope-service-2.1.0-BETA1.tgz'})

    def test_npm_publication_uses_organization_registry_and_release_tag(self):
        for version, dry_run, tag in [('2.1.0-BETA1', True, 'next'), ('2.1.0', False, 'latest')]:
            with self.subTest(version=version), tempfile.TemporaryDirectory() as directory:
                service = Path(directory)
                sdk = service / 'service-controlplane/sdk/dsh'
                sdk.mkdir(parents=True)
                (sdk / 'package.json').write_text(json.dumps({'name': release.NPM_PACKAGE, 'version': version}))
                args = SimpleNamespace(version=version, output=service / 'dist', dry_run=dry_run)

                def simulated_run(*command, **kwargs):
                    if command[:2] == ('npm', 'pack'):
                        return json.dumps([{'filename': 'sdk.tgz', 'integrity': 'sha512-test'}])
                    if command == ('git', 'rev-parse', 'HEAD'):
                        return 'source-commit\n'

                with mock.patch.object(release, 'SERVICE', service), mock.patch.object(release, 'run', side_effect=simulated_run) as run, mock.patch.object(release, 'require_clean') as clean:
                    release.publish_npm(args)
                command = ['npm', 'publish', str(args.output / 'npm/sdk.tgz'), '--registry', 'https://registry.npmjs.org', '--access', 'public', '--tag', tag]
                if dry_run:
                    command.append('--dry-run')
                    clean.assert_not_called()
                else:
                    clean.assert_called_once()
                run.assert_any_call(*command)
                self.assertEqual(json.loads((args.output / 'npm/publication.json').read_text())['tag'], tag)

    def test_npm_publication_rejects_wrong_name_or_version_before_build(self):
        for name, version in [('@other/dsh-controlplane', '2.1.0-BETA1'), (release.NPM_PACKAGE, '2.0.0')]:
            with self.subTest(name=name, version=version), tempfile.TemporaryDirectory() as directory:
                service = Path(directory)
                sdk = service / 'service-controlplane/sdk/dsh'
                sdk.mkdir(parents=True)
                (sdk / 'package.json').write_text(json.dumps({'name': name, 'version': version}))
                args = SimpleNamespace(version='2.1.0-BETA1', output=service / 'dist', dry_run=True)
                with mock.patch.object(release, 'SERVICE', service), mock.patch.object(release, 'run') as run:
                    with self.assertRaises(SystemExit):
                        release.publish_npm(args)
                run.assert_not_called()

    def test_npm_plugin_configuration_matches_package_scope(self):
        directory = SERVICE / 'service-controlplane/sdk/dsh'
        metadata = json.loads((directory / 'package.json').read_text())
        patch = yaml.safe_load((directory / 'cordis.patch.yml').read_text())
        self.assertEqual(metadata['name'], release.NPM_PACKAGE)
        self.assertEqual(patch[0]['insert'][0]['name'], metadata['name'])
        self.assertEqual(metadata['publishConfig'], {'access': 'public', 'registry': release.NPM_REGISTRY})

    def test_frontend_verification_preserves_tracked_placeholder(self):
        self.check_frontend_placeholder(build_fails=False)

    def test_failed_frontend_build_preserves_tracked_placeholder(self):
        self.check_frontend_placeholder(build_fails=True)

    def check_frontend_placeholder(self, build_fails):
        with tempfile.TemporaryDirectory() as directory:
            service = Path(directory)
            placeholder = service / 'service-controlplane/ui/.gitkeep'
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
            self.assertGreaterEqual(len(settings['CONTROL_PLANE_BOOTSTRAP_PASSWORD']), 12)
            self.assertNotEqual(settings['BUILDER_JWT_SECRET'], settings['BUILDER_INTERNAL_TOKEN'])

    def test_chart_has_four_planes_shared_storage_and_no_demo_users(self):
        rendered = subprocess.check_output(['helm', 'template', 'test', str(SERVICE / 'helm/agentscope-service'),
                                           '--set', 'imageRepository=example.com/test', '--set', 'existingSecret=credentials'], text=True)
        objects = [o for o in yaml.safe_load_all(rendered) if o]
        deployments = [o for o in objects if o['kind'] == 'Deployment']
        self.assertEqual(len(deployments), 4)
        claims = []
        image_tag = yaml.safe_load((SERVICE / 'helm/agentscope-service/Chart.yaml').read_text())['appVersion']
        for dep in deployments:
            pod = dep['spec']['template']['spec']
            self.assertFalse(pod['automountServiceAccountToken'])
            container = pod['containers'][0]
            self.assertEqual(container['image'],
                             f'example.com/test/{release.IMAGE_NAMES[container["name"]]}:{image_tag}')
            for init in pod.get('initContainers', []):
                self.assertEqual(init['image'], container['image'])
            env = {e['name']: e['value'] for e in container['env']}
            if container['name'] == 'control':
                self.assertEqual(env['CONTROL_PLANE_SEED_USERS'], 'false')
                self.assertEqual(env['CONTROL_PLANE_ENABLE_KUBERNETES'], 'false')
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
        self.assertEqual(compose['services']['control']['environment']['CONTROL_PLANE_SEED_USERS'], 'false')
        for name, plane in {'control': 'control', 'data': 'dataplane', 'gateway': 'gateway', 'scheduler': 'scheduler'}.items():
            self.assertIn('/' + release.IMAGE_NAMES[plane] + ':', compose['services'][name]['image'])


if __name__ == '__main__':
    unittest.main()
