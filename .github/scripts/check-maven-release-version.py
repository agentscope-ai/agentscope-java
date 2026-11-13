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

"""Require a non-SNAPSHOT version tag matching all independent Maven revisions."""

import argparse
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET


POMS = (
    "pom.xml",
    "agentscope-dependencies-bom/pom.xml",
    "agentscope-distribution/agentscope-bom/pom.xml",
)
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
VERSION_TAG = re.compile(
    r"v(\d+\.\d+\.\d+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?)"
)
EXCLUDED_MODULES = {
    "agentscope-examples",
    "agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-e2e-tests",
}


def check_version(root: Path, tag: str) -> str:
    match = VERSION_TAG.fullmatch(tag)
    if not match or "SNAPSHOT" in tag.upper():
        raise ValueError(f"Expected a non-SNAPSHOT release tag such as v2.1.0-BETA2: {tag}")
    version = match.group(1)
    for filename in POMS:
        pom = ET.parse(root / filename).getroot()
        revision = pom.findtext("m:properties/m:revision", namespaces=NS)
        if revision != version:
            raise ValueError(f"{filename}: revision {revision!r} does not match tag version {version}")
        if pom.findtext("m:version", namespaces=NS) != "${revision}":
            raise ValueError(f"{filename}: project version must use ${{revision}}")
    return version


def release_projects(root: Path) -> list[str]:
    """Select library modules recursively; Maven 3 exclusions are not recursive."""
    projects = []

    def visit(directory: Path) -> None:
        relative = directory.relative_to(root).as_posix()
        if relative in EXCLUDED_MODULES:
            return
        projects.append(relative)
        pom = ET.parse(directory / "pom.xml").getroot()
        for module in pom.findall("m:modules/m:module", NS):
            visit(directory / module.text)

    visit(root)
    return projects


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    args = parser.parse_args()
    try:
        version = check_version(Path.cwd(), args.tag)
        projects = ",".join(release_projects(Path.cwd()))
    except (ValueError, OSError, ET.ParseError) as error:
        raise SystemExit(str(error)) from error
    print(f"Maven release version: {version}")
    if output := os.environ.get("GITHUB_OUTPUT"):
        with open(output, "a", encoding="utf-8") as stream:
            stream.write(f"version={version}\n")
            stream.write(f"projects={projects}\n")


if __name__ == "__main__":
    main()
