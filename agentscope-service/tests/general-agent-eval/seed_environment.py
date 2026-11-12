# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Initialize an empty lane PVC from a checksum-pinned, trusted benchmark seed."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import time
from urllib.request import urlopen


ALLOWED = {'python', 'venvs', 'sources', 'appworld-root', 'benchmark-revisions.json',
           'appworld-requirements.lock', 'tau-bench-requirements.lock'}


def validate_members(names):
    for name in names:
        path = Path(name)
        if path.is_absolute() or '..' in path.parts or not path.parts or path.parts[0] not in ALLOWED:
            raise ValueError('Seed contains an unexpected path')
        if path.parts[0] == 'appworld-root' and len(path.parts) > 1 and path.parts[1] != 'data':
            raise ValueError('Seed must not contain mutable AppWorld state')


def main(args):
    root = Path(args.root)
    receipt = root / 'seed-receipt.json'
    if receipt.exists():
        if json.loads(receipt.read_text())['sha256'] != args.sha256:
            raise ValueError('Existing PVC has a different seed')
        print('Pinned benchmark seed already initialized')
        return
    # Kubernetes creates the nested read-only ConfigMap mountpoint before init.
    if any(p.name not in {'lost+found', 'code'} for p in root.iterdir()):
        raise ValueError('PVC is not empty; interrupted initialization requires inspection')
    archive = root / 'benchmark-seed.tar.gz'
    digest = hashlib.sha256()
    with urlopen(args.url, timeout=120) as response, archive.open('xb') as output:
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
            output.write(chunk)
    if digest.hexdigest() != args.sha256:
        raise ValueError('Seed checksum mismatch')
    with tarfile.open(archive) as bundle:
        validate_members(bundle.getnames())
    # This is a locally generated, checksum-pinned seed. Preserve /eval interpreter
    # symlinks; generic safe-tar filters reject these required absolute links.
    subprocess.run(['tar', '-xzf', str(archive), '-C', str(root)], check=True)
    with receipt.open('x') as output:
        json.dump({'sha256': args.sha256, 'initialized_at_epoch': time.time()}, output, indent=2)
    archive.unlink()
    print('Pinned benchmark dependencies and immutable task data initialized')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', default='/eval')
    parser.add_argument('--url', required=True)
    parser.add_argument('--sha256', required=True)
    main(parser.parse_args())
