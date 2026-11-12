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

"""Import a published Service chart into the public Helm repository."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import time
from urllib.error import URLError
from urllib.request import urlopen

import yaml

UPSTREAM = os.environ.get("MANAGED_AGENTS_GITHUB_REPOSITORY", "")
HELM_REPOSITORY = os.environ.get("MANAGED_AGENTS_HELM_GITHUB_REPOSITORY", "")
HELM_URL = os.environ.get("MANAGED_AGENTS_HELM_URL", "").rstrip("/")


def gh_api(endpoint, payload=None):
    command = ["gh", "api", "-H", "X-GitHub-Api-Version: 2026-03-10", endpoint]
    if payload is not None:
        command += ["--method", "POST", "--input", "-"]
    result = subprocess.run(
        command, input=json.dumps(payload) if payload is not None else None,
        capture_output=True, text=True, check=True,
    )
    return json.loads(result.stdout)


def release_digest(release, version):
    if release.get("draft") or release.get("tag_name") != f"v{version}":
        raise ValueError("Expected a public Release with the requested tag")
    filename = f"managed-agents-{version}.tgz"
    asset = next((a for a in release.get("assets", []) if a["name"] == filename), None)
    if not asset or not re.fullmatch(r"sha256:[0-9a-f]{64}", asset.get("digest") or ""):
        raise ValueError("Published Release chart or its SHA-256 digest is missing")
    return asset["digest"].removeprefix("sha256:")


def verify_publication(index, archive, version, digest):
    if not isinstance(index, dict):
        raise ValueError("Public Helm index is not a YAML mapping")
    filename = f"managed-agents-{version}.tgz"
    entries = index.get("entries", {}).get("managed-agents", [])
    matching = [entry for entry in entries if entry["version"] == version]
    if len(matching) != 1:
        raise ValueError("Requested chart version is missing or duplicated in the public index")
    entry = matching[0]
    if (entry.get("digest") != digest
            or entry.get("appVersion") != version
            or entry.get("urls") != [f"{HELM_URL}/{filename}"]):
        raise ValueError("Public Helm index does not match the original Release chart")
    if hashlib.sha256(archive).hexdigest() != digest:
        raise ValueError("Public chart bytes do not match the original Release chart")


def sync(version):
    if (not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", version)
            or "SNAPSHOT" in version.upper()):
        raise ValueError("Expected a non-SNAPSHOT Service version without v")
    if not UPSTREAM or not HELM_REPOSITORY or not HELM_URL:
        raise ValueError("Configure MANAGED_AGENTS_GITHUB_REPOSITORY, MANAGED_AGENTS_HELM_GITHUB_REPOSITORY and MANAGED_AGENTS_HELM_URL before publishing")
    release = gh_api(f"repos/{UPSTREAM}/releases/tags/v{version}")
    digest = release_digest(release, version)
    dispatch = gh_api(
        f"repos/{HELM_REPOSITORY}/actions/workflows/publish.yml/dispatches",
        {"ref": "main", "inputs": {"version": version}},
    )
    run_id = dispatch["workflow_run_id"]
    if not isinstance(run_id, int) or run_id <= 0:
        raise ValueError("Helm workflow dispatch did not return a valid run ID")
    run_url = f"https://github.com/{HELM_REPOSITORY}/actions/runs/{run_id}"
    print(f"Helm repository sync: {run_url}", flush=True)
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(summary, "a", encoding="utf-8") as stream:
            stream.write(f"### Helm {version}\n\n[Repository workflow]({run_url})\n\n")
    subprocess.run(
        ["gh", "run", "watch", str(run_id), "--repo", HELM_REPOSITORY,
         "--exit-status", "--interval", "10"], check=True,
    )
    # Pages deployment can finish before all CDN edges serve the new index.
    for attempt in range(18):
        try:
            with urlopen(f"{HELM_URL}/index.yaml?run={run_id}", timeout=20) as response:
                index = yaml.safe_load(response.read())
            with urlopen(f"{HELM_URL}/managed-agents-{version}.tgz", timeout=20) as response:
                archive = response.read()
            verify_publication(index, archive, version, digest)
            break
        except (URLError, ValueError, yaml.YAMLError):
            if attempt == 17:
                raise
            time.sleep(10)
    print(f"Verified {HELM_URL}: {version} ({digest})", flush=True)
    if summary:
        with open(summary, "a", encoding="utf-8") as stream:
            stream.write(f"[Public Helm repository]({HELM_URL}) verified against the Release SHA-256.\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    sync(parser.parse_args().version)
