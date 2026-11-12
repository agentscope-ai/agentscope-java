# Maintained tasks and external benchmarks

中文：[题库说明](README_zh.md)。

| Source | Tasks | Maintenance |
| --- | ---: | --- |
| `harness/` | 26 | Owned tasks, edited directly |
| `capabilities/` | 127 | Owned capability tasks, edited directly |
| Terminal Bench | 8 | Fixed commit and overlays in `../external/terminal-bench/` |
| Business | 807 | 165 tau-bench and 642 AppWorld IDs with one template in `../external/business/` |

Suites select 968 unique IDs. Their selections overlap: full still selects 63, release 18 and comparison
17. There are no per-suite copies or v1 folders. The 12 reference cases without equivalent
executable contracts were excluded; IDs, origins and reasons remain in
[README.md](capabilities/README.md). Retained per-agent capability limitations
remain explicit and never count as passes.

## Preparation

From `benchmarks/harness`:

```sh
python eval.py release validate --definitions-only
python eval.py prepare-tasks configs/suites/full.json
python eval.py release validate --config configs/suites/full.json
python eval.py export-tasks configs/suites/full.json --output artifacts/export-full
```

Owned tasks need no downloads. Preparation fetches selected external files, verifies locked hashes and
materializes tasks in ignored `artifacts/datasets/`. `prepare-tasks ... --offline` requires cached
downloads. Resolution, export and validation never download. Unknown IDs, duplicate definitions,
unprepared external tasks and modified cache contents fail explicitly.

Export includes task directories and a `manifest.json` recording source revisions and file hashes.
It does not install official business worlds or start agents. See [external preparation](../external/README.md).

## Maintenance

Edit owned instructions, scenarios, fixtures and tests directly. Capability tasks preserve
MIT attribution and original test references. Their synthetic tools live in `../runtime/fixtures/`.
Adaptation is complete: there is no capture/build step or upstream source dependency. These are AgentScope
capability evaluations, not official upstream scores. The scoring profile is `capabilities-task-outcomes-v2`; task IDs use the `cap-` prefix.
Naming and metadata changes require a new evaluation baseline; archived runs retain their original identifiers.

Freeze the repository commit and evaluation contract for each run. Selected tasks, local tools,
external locks/overlays/templates, scoring code and selected environment recipes participate in the
contract. Runners must additionally record actual image digests and official package/data identities.
Contract changes require a new baseline. Caches, reports and intermediate results are not committed.
