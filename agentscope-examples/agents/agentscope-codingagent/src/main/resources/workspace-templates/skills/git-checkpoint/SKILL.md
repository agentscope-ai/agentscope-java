---
name: git-checkpoint
description: Use git as a safety net - create a checkpoint commit before risky or large changes and roll back cleanly if a change makes things worse. Use before multi-file refactors.
---

# Git Checkpoint and Rollback

You work in a git-backed sandbox. Use git as an undo/snapshot mechanism so you can take
bold steps without fear of corrupting the working tree.

## Checkpoint before risky work

Before a large refactor or anything you are unsure about, save the current good state:

```bash
git add -A && git commit -m "checkpoint: before <short description>" --no-verify
```

Record the commit hash (`git rev-parse HEAD`) so you can return to it.

## Roll back on failure

If a change makes things worse and you cannot quickly fix it, revert rather than piling
on more edits:

- Discard uncommitted changes to a file: `git checkout -- <file>`
- Discard ALL uncommitted changes: `git reset --hard HEAD`
- Return to a checkpoint: `git reset --hard <checkpoint-hash>`

Then re-read the code and try a different approach.

## Rules

- NEVER create `.bak` / backup files — git is your history.
- Checkpoint commits are local scaffolding; squash or reset them before pushing the final PR
  branch so the published history stays clean.
