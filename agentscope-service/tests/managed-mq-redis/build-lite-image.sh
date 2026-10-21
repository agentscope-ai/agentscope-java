#!/bin/sh
set -eu
fixture_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build_dir=$(mktemp -d "${TMPDIR:-/tmp}/managed-rocketmq-551.XXXXXX")
trap 'rm -rf "${build_dir:?}"' EXIT
curl -fsSL --connect-timeout 10 --max-time 240 \
  https://downloads.apache.org/rocketmq/5.5.1/rocketmq-all-5.5.1-bin-release.zip \
  -o "$build_dir/rocketmq-all-5.5.1-bin-release.zip"
python3 - "$build_dir" <<'PY'
import hashlib
from pathlib import Path
import sys
import zipfile
root = Path(sys.argv[1]).resolve()
archive = root / 'rocketmq-all-5.5.1-bin-release.zip'
expected = '21555d9bd79792ac89f246566f19f58e300784c59b96289fd0c74db14787861081fb60ebb95cf541bf20a3e508bf3b70feb9596ea3ba6dc5dc88fa6081f00577'
digest = hashlib.sha512()
with archive.open('rb') as source:
    for chunk in iter(lambda: source.read(1024 * 1024), b''):
        digest.update(chunk)
if digest.hexdigest() != expected:
    raise SystemExit('Official RocketMQ 5.5.1 binary checksum differs')
with zipfile.ZipFile(archive) as source:
    for name in source.namelist():
        if not (root / name).resolve().is_relative_to(root):
            raise SystemExit('Archive path leaves build directory')
    source.extractall(root)
archive.unlink()
PY
docker build -f "$fixture_dir/Dockerfile.rocketmq-551" -t managed-test/rocketmq:5.5.1 "$build_dir"
