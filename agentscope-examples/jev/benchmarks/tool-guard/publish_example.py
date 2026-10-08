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

"""Refresh the documentation's downloadable snapshot from the canonical example files."""
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[4]
BENCH = Path(__file__).resolve().parent
MODULE = ROOT / "agentscope-examples/jev"
PUBLIC = ROOT / "docs/examples/jev"

files = sorted(p for p in BENCH.rglob("*") if p.is_file()
               and "__pycache__" not in p.parts and p.suffix != ".pyc" and p.name != ".gitignore")
files += [MODULE / "README.md", MODULE / "pom.xml",
          MODULE / "src/main/java/io/agentscope/examples/jev/JevToolGuardBenchmark.java",
          MODULE / "src/main/java/io/agentscope/examples/jev/JevTraceBenchmark.java",
          MODULE / "src/test/java/io/agentscope/examples/jev/JevToolGuardBenchmarkTest.java",
          ROOT / "docs/v2/zh/jev/guides/tool-guard-api.md"]
(PUBLIC / "tool-guard").mkdir(parents=True, exist_ok=True)
(PUBLIC / "tool-guard/README.md.txt").write_bytes((BENCH / "README.md").read_bytes())
with zipfile.ZipFile(PUBLIC / "tool-guard-benchmark.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(files):
        entry = zipfile.ZipInfo(str(path.relative_to(ROOT)), date_time=(2026, 9, 26, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(entry, path.read_bytes())
print(f"Published {len(files)} files from the example directory")
