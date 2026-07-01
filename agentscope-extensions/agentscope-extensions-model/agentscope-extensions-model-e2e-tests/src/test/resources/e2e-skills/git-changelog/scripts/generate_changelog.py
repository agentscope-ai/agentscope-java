#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Generate a grouped Markdown changelog from git commit history."""
import argparse
import re
import subprocess
import sys
from collections import defaultdict


COMMIT_RE = re.compile(r"^(?P<type>feat|fix|chore|docs|refactor|perf|test|ci|build)(\(.*?\))?!?: (?P<desc>.+)")
TYPE_LABELS = {
    "feat": "Features",
    "fix": "Bug Fixes",
    "perf": "Performance",
    "refactor": "Refactoring",
    "docs": "Documentation",
    "chore": "Chores",
    "test": "Tests",
    "ci": "CI",
    "build": "Build",
}


def get_commits(from_ref: str, to_ref: str) -> list[str]:
    result = subprocess.run(
        ["git", "log", f"{from_ref}..{to_ref}", "--pretty=format:%s"],
        capture_output=True,
        text=True,
        check=True,
    )
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def generate_changelog(from_ref: str, to_ref: str, output_path: str | None) -> None:
    try:
        commits = get_commits(from_ref, to_ref)
    except subprocess.CalledProcessError as e:
        print(f"git error: {e.stderr}", file=sys.stderr)
        sys.exit(1)

    grouped: dict[str, list[str]] = defaultdict(list)
    uncategorized = []
    for msg in commits:
        m = COMMIT_RE.match(msg)
        if m:
            grouped[m.group("type")].append(m.group("desc"))
        else:
            uncategorized.append(msg)

    lines = [f"# Changelog\n\n**Range:** `{from_ref}` → `{to_ref}`\n"]
    for ctype, label in TYPE_LABELS.items():
        if ctype in grouped:
            lines.append(f"## {label}")
            for desc in grouped[ctype]:
                lines.append(f"- {desc}")
            lines.append("")
    if uncategorized:
        lines.append("## Other")
        for msg in uncategorized:
            lines.append(f"- {msg}")
        lines.append("")

    changelog = "\n".join(lines)
    if output_path:
        with open(output_path, "w", encoding="utf-8") as f:
            f.write(changelog)
        print(f"Changelog written to {output_path} ({len(commits)} commits)")
    else:
        print(changelog)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description="Generate changelog from git history")
    p.add_argument("--from", dest="from_ref", required=True, help="Start ref (tag or commit)")
    p.add_argument("--to", dest="to_ref", default="HEAD", help="End ref (default: HEAD)")
    p.add_argument("--output", help="Output Markdown file (stdout if omitted)")
    args = p.parse_args()
    generate_changelog(args.from_ref, args.to_ref, args.output)