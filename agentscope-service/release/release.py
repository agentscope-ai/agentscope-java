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

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / 'agentscope-service'
PLANES = ('control', 'gateway', 'dataplane', 'scheduler')


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
            ui = name.startswith('agentscope-service/aistio/ui/') and p.name != '.gitkeep'
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
    placeholder = SERVICE / 'aistio/ui/.gitkeep'
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
    run('mvn', '-B', '-ntp', '-pl', 'agentscope-service/service-gateway,agentscope-service/service-dataplane,agentscope-service/service-scheduler', '-am', 'clean', 'verify')
    # Integration packages share PostgreSQL migration locks; serialize packages.
    run('go', 'test', '-p', '1', './...', cwd=SERVICE / 'aistio')
    run('go', 'vet', './...', cwd=SERVICE / 'aistio')
    for directory in (SERVICE / 'frontend', SERVICE / 'aistio/sdk/dsh'):
        verify_npm(directory)
    run(sys.executable, '-m', 'pytest', '-q', cwd=SERVICE / 'aistio/sdk/python')
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
            'images': {p: f'{args.repository}/agentscope-service-{p}:{args.version}' for p in PLANES},
            'sdkVersions': {'python': re.search(r'^version = "([^"]+)"', (SERVICE / 'aistio/sdk/python/pyproject.toml').read_text(), re.M)[1], 'dsh': json.loads((SERVICE / 'aistio/sdk/dsh/package.json').read_text())['version']},
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
    for target in args.platforms.split(','):
        if target not in ('linux/amd64', 'linux/arm64', 'darwin/amd64', 'darwin/arm64'):
            raise SystemExit(f'Unsupported CLI platform: {target}')
        system, arch = target.split('/')
        stage = out / f'cli-{system}-{arch}'
        stage.mkdir()
        env = dict(os.environ, CGO_ENABLED='0', GOOS=system, GOARCH=arch)
        for name, command in (('agentscope', 'aistioctl'), ('aistio-runtime-host', 'aistio-runtime-host')):
            run('go', 'build', '-trimpath', '-ldflags=-s -w -X github.com/spring-ai-alibaba/aistio/internal/version.Version=' + args.version, '-o', str(stage / name), './cmd/' + command,
                cwd=SERVICE / 'aistio', env=env)
        shutil.copy2(stage / 'agentscope', stage / 'aistioctl')
        shutil.copy2(ROOT / 'LICENSE', stage / 'LICENSE')
        with tarfile.open(out / f'agentscope-cli-{args.version}-{system}-{arch}.tar.gz', 'w:gz') as archive:
            for file in sorted(stage.iterdir()):
                archive.add(file, arcname=file.name)
        shutil.rmtree(stage)
    run(sys.executable, '-m', 'build', '--outdir', str(out), cwd=SERVICE / 'aistio/sdk/python')
    run('npm', 'ci', cwd=SERVICE / 'aistio/sdk/dsh')
    run('npm', 'run', 'build', cwd=SERVICE / 'aistio/sdk/dsh')
    run('npm', 'pack', '--pack-destination', str(out), cwd=SERVICE / 'aistio/sdk/dsh')
    data = manifest(args)
    data['platforms']['cli'] = args.platforms.split(',')
    data['artifacts'] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(out.iterdir()) if p.is_file()}
    (out / 'release-manifest.json').write_text(json.dumps(data, indent=2) + '\n')
    (out / 'SHA256SUMS').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in sorted(out.iterdir()) if p.is_file()))


def require_clean():
    if run('git', 'status', '--porcelain', capture=True).strip():
        raise SystemExit('Publishing requires a clean, committed source tree.')


def images(args):
    if args.push:
        require_clean()
    if not args.push and ',' in args.platforms:
        raise SystemExit('Use one platform with --load; multi-platform builds require --push.')
    args.output.mkdir(parents=True, exist_ok=True)
    for plane in PLANES:
        dockerfile = 'Dockerfile.control' if plane == 'control' else 'Dockerfile.service'
        cmd = ['docker', 'buildx', 'build', '--platform', args.platforms, '-f', str(SERVICE / 'docker' / dockerfile),
               '-t', f'{args.repository}/agentscope-service-{plane}:{args.version}',
               '--label', f'org.opencontainers.image.version={args.version}',
               '--label', 'org.opencontainers.image.revision=' + run('git', 'rev-parse', 'HEAD', capture=True).strip(),
               '--metadata-file', str(args.output / f'image-{plane}.json')]
        if plane == 'control':
            cmd += ['--build-arg', 'VERSION=' + args.version]
        if plane != 'control':
            cmd += ['--build-arg', 'MODULE=service-' + plane]
        cmd += ['--push', '--sbom=true', '--provenance=mode=max'] if args.push else ['--load']
        run(*cmd, str(ROOT))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('hygiene', 'verify', 'package', 'images', 'publish-chart'))
    parser.add_argument('--version')
    parser.add_argument('--repository', help='Registry hostname/namespace; no URL scheme')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--platforms', default='linux/amd64,linux/arm64,darwin/amd64,darwin/arm64')
    parser.add_argument('--push', action='store_true', help='Push images (otherwise load one platform locally)')
    args = parser.parse_args()
    if args.command == 'hygiene': return tracked_hygiene()
    if args.command == 'verify': return verify()
    validate_version(args.version)
    if not re.fullmatch(r'[a-z0-9][a-z0-9./:_-]+', args.repository or '') or '://' in args.repository:
        parser.error('--repository must be a registry hostname/namespace')
    args.output = (args.output or SERVICE / 'release/dist' / args.version).resolve()
    if args.command == 'package': package(args)
    if args.command == 'images': images(args)
    if args.command == 'publish-chart':
        require_clean()
        run('helm', 'push', str(args.output / f'agentscope-service-{args.version}.tgz'), 'oci://' + args.repository + '/charts')


if __name__ == '__main__':
    main()
