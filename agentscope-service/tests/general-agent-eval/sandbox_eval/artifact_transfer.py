# Copyright 2024-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
"""Digest-checked reads of immutable artifacts with bounded Kubernetes output."""
import base64
import hashlib
import json
from pathlib import Path
import re
import shlex

from sandbox_eval.exec_transport import ExecutionOutcomeUnknown


async def download_snapshot(environment, remote, partial):
    script = ('import hashlib,json;from pathlib import Path;p=Path(' + repr(remote) +
              ');b=p.read_bytes();print(json.dumps({"bytes":len(b),'
              '"sha256":hashlib.sha256(b).hexdigest()}))')
    result = await environment.exec('python3 -c ' + shlex.quote(script), timeout_sec=60)
    try:
        metadata = json.loads(result.stdout)
        if (result.return_code != 0 or type(metadata['bytes']) is not int or
                metadata['bytes'] < 0 or not re.fullmatch('[0-9a-f]{64}', metadata['sha256'])):
            raise ValueError('Invalid snapshot metadata')
    except (KeyError, TypeError, ValueError) as error:
        raise ExecutionOutcomeUnknown('Artifact snapshot could not be verified') from error
    sha = hashlib.sha256()
    with Path(partial).open('xb') as stream:
        for offset in range(0, metadata['bytes'], 8192):
            length = min(8192, metadata['bytes'] - offset)
            command = ('set -o pipefail; dd if=' + shlex.quote(remote) + ' bs=8192 skip=' +
                       str(offset // 8192) + ' count=1 2>/dev/null | base64 -w 0')
            result = await environment.exec(command, timeout_sec=60)
            try:
                payload = base64.b64decode(result.stdout, validate=True)
            except ValueError as error:
                raise ExecutionOutcomeUnknown('Artifact chunk is invalid base64') from error
            if result.return_code != 0 or len(payload) != length:
                raise ExecutionOutcomeUnknown('Artifact chunk is incomplete; preserve snapshot')
            stream.write(payload)
            sha.update(payload)
    if sha.hexdigest() != metadata['sha256']:
        raise ExecutionOutcomeUnknown('Artifact snapshot digest mismatch')
    return {**metadata, 'remote_path': remote, 'chunk_bytes': 8192}
