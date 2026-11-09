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
"""Freeze tracked working-tree files, including uncommitted fixes, as a build artifact."""
import argparse
import gzip
import re
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import xml.etree.ElementTree as ET


# Older candidate commits may contain these unrelated generated files in Git.
# Exclude only their exact directories, not every directory named dist or tests.
SOURCE_EXCLUSIONS = (
    "agentscope-service/tests/session-playground/node_modules/",
    "agentscope-service/tests/session-playground/dist/",
    "agentscope-service/tests/session-playground/test-results/",
)


def freeze(root, destination):
    def git(*args):
        return subprocess.check_output(["git", "-C", str(root), *args])

    base = git("rev-parse", "HEAD").decode().strip()
    diff = git("diff", "HEAD", "--binary")
    names = sorted(p.decode() for p in git("ls-files", "-z").split(b"\0") if p)
    excluded = [name for name in names if name.startswith(SOURCE_EXCLUSIONS)]
    names = [name for name in names if not name.startswith(SOURCE_EXCLUSIONS)]
    destination.mkdir(parents=True, exist_ok=True)
    archive = destination / "candidate-source.tgz"
    with archive.open("wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w|") as tar:
            for name in names:
                path = root / name
                if not path.exists() and not path.is_symlink():
                    continue  # A tracked deletion is part of the candidate.
                info = tar.gettarinfo(str(path), arcname=name)
                info.uid = info.gid = 0
                # Maven resources 3.3.1 compares against mtime=0 for absent targets.
                # Epoch-zero sources can be silently skipped even on a clean build.
                info.mtime = 946684800
                info.uname = info.gname = ""
                if info.isfile():
                    with path.open("rb") as content:
                        tar.addfile(info, content)
                else:
                    tar.addfile(info)
    if git("diff", "HEAD", "--binary") != diff or git("rev-parse", "HEAD").decode().strip() != base:
        raise RuntimeError("Working tree changed while freezing candidate")
    version = ET.parse(root / "pom.xml").getroot().find(
        "{*}properties/{*}revision").text
    sha = hashlib.sha256(archive.read_bytes()).hexdigest()
    metadata = {"base_sha": base, "sha256": sha, "version": version,
                "object_key": "harness-eval/sources/" + sha + "/candidate-source.tgz",
                "diff_sha256": hashlib.sha256(diff).hexdigest(),
                "modified_files": git("diff", "HEAD", "--name-only").decode().splitlines(),
                "source_exclusions": {"prefixes": list(SOURCE_EXCLUSIONS),
                                      "file_count": len(excluded),
                                      "paths_sha256": hashlib.sha256("\n".join(excluded).encode()).hexdigest()},
                "scope": "tracked working-tree files; runner and evaluation scripts frozen separately"}
    (destination / "candidate.diff").write_bytes(diff)
    (destination / "snapshot.json").write_text(json.dumps(metadata, indent=2) + "\n")
    return metadata


def freeze_ref(root, destination, ref):
    """Archive an exact Git commit without checkout or working-tree mutation."""
    root, destination = Path(root).resolve(), Path(destination)
    if not ref or ref.startswith('-'):
        raise ValueError("Candidate ref must be a tag or commit")
    def git(*args):
        return subprocess.check_output(["git", "-C", str(root), *args])
    commit = git("rev-parse", "--verify", ref + "^{commit}").decode().strip()
    if not re.fullmatch(r"[a-f0-9]{40,64}", commit):
        raise ValueError("Invalid resolved candidate commit")
    # git archive reads the immutable tree, never the index or local modifications.
    destination.mkdir(parents=True, exist_ok=True)
    archive = destination / "candidate-source.tgz"
    excluded = []
    process = subprocess.Popen(['git', '-C', str(root), 'archive', '--format=tar', commit], stdout=subprocess.PIPE)
    with process, tarfile.open(fileobj=process.stdout, mode='r|') as source, archive.open("wb") as output:
        with gzip.GzipFile(fileobj=output, mode="wb", mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w|") as target:
                for member in source:
                    if member.name.startswith(SOURCE_EXCLUSIONS):
                        excluded.append(member.name)
                        continue
                    member.uid = member.gid = 0
                    member.uname = member.gname = ""
                    member.mtime = 946684800
                    member.pax_headers = {}
                    target.addfile(member, source.extractfile(member) if member.isfile() else None)
    if process.returncode:
        raise RuntimeError('Candidate git archive failed')
    pom = git("show", commit + ":pom.xml")
    version = ET.fromstring(pom).find("{*}properties/{*}revision").text
    sha = hashlib.sha256(archive.read_bytes()).hexdigest()
    metadata = {"base_sha": commit, "ref": ref, "mode": "git-ref", "sha256": sha,
                "version": version, "object_key": "harness-eval/sources/" + sha + "/candidate-source.tgz",
                "diff_sha256": hashlib.sha256(b"").hexdigest(), "modified_files": [],
                "source_exclusions": {"prefixes": list(SOURCE_EXCLUSIONS), "file_count": len(excluded)},
                "scope": "immutable Git commit; runner and evaluation scripts frozen separately"}
    (destination / "candidate.diff").write_bytes(b"")
    (destination / "snapshot.json").write_text(json.dumps(metadata, indent=2) + "\n")
    return metadata


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(freeze(Path(__file__).resolve().parents[2], args.output), indent=2))
