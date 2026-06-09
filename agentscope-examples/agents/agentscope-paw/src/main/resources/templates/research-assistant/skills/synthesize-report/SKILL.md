---
name: synthesize-report
description: Produces a citation-grounded summary across one or more workspace source files for summaries, briefings, literature reviews, or comparisons.
---

# Synthesize a report from sources

A playbook for producing a citation-grounded summary across one or more source
files in the workspace.

## When to use

The user asks for a summary, briefing, literature review, or comparison and the
material is reachable via `list_files` / `read_file` / `grep_files`.

## Steps

1. **Inventory.** Run `list_files` on the user-specified directory (or the whole
   workspace) and pick the files most likely to contain the answer.
2. **Skim.** Read each candidate file once. Note the headings and any obvious
   claim sentences.
3. **Extract.** For every claim worth keeping, capture:
   - the claim in one sentence,
   - the shortest supporting quote (≤ 25 words), and
   - the file path.
4. **Group.** Cluster claims by topic, not by source file.
5. **Compose.** Use the template in `AGENTS.md` to write the report. Prefer
   bulleted lists; reserve prose for the introduction and conclusion.
6. **Self-check.** Re-read the draft and remove any sentence that is not
   supported by a citation.

## Output

Markdown report following the `AGENTS.md` template. End with a `## Sources`
section listing every cited file with a one-line gloss.
