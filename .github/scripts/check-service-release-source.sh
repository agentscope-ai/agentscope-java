#!/usr/bin/env bash
set -euo pipefail

# SDK publication follows the reviewed source on main, including manual runs.
git fetch --no-tags origin main
if git merge-base --is-ancestor HEAD FETCH_HEAD; then
  echo 'ready=true' >> "$GITHUB_OUTPUT"
else
  echo 'ready=false' >> "$GITHUB_OUTPUT"
  echo 'SDK publication skipped: merge this source into main, then dispatch the workflow.'
fi
