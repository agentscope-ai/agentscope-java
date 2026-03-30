---
name: git-changelog
description: Use this skill when the task involves generating a changelog, release notes, or commit summary from a Git repository's history. Suitable for tasks like "generate a changelog for v2.0", "summarize what changed since last release", or "create release notes from git commits". Requires access to a Git repository.
---
# Git Changelog Skill

Generates formatted changelogs and release notes from Git commit history.

## Available Scripts

- `scripts/generate_changelog.py` — Read git log between two refs and produce a grouped Markdown changelog (features, fixes, chores)

## Usage

```
python3 scripts/generate_changelog.py --from v1.0.0 --to HEAD --output CHANGELOG.md
python3 scripts/generate_changelog.py --from v1.0.0 --to v2.0.0
```
