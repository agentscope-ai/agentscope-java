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

import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('release', Path(__file__).resolve().parents[1] / 'release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

VERSION = '2.1.0-BETA1'
COMMIT = 'a' * 40
GO_REF = 'refs/tags/agentscope-service/service-controlplane/v' + VERSION


class GitHubReleaseTests(unittest.TestCase):
    def test_go_tag_created_at_exact_commit(self):
        with mock.patch.object(release, 'run', return_value='') as run:
            release.ensure_go_tag(VERSION, COMMIT)
        run.assert_any_call('git', 'push', 'origin', f'{COMMIT}:{GO_REF}')

    def test_matching_lightweight_and_annotated_go_tags_are_unchanged(self):
        for remote in [f'{COMMIT}\t{GO_REF}\n',
                       f'{"b" * 40}\t{GO_REF}\n{COMMIT}\t{GO_REF}^{{}}\n']:
            with self.subTest(remote=remote), mock.patch.object(release, 'run', return_value=remote) as run:
                release.ensure_go_tag(VERSION, COMMIT)
                self.assertEqual(run.call_count, 1)

    def test_conflicting_go_tag_is_never_moved(self):
        with mock.patch.object(release, 'run', return_value=f'{"b" * 40}\t{GO_REF}\n') as run:
            with self.assertRaisesRegex(SystemExit, 'Refusing to move'):
                release.ensure_go_tag(VERSION, COMMIT)
            self.assertEqual(run.call_count, 1)

    def test_source_rejects_wrong_tag_and_unreviewed_commit(self):
        def source(*command, **kwargs):
            if command[1:3] == ('rev-parse', 'HEAD'):
                return COMMIT
            return 'b' * 40

        with mock.patch.object(release, 'require_clean'), mock.patch.object(release, 'run', side_effect=source) as run:
            with self.assertRaisesRegex(SystemExit, 'checked-out source'):
                release.check_release_source(VERSION)
            self.assertEqual(run.call_count, 2)

        def unreviewed(*command, **kwargs):
            if command[:2] == ('git', 'merge-base'):
                raise subprocess.CalledProcessError(1, command)
            return COMMIT

        with mock.patch.object(release, 'require_clean'), mock.patch.object(release, 'run', side_effect=unreviewed):
            with self.assertRaises(subprocess.CalledProcessError):
                release.check_release_source(VERSION)

    def test_source_requires_matching_java_go_versions_and_module_major(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control = root / 'agentscope-service/service-controlplane'
            (control / 'internal/version').mkdir(parents=True)
            for revision, go_version, module in [
                    (VERSION, VERSION, 'example.com/module/v2'),
                    ('2.0.0', VERSION, 'example.com/module/v2'),
                    (VERSION, '2.0.0', 'example.com/module/v2'),
                    (VERSION, VERSION, 'example.com/module/v3')]:
                (root / 'pom.xml').write_text(f'<revision>{revision}</revision>')
                (control / 'internal/version/version.go').write_text(f'Version = "{go_version}"')
                (control / 'go.mod').write_text(f'module {module}\n')
                with self.subTest(revision=revision, go_version=go_version, module=module), \
                        mock.patch.object(release, 'ROOT', root), \
                        mock.patch.object(release, 'SERVICE', root / 'agentscope-service'), \
                        mock.patch.object(release, 'require_clean'), \
                        mock.patch.object(release, 'run', return_value=COMMIT):
                    if revision == go_version == VERSION and module.endswith('/v2'):
                        self.assertEqual(release.check_release_source(VERSION), COMMIT)
                    else:
                        with self.assertRaises(SystemExit):
                            release.check_release_source(VERSION)

    def publish(self, output, existing, api_error=None):
        args = SimpleNamespace(version=VERSION, output=output)
        api = SimpleNamespace(returncode=1 if api_error else 0,
                              stderr=api_error or '', stdout=json.dumps(existing))
        with mock.patch.object(release, 'check_release_source', return_value=COMMIT), \
                mock.patch.dict(release.os.environ, {'GH_REPO': 'test/project'}), \
                mock.patch.object(release.subprocess, 'run', return_value=api), \
                mock.patch.object(release, 'run') as run, \
                mock.patch.object(release, 'ensure_go_tag') as go_tag:
            release.publish_github(args)
            return run.call_args_list, go_tag.call_args_list

    def package_fixture(self, output):
        names = [f'agentscope-cli-{VERSION}-{system}-{arch}.tar.gz'
                 for system, arch in [('linux', 'amd64'), ('linux', 'arm64'),
                                      ('darwin', 'amd64'), ('darwin', 'arm64')]]
        names += [f'agentscope-service-{VERSION}-compose.tar.gz',
                  f'agentscope-service-{VERSION}-kubernetes.tar.gz',
                  f'agentscope-service-{VERSION}.tgz', 'release-manifest.json']
        for name in names:
            (output / name).write_bytes(b'archive fixture')
        (output / 'release-manifest.json').write_text(json.dumps({
            'sourceCommit': COMMIT, 'serviceVersion': VERSION, 'sourceDirty': False}))
        (output / 'SHA256SUMS').write_text(''.join(
            f'{hashlib.sha256((output / name).read_bytes()).hexdigest()}  {name}\n' for name in names))
        return names + ['SHA256SUMS']

    def test_new_release_uploads_only_allowlisted_assets_then_publishes_prerelease(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            names = self.package_fixture(output)
            (output / '.env').write_text('must not upload')
            calls, tags = self.publish(output, None, 'gh: Not Found (HTTP 404)')
            self.assertIn('--draft', calls[0].args)
            self.assertIn('--verify-tag', calls[0].args)
            self.assertEqual(calls[1].args, ('gh', 'release', 'upload', 'v' + VERSION,
                                           *(str(output / name) for name in names)))
            self.assertEqual(tags, [mock.call(VERSION, COMMIT)])
            self.assertEqual(calls[2].args, ('gh', 'release', 'edit', 'v' + VERSION,
                                           '--draft=false', '--prerelease=true', '--latest=false'))

    def test_published_release_is_unchanged_and_api_errors_do_not_create_release(self):
        calls, tags = self.publish(Path('/unused'), {'draft': False})
        self.assertEqual(calls, [])
        self.assertEqual(tags, [])
        with self.assertRaisesRegex(SystemExit, 'HTTP 403'):
            self.publish(Path('/unused'), None, 'HTTP 403')

    def test_image_digest_asset_is_uploaded_and_included_in_checksums(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            self.package_fixture(output)
            (output / 'images.json').write_text(json.dumps({
                'sourceCommit': COMMIT, 'serviceVersion': VERSION,
                'images': {plane: {'digest': 'sha256:' + 'a' * 64} for plane in release.PLANES}}))
            calls, _ = self.publish(output, None, 'HTTP 404')
            self.assertIn(str(output / 'images.json'), calls[1].args)
            checksums = (output / 'SHA256SUMS').read_text()
            self.assertIn(hashlib.sha256((output / 'images.json').read_bytes()).hexdigest() + '  images.json', checksums)
            data = json.loads((output / 'images.json').read_text())
            data['images'].pop('gateway')
            (output / 'images.json').write_text(json.dumps(data))
            with self.assertRaisesRegex(SystemExit, 'complete tagged release'):
                self.publish(output, None, 'HTTP 404')

    def test_draft_retry_skips_matching_assets_and_rejects_conflicts(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            names = self.package_fixture(output)
            asset = {'name': names[0], 'digest': 'sha256:' + hashlib.sha256((output / names[0]).read_bytes()).hexdigest()}
            calls, _ = self.publish(output, {'draft': True, 'assets': [asset]})
            self.assertNotIn(str(output / names[0]), calls[0].args)
            self.assertEqual(calls[0].args[:3], ('gh', 'release', 'upload'))
            asset['digest'] = 'sha256:conflict'
            with self.assertRaisesRegex(SystemExit, 'refuse to overwrite'):
                self.publish(output, {'draft': True, 'assets': [asset]})

    def test_tampered_artifact_or_manifest_blocks_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            names = self.package_fixture(output)
            (output / names[0]).write_bytes(b'tampered')
            with self.assertRaisesRegex(SystemExit, 'checksum verification failed'):
                self.publish(output, None, 'HTTP 404')
            self.package_fixture(output)
            metadata = json.loads((output / 'release-manifest.json').read_text())
            metadata['sourceCommit'] = 'other-commit'
            (output / 'release-manifest.json').write_text(json.dumps(metadata))
            with self.assertRaisesRegex(SystemExit, 'manifest does not match'):
                self.publish(output, None, 'HTTP 404')
