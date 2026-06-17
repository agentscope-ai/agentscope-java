---
name: apply-patch
description: Apply multi-file or tricky edits atomically with git apply instead of many fragile edit_file calls. Use when changing several files at once or when edit_file fails to match.
---

# Apply Patch

The built-in `edit_file` does an exact string match on a single file and has no fuzzy
tolerance. For multi-file changes, or when `edit_file` keeps failing to find the string,
construct a unified diff and apply it atomically with git.

## Procedure

1. Write the unified diff to a file in the sandbox (use `write_file` or a heredoc via `execute`):

   ```
   diff --git a/path/to/file b/path/to/file
   --- a/path/to/file
   +++ b/path/to/file
   @@ -10,7 +10,7 @@ context line
   -old line
   +new line
   ```

2. Validate before applying: `git apply --check changes.patch`
3. Apply: `git apply changes.patch` (use `-p0` if paths are not `a/`...`b/`...).
4. If `git apply` rejects the hunk, the surrounding context is stale — `read_file` the exact
   lines again, regenerate the diff with correct context, and retry.

## When to prefer this

- Editing 3+ files for one logical change (atomic, all-or-nothing).
- Repetitive or whitespace-sensitive edits where `edit_file` exact-match struggles.
- Re-applying a patch produced elsewhere (e.g. from a PR or `git diff`).

Always run the verify-changes skill afterwards.
