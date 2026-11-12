# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Install pinned benchmark sources and separate Python environments on a mounted PVC."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import urllib.request


REVISIONS = {'tau-bench': ('sierra-research/tau-bench', '59a200c6d575d595120f1cb70fea53cef0632f6b'),
             'appworld': ('StonyBrookNLP/appworld', '42b5bcf3cd334fee33f0c37c02070a9f5807add5')}


def download(url, destination, headers=None):
    destination = Path(destination)
    temporary = destination.with_suffix(destination.suffix + '.download')
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers or {}), timeout=120) as response:
        with temporary.open('wb') as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
    temporary.replace(destination)


def sources(root, name):
    repository, revision = REVISIONS[name]
    target = root / 'sources' / name
    if not target.exists():
        archive = root / (name + '-' + revision + '.tar.gz')
        if not archive.exists():
            download('https://codeload.github.com/' + repository + '/tar.gz/' + revision, archive)
        parent = root / 'sources'
        parent.mkdir(exist_ok=True)
        with tarfile.open(archive) as stream:
            stream.extractall(parent, filter='data')
        (parent / (name + '-' + revision)).rename(target)
    # GitHub archives contain Git LFS pointers. Download only the pinned public objects.
    for path in target.rglob('*'):
        if not path.is_file() or path.stat().st_size > 1024:
            continue
        value = path.read_bytes()
        if not value.startswith(b'version https://git-lfs.github.com/spec/v1'):
            continue
        fields = dict(line.split(' ', 1) for line in value.decode().splitlines())
        oid, size = fields['oid'].removeprefix('sha256:'), int(fields['size'])
        download('https://media.githubusercontent.com/media/' + repository + '/' + revision + '/' +
                 str(path.relative_to(target)), path)
        with path.open('rb') as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != oid or path.stat().st_size != size:
                raise ValueError('Git LFS object checksum differs')
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', default='/eval')
    args = parser.parse_args()
    root = Path(args.root).resolve()
    root.mkdir(exist_ok=True)
    env = {**os.environ, 'UV_CACHE_DIR': str(root / 'uv-cache'), 'UV_PYTHON_INSTALL_DIR': str(root / 'python'),
           'UV_HTTP_TIMEOUT': '60', 'UV_DEFAULT_INDEX': 'https://mirrors.aliyun.com/pypi/simple', 'UV_SYSTEM_CERTS': 'true'}
    uv = str(root / 'uv')
    def run(command, cwd=None):
        subprocess.run(command, env=env, cwd=cwd, check=True)
    run([uv, 'python', 'install', '3.11'])
    for name in REVISIONS:
        source = sources(root, name)
        venv = root / 'venvs' / name
        if not (venv / 'bin/python').exists():
            run([uv, 'venv', '--python', '3.11', str(venv)])
        lock = Path(__file__).with_name(('tau' if name == 'tau-bench' else name) + '-requirements.lock')
        run([uv, 'pip', 'install', '--python', str(venv / 'bin/python'), '-r', str(lock)])
        # The tau runner uses LiteLLM; unused direct-provider SDKs are intentionally omitted.
        run([uv, 'pip', 'install', '--python', str(venv / 'bin/python'), '--no-deps', '-e', str(source)])
        if name == 'appworld':
            world_root = root / 'appworld-root'
            world_root.mkdir(exist_ok=True)
            env['APPWORLD_ROOT'] = str(world_root)
            run([str(venv / 'bin/appworld'), 'install', '--repo'], cwd=source)
            if not (world_root / 'data/tasks').exists():
                bundle = root / 'appworld-data-0.2.0.bundle'
                if not bundle.exists():
                    download('https://s3.us-west-2.amazonaws.com/appworld.dev/data-0.2.0.bundle', bundle)
                with bundle.open('rb') as stream:
                    if hashlib.file_digest(stream, 'sha256').hexdigest() != 'c9299e6cafe92bce4592a3c117c047c973d1554a667c21dd81537e78ab2f532e':
                        raise ValueError('AppWorld 0.2.0 data differs from the frozen download')
                run([str(venv / 'bin/python'), '-c',
                     'from appworld.common.crypto import unpack_bundle; from appworld.common.constants import PASSWORD,SALT; '
                     'import sys; unpack_bundle(bundle_file_path=sys.argv[1],base_directory=sys.argv[2],password=PASSWORD,salt=SALT)',
                     str(bundle), str(world_root)])
        freeze = subprocess.check_output([uv, 'pip', 'freeze', '--python', str(venv / 'bin/python')], env=env)
        (root / (name + '-requirements.lock')).write_bytes(freeze)
    (root / 'benchmark-revisions.json').write_text(json.dumps(REVISIONS, indent=2))
    print('Benchmark environments installed; no trials started.', flush=True)


if __name__ == '__main__':
    main()
