#!/bin/sh
# Test-only Broker derivative. The unmodified image remains the negative baseline.
set -eu
fixture_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build_dir=$(mktemp -d "${TMPDIR:-/tmp}/managed-rocketmq-min-offset.XXXXXX")
trap 'rm -rf "${build_dir:?}"' EXIT
base_image_id=$(docker image inspect managed-test/rocketmq:5.5.1 --format '{{.Id}}')
base_image="managed-test/rocketmq:fixture-base-${base_image_id#sha256:}"
docker image tag "$base_image_id" "$base_image"
curl -fsSL --connect-timeout 10 --max-time 60 \
  https://raw.githubusercontent.com/apache/rocketmq/rocketmq-all-5.5.1/broker/src/main/java/org/apache/rocketmq/broker/processor/PopLiteMessageProcessor.java \
  -o "$build_dir/PopLiteMessageProcessor.java"
python3 - "$build_dir/PopLiteMessageProcessor.java" <<'PY'
import hashlib
from pathlib import Path
import sys
p = Path(sys.argv[1])
expected = 'f15c65b3ae8ab33fb2da98884de91596aa5f316e67c8c3254dac981ef0f94817'
if hashlib.sha256(p.read_bytes()).hexdigest() != expected:
    raise SystemExit('Pinned official Broker source checksum differs')
PY
patch -d "$build_dir" -p1 < "$fixture_dir/rocketmq-lite-min-offset.patch"
docker build --build-arg "BASE_IMAGE=$base_image" \
  -f "$fixture_dir/Dockerfile.rocketmq-551-min-offset" \
  -t managed-test/rocketmq:5.5.1-min-offset "$build_dir"
