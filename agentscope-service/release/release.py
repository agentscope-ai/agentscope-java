#!/usr/bin/env python3
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

"""Build and package AgentScope Service. Publishing is always an explicit command."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / 'agentscope-service'
PLANES = ('control', 'gateway', 'dataplane', 'scheduler')
IMAGE_NAMES = {'control': 'as-controlplane', 'gateway': 'as-gateway',
               'dataplane': 'as-dataplane', 'scheduler': 'as-scheduler'}
NPM_PACKAGE = '@agentscope-service/dsh-controlplane'
NPM_REGISTRY = 'https://registry.npmjs.org'


def run(*args, cwd=ROOT, env=None, capture=False):
    return subprocess.run(args, cwd=cwd, env=env, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def tracked_hygiene():
    files = run('git', 'ls-files', '-z', capture=True).split('\0')
    errors = []
    for name in files:
        if not name:
            continue
        p = Path(name)
        if p.suffix.lower() in ('.ppt', '.pptx', '.key', '.jar', '.war', '.class') and 'src/test/resources' not in name and name != '.mvn/wrapper/maven-wrapper.jar':
            errors.append(name)
        if name.startswith('agentscope-service/'):
            generated = any(part in p.parts for part in ('node_modules', 'test-reports', 'playwright-report', 'test-results', 'target'))
            ui = name.startswith('agentscope-service/service-controlplane/ui/') and p.name != '.gitkeep'
            package = p.suffix.lower() in ('.ppt', '.pptx', '.jar', '.war', '.class', '.zip', '.tgz', '.exe')
            generated |= name.startswith('agentscope-service/release/dist/')
            if generated or ui or package:
                errors.append(name)
        if (ROOT / p).is_file() and 'src/test/resources' not in name:
            with (ROOT / p).open('rb') as stream:
                magic = stream.read(4)
            if magic in (b'\x7fELF', b'\xcf\xfa\xed\xfe', b'\xfe\xed\xfa\xcf', b'\xce\xfa\xed\xfe'):
                errors.append(name)
    if errors:
        raise SystemExit('Generated/binary files are tracked:\n' + '\n'.join(sorted(set(errors))))
    print('Tracked source hygiene passed.')


def verify_npm(directory):
    # Vite empties its output directory, including this tracked source placeholder.
    placeholder = SERVICE / 'service-controlplane/ui/.gitkeep'
    original = placeholder.read_bytes() if directory == SERVICE / 'frontend' and placeholder.is_file() else None
    run('npm', 'ci', cwd=directory)
    try:
        run('npm', 'run', 'build', cwd=directory)
    finally:
        if original is not None:
            placeholder.parent.mkdir(parents=True, exist_ok=True)
            placeholder.write_bytes(original)
    run('npm', 'test', cwd=directory)


def verify():
    tracked_hygiene()
    run('mvn', '-B', '-ntp', '-T1', '-pl', 'agentscope-service/service-gateway,agentscope-service/service-dataplane,agentscope-service/service-scheduler', '-am', 'clean', 'verify')
    # Integration packages share PostgreSQL migration locks; serialize packages.
    run('go', 'test', '-p', '1', './...', cwd=SERVICE / 'service-controlplane')
    run('go', 'vet', './...', cwd=SERVICE / 'service-controlplane')
    for directory in (SERVICE / 'frontend', SERVICE / 'service-controlplane/sdk/dsh'):
        verify_npm(directory)
    run(sys.executable, '-m', 'pytest', '-q', cwd=SERVICE / 'service-controlplane/sdk/python')
    run('helm', 'lint', str(SERVICE / 'helm/agentscope-service'), '--set', 'imageRepository=example.com/ci', '--set', 'existingSecret=ci')
    run(sys.executable, '-m', 'unittest', 'discover', '-s', str(SERVICE / 'release/tests'))


def validate_version(version):
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', version or ''):
        raise ValueError('Use a SemVer version without v prefix or build metadata.')
    return version


def manifest(args):
    return {'serviceVersion': args.version,
            'sourceCommit': run('git', 'rev-parse', 'HEAD', capture=True).strip(),
            'javaRevision': re.search(r'<revision>([^<]+)</revision>', (ROOT / 'pom.xml').read_text())[1],
            'sourceDirty': bool(run('git', 'status', '--porcelain', capture=True).strip()),
            'images': {p: f'{args.repository}/{IMAGE_NAMES[p]}:{args.version}' for p in PLANES},
            'sdkVersions': {'python': re.search(r'^version = "([^"]+)"', (SERVICE / 'service-controlplane/sdk/python/pyproject.toml').read_text(), re.M)[1], 'dsh': json.loads((SERVICE / 'service-controlplane/sdk/dsh/package.json').read_text())['version']},
            'platforms': {'images': ['linux/amd64', 'linux/arm64'], 'cli': ['linux/amd64', 'linux/arm64', 'darwin/amd64', 'darwin/arm64']},
            'notes': 'Image digests are recorded separately by the images command. Registry references are publication targets, not proof of availability.'}


def package(args):
    out = args.output
    if out.exists():
        raise SystemExit(f'Refusing to overwrite {out}; choose another --output directory.')
    out.mkdir(parents=True)
    deploy = out / 'agentscope-service'
    # Explicit allowlist: never package local .env files or database backups.
    deploy.mkdir()
    for name in ('compose.yaml', '.env.example', 'init-env.sh', 'postgres-init.sql', 'kubernetes.env.example', 'README.md'):
        shutil.copy2(SERVICE / 'deploy' / name, deploy / name)
    for name in ('LICENSE', 'NOTICE'):
        if (ROOT / name).exists():
            shutil.copy2(ROOT / name, deploy / name)
    with tarfile.open(out / f'agentscope-service-{args.version}-compose.tar.gz', 'w:gz') as archive:
        archive.add(deploy, arcname='agentscope-service')
    shutil.rmtree(deploy)
    run('helm', 'package', str(SERVICE / 'helm/agentscope-service'), '--version', args.version,
        '--app-version', args.version, '--destination', str(out))
    deploy = out / 'agentscope-service-kubernetes'
    deploy.mkdir()
    for name in ('kubernetes.env.example', 'postgres-init.sql'):
        shutil.copy2(SERVICE / 'deploy' / name, deploy / name)
    readme = (SERVICE / 'release/KUBERNETES_README.md').read_text()
    readme = readme.replace('@SERVICE_VERSION@', args.version).replace('@IMAGE_REPOSITORY@', args.repository)
    (deploy / 'README.md').write_text(readme)
    shutil.copy2(out / f'agentscope-service-{args.version}.tgz', deploy)
    for name in ('LICENSE', 'NOTICE'):
        if (ROOT / name).exists():
            shutil.copy2(ROOT / name, deploy / name)
    with tarfile.open(out / f'agentscope-service-{args.version}-kubernetes.tar.gz', 'w:gz') as archive:
        archive.add(deploy, arcname=deploy.name)
    shutil.rmtree(deploy)
    for target in args.platforms.split(','):
        if target not in ('linux/amd64', 'linux/arm64', 'darwin/amd64', 'darwin/arm64'):
            raise SystemExit(f'Unsupported CLI platform: {target}')
        system, arch = target.split('/')
        stage = out / f'cli-{system}-{arch}'
        stage.mkdir()
        env = dict(os.environ, CGO_ENABLED='0', GOOS=system, GOARCH=arch)
        for name, command in (('as', 'as'), ('agentscope-runtime-host', 'agentscope-runtime-host')):
            run('go', 'build', '-trimpath', '-ldflags=-s -w -X github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/version.Version=' + args.version, '-o', str(stage / name), './cmd/' + command,
                cwd=SERVICE / 'service-controlplane', env=env)
        shutil.copy2(ROOT / 'LICENSE', stage / 'LICENSE')
        shutil.copy2(SERVICE / 'release/CLI_README.md', stage / 'README.md')
        with tarfile.open(out / f'agentscope-cli-{args.version}-{system}-{arch}.tar.gz', 'w:gz') as archive:
            for file in sorted(stage.iterdir()):
                archive.add(file, arcname=file.name)
        shutil.rmtree(stage)
    if not args.distributions_only:
        run(sys.executable, '-m', 'build', '--outdir', str(out), cwd=SERVICE / 'service-controlplane/sdk/python')
        run('npm', 'ci', cwd=SERVICE / 'service-controlplane/sdk/dsh')
        run('npm', 'run', 'build', cwd=SERVICE / 'service-controlplane/sdk/dsh')
        run('npm', 'pack', '--pack-destination', str(out), cwd=SERVICE / 'service-controlplane/sdk/dsh')
    data = manifest(args)
    data['platforms']['cli'] = args.platforms.split(',')
    data['artifacts'] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(out.iterdir()) if p.is_file()}
    (out / 'release-manifest.json').write_text(json.dumps(data, indent=2) + '\n')
    (out / 'SHA256SUMS').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in sorted(out.iterdir()) if p.is_file()))


def require_clean():
    if run('git', 'status', '--porcelain', capture=True).strip():
        raise SystemExit('Publishing requires a clean, committed source tree.')


def check_release_source(version):
    validate_version(version)
    require_clean()
    commit = run('git', 'rev-parse', 'HEAD', capture=True).strip()
    tag = 'v' + version
    if run('git', 'rev-parse', f'refs/tags/{tag}^{{commit}}', capture=True).strip() != commit:
        raise SystemExit('The main release tag must point to the checked-out source.')
    run('git', 'fetch', '--no-tags', 'origin', 'main')
    run('git', 'merge-base', '--is-ancestor', commit, 'FETCH_HEAD')
    revision = re.search(r'<revision>([^<]+)</revision>', (ROOT / 'pom.xml').read_text())[1]
    go_version = re.search(r'Version = "([^"]+)"', (SERVICE / 'service-controlplane/internal/version/version.go').read_text())[1]
    module = re.search(r'^module (\S+)', (SERVICE / 'service-controlplane/go.mod').read_text())[1]
    if revision != version or go_version != version:
        raise SystemExit('Update the Java revision and Go version before tagging this release.')
    if not module.endswith('/v' + version.split('.')[0]):
        raise SystemExit('The release major version must match the Go module path.')
    return commit


def ensure_go_tag(version, commit):
    tag = 'agentscope-service/service-controlplane/v' + version
    ref = 'refs/tags/' + tag
    remote = run('git', 'ls-remote', '--tags', 'origin', ref, ref + '^{}', capture=True)
    refs = dict((name, sha) for sha, name in (line.split() for line in remote.splitlines()))
    existing = refs.get(ref + '^{}', refs.get(ref))
    if existing and existing != commit:
        raise SystemExit(f'Refusing to move published Go tag {tag}.')
    if not existing:
        run('git', 'push', 'origin', f'{commit}:{ref}')


def publish_github(args):
    commit = check_release_source(args.version)
    tag = 'v' + args.version
    repo = os.environ['GH_REPO']
    result = subprocess.run(['gh', 'api', f'repos/{repo}/releases/tags/{tag}'],
                            text=True, capture_output=True)
    if result.returncode:
        if 'HTTP 404' not in result.stderr:
            raise SystemExit(result.stderr)
        existing = None
    else:
        existing = json.loads(result.stdout)
    if existing and not existing['draft']:
        print(f'{tag} is already published; its assets are unchanged.')
        return
    assets = [f'agentscope-cli-{args.version}-{system}-{arch}.tar.gz'
              for system, arch in [('linux', 'amd64'), ('linux', 'arm64'),
                                   ('darwin', 'amd64'), ('darwin', 'arm64')]]
    assets += [f'agentscope-service-{args.version}-compose.tar.gz',
               f'agentscope-service-{args.version}-kubernetes.tar.gz',
               f'agentscope-service-{args.version}.tgz', 'release-manifest.json', 'SHA256SUMS']
    metadata = json.loads((args.output / 'release-manifest.json').read_text())
    if metadata['sourceCommit'] != commit or metadata['serviceVersion'] != args.version or metadata['sourceDirty']:
        raise SystemExit('The package manifest does not match the clean tagged source.')
    checksums = dict((name, digest) for digest, name in
                     (line.split() for line in (args.output / 'SHA256SUMS').read_text().splitlines()))
    image_file = args.output / 'images.json'
    if image_file.is_file():
        image_metadata = json.loads(image_file.read_text())
        if (image_metadata['sourceCommit'] != commit or image_metadata['serviceVersion'] != args.version
                or set(image_metadata['images']) != set(PLANES)):
            raise SystemExit('The image metadata does not match the complete tagged release.')
        assets.append('images.json')
        if 'images.json' not in checksums:
            checksums['images.json'] = hashlib.sha256(image_file.read_bytes()).hexdigest()
            (args.output / 'SHA256SUMS').write_text(''.join(
                f'{digest}  {name}\n' for name, digest in checksums.items()))
    if set(checksums) != set(assets) - {'SHA256SUMS'}:
        raise SystemExit('The checksums must cover exactly the distribution assets.')
    digests = {name: 'sha256:' + hashlib.sha256((args.output / name).read_bytes()).hexdigest()
               for name in assets}
    if any(digests[name] != 'sha256:' + digest for name, digest in checksums.items()):
        raise SystemExit('Distribution checksum verification failed.')
    uploaded = {asset['name']: asset.get('digest') for asset in existing['assets']} if existing else {}
    if any(uploaded[name] != digests[name] for name in assets if name in uploaded):
        raise SystemExit('Existing draft assets differ; refuse to overwrite them. Rerun the publish job with its original artifacts.')
    if not existing:
        with tempfile.TemporaryDirectory() as directory:
            notes = Path(directory) / 'notes.md'
            notes.write_text(f'AgentScope Service {args.version}. Installation and usage: '
                             '[English](https://java.agentscope.io/v2/en/service/overview) / '
                             '[中文](https://java.agentscope.io/v2/zh/service/overview).\n')
            run('gh', 'release', 'create', tag, '--verify-tag', '--draft',
                '--title', f'AgentScope Service {args.version}', '--notes-file', str(notes))
    missing = [str(args.output / name) for name in assets if name not in uploaded]
    if missing:
        run('gh', 'release', 'upload', tag, *missing)
    ensure_go_tag(args.version, commit)
    run('gh', 'release', 'edit', tag, '--draft=false',
        '--prerelease=' + str('-' in args.version).lower(), '--latest=false')


def publish_npm(args):
    directory = SERVICE / 'service-controlplane/sdk/dsh'
    metadata = json.loads((directory / 'package.json').read_text())
    if metadata['name'] != NPM_PACKAGE or metadata['version'] != args.version:
        raise SystemExit('npm package name/version does not match the release target.')
    if not args.dry_run:
        require_clean()
    run('npm', 'ci', cwd=directory)
    run('npm', 'test', cwd=directory)
    run('npm', 'run', 'build', cwd=directory)
    out = args.output / 'npm'
    out.mkdir(parents=True, exist_ok=True)
    packed = json.loads(run('npm', 'pack', '--json', '--pack-destination', str(out),
                            cwd=directory, capture=True))[0]
    archive = out / packed['filename']
    tag = 'next' if '-' in args.version else 'latest'
    command = ['npm', 'publish', str(archive), '--registry', NPM_REGISTRY,
               '--access', 'public', '--tag', tag]
    if args.dry_run:
        command.append('--dry-run')
    run(*command)
    record = {'name': metadata['name'], 'version': args.version, 'registry': NPM_REGISTRY,
              'tag': tag, 'integrity': packed['integrity'],
              'sourceCommit': run('git', 'rev-parse', 'HEAD', capture=True).strip(),
              'status': 'dry-run' if args.dry_run else 'published'}
    (out / 'publication.json').write_text(json.dumps(record, indent=2) + '\n')


def inspect_release_image(reference, version, commit, platforms, allow_missing=False):
    result = subprocess.run(['docker', 'buildx', 'imagetools', 'inspect', reference,
                             '--format', '{{json .Manifest}}'], text=True, capture_output=True)
    if result.returncode:
        if allow_missing and ('manifest unknown' in result.stderr.lower() or ': not found' in result.stderr.lower()):
            return None
        raise SystemExit(result.stderr)
    metadata = json.loads(result.stdout)
    actual = {f'{p["os"]}/{p["architecture"]}' for m in metadata.get('manifests', [])
              if (p := m.get('platform', {})).get('os') != 'unknown' and p.get('os')}
    expected = set(platforms.split(','))
    if actual != expected:
        raise SystemExit(f'Refusing to reuse {reference}: platforms {actual} do not match {expected}.')
    pinned = reference.rsplit(':', 1)[0] + '@' + metadata['digest']
    configs = json.loads(run('docker', 'buildx', 'imagetools', 'inspect', pinned,
                             '--format', '{{json .Image}}', capture=True))
    for platform in expected:
        labels = configs[platform].get('config', {}).get('Labels', {})
        if (labels.get('org.opencontainers.image.version') != version
                or labels.get('org.opencontainers.image.revision') != commit):
            raise SystemExit(f'Refusing to reuse {reference}: {platform} version/source labels differ.')
    return metadata['digest']


def images(args):
    if args.push:
        require_clean()
    if not args.push and ',' in args.platforms:
        raise SystemExit('Use one platform with --load; multi-platform builds require --push.')
    skip_existing = getattr(args, 'skip_existing', False)
    if skip_existing and not args.push:
        raise SystemExit('--skip-existing requires --push.')
    commit = run('git', 'rev-parse', 'HEAD', capture=True).strip()
    references = {plane: f'{args.repository}/{IMAGE_NAMES[plane]}:{args.version}' for plane in PLANES}
    existing = {plane: inspect_release_image(reference, args.version, commit, args.platforms, allow_missing=True)
                for plane, reference in references.items()} if skip_existing else {}
    args.output.mkdir(parents=True, exist_ok=True)
    records = {}
    for plane in PLANES:
        if existing.get(plane):
            print(f'Reusing {references[plane]}@{existing[plane]}; source and platforms match.')
            (args.output / f'image-{plane}.json').write_text(json.dumps(
                {'containerimage.digest': existing[plane]}, indent=2) + '\n')
        else:
            build_image(args, plane, commit)
        if skip_existing:
            digest = existing.get(plane) or inspect_release_image(
                references[plane], args.version, commit, args.platforms)
            records[plane] = {'reference': references[plane], 'digest': digest,
                              'platforms': args.platforms.split(',')}
    if skip_existing:
        (args.output / 'images.json').write_text(json.dumps({
            'serviceVersion': args.version, 'sourceCommit': commit, 'images': records}, indent=2) + '\n')


def build_image(args, plane, commit):
    dockerfile = 'Dockerfile.control' if plane == 'control' else 'Dockerfile.service'
    cmd = ['docker', 'buildx', 'build', '--platform', args.platforms, '-f', str(SERVICE / 'docker' / dockerfile),
           '-t', f'{args.repository}/{IMAGE_NAMES[plane]}:{args.version}',
           '--label', f'org.opencontainers.image.version={args.version}',
           '--label', 'org.opencontainers.image.revision=' + commit,
           '--metadata-file', str(args.output / f'image-{plane}.json')]
    if plane == 'control':
        cmd += ['--build-arg', 'VERSION=' + args.version,
                '--build-arg', 'GIT_COMMIT=' + commit]
    if plane != 'control':
        cmd += ['--build-arg', 'MODULE=service-' + plane]
    cmd += ['--push', '--sbom=true', '--provenance=mode=max'] if args.push else ['--load']
    run(*cmd, str(ROOT))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('hygiene', 'verify', 'package', 'images', 'publish-chart', 'publish-npm', 'check-tag', 'publish-github'))
    parser.add_argument('--version')
    parser.add_argument('--repository', help='Registry hostname/namespace; no URL scheme')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--platforms', default='linux/amd64,linux/arm64,darwin/amd64,darwin/arm64')
    parser.add_argument('--push', action='store_true', help='Push images (otherwise load one platform locally)')
    parser.add_argument('--skip-existing', action='store_true', help='Reuse matching published images and verify image source/platforms')
    parser.add_argument('--dry-run', action='store_true', help='Validate npm publication without uploading')
    parser.add_argument('--distributions-only', action='store_true', help='Package CLI, Compose and Kubernetes without SDK archives')
    args = parser.parse_args()
    if args.command == 'hygiene': return tracked_hygiene()
    if args.command == 'verify': return verify()
    validate_version(args.version)
    args.output = (args.output or SERVICE / 'release/dist' / args.version).resolve()
    if args.command == 'check-tag':
        check_release_source(args.version)
        return
    if args.command == 'publish-github':
        publish_github(args)
        return
    if args.command == 'publish-npm':
        publish_npm(args)
        return
    if not re.fullmatch(r'[a-z0-9][a-z0-9./:_-]+', args.repository or '') or '://' in args.repository:
        parser.error('--repository must be a registry hostname/namespace')
    if args.command == 'package': package(args)
    if args.command == 'images': images(args)
    if args.command == 'publish-chart':
        require_clean()
        run('helm', 'push', str(args.output / f'agentscope-service-{args.version}.tgz'), 'oci://' + args.repository + '/charts')


if __name__ == '__main__':
    main()
