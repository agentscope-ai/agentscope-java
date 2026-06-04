---
name: code-search
description: Search a codebase efficiently with ripgrep regular expressions, file globs, and git history search. Use to locate symbols, usages, and definitions instead of reading whole files.
---

# Code Search

The built-in `grep` tool matches LITERAL text only. For real regular-expression search and
history-aware lookups, drive ripgrep and git through `execute`.

## Ripgrep (regex)

- Symbol definition: `rg -n "fn\s+\w+\(" src/`, `rg -n --type java "class \w+Service"`.
- Usages of a name: `rg -n "\bmyFunction\b"`.
- Limit by file type: `rg -n --type ts "useEffect\("`.
- List files only: `rg -l "TODO"`; find files by name: `rg --files | rg <name>` or use `glob`.

## Git history search

- When a string was introduced/removed: `git log -S "<string>" --oneline`.
- Who/why a line changed: `git blame -L <start>,<end> <file>`.
- Search tracked files: `git grep -n "<regex>"`.

## Tips

- Prefer a targeted regex over reading whole files when locating symbols or call sites.
- Escape regex metacharacters when you want a literal match, or pass `-F` to ripgrep.
- Narrow scope with a path argument to keep output small (tool output is truncated).
