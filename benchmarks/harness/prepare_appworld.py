# Copyright 2024-2026 the original author or authors.
# SPDX-License-Identifier: Apache-2.0
"""Materialize official Git LFS bundles from the pinned commit, verifying their pointers."""
import hashlib
import importlib.util
from pathlib import Path
import re
import sys
import urllib.request


def materialize(revision):
    if not re.fullmatch(r"[a-f0-9]{40}", revision):
        raise ValueError("An immutable AppWorld commit is required")
    root = Path(importlib.util.find_spec("appworld").submodule_search_locations[0])
    for name in ("apps.bundle", "tests.bundle"):
        path = root / ".source" / name
        pointer = path.read_text()
        expected = re.search(r"^oid sha256:([a-f0-9]{64})$", pointer, re.MULTILINE).group(1)
        size = int(re.search(r"^size (\d+)$", pointer, re.MULTILINE).group(1))
        url = f"https://media.githubusercontent.com/media/StonyBrookNLP/appworld/{revision}/src/appworld/.source/{name}"
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read()
        if len(data) != size or hashlib.sha256(data).hexdigest() != expected:
            raise ValueError("AppWorld LFS object does not match its pinned source pointer")
        path.write_bytes(data)
        print(name, expected, size, flush=True)


if __name__ == "__main__":
    materialize(sys.argv[1])
