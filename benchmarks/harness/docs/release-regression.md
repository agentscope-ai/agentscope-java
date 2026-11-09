# Harness release regression and comparison

Core release tooling validates frozen inputs, checks production framework contracts and compares local
archived evidence. Execution and evidence collection are supplied by a separate runner.
中文：[发布回归](release-regression_zh.md)。

| Suite | Scope |
| --- | --- |
| framework-contract-v1 | 27 production test classes covering sessions, memory, compaction, subagents, permissions and related contracts |
| release | 18 tasks × AgentScope × 3 rollouts = 54 trials |
| comparison | 17 common tasks × 3 agents × 3 rollouts = 153 trials |
| capabilities | 127 independently maintained capability tasks with declared task-outcome scoring |

The release profile selects 18 canonical Harness tasks. Full and smoke profiles share the same task
files, including clarified output keys, skill formatting and session instructions.
Four tool tasks require trusted per-trial receipts and a generated lookup ticket; candidate-written
files cannot satisfy those assertions. The runner must host these fixtures outside the candidate
workspace. Mock framework tests complement real-model trials; neither replaces the other's evidence.

## Frozen contract

`runtime.release.freeze_contract` hashes selected task content, local fixtures, external source locks and adaptations,
selected environment recipes, scorers, adapters, versions, model/API,
execution limits, Harbor and configured base images. Concurrency and run ID are excluded. Candidate
source may change; changes to the evaluation contract require a new baseline. Consolidating task definitions changes the evaluation fingerprint; historical suite runs must not
silently become a baseline for the canonical library. Keep actual image digests and package versions in execution evidence;
image recipes do not guarantee bit-identical rebuilds.

## Local commands

From `benchmarks/harness`, with protocol dependencies installed:

```sh
python eval.py release validate --config configs/suites/release.json
python eval.py release contracts --repo /path/to/candidate-repo --output artifacts/contracts-unique
python eval.py release compare --candidate /path/to/candidate-evidence \
  --baseline /path/to/baseline-evidence --output artifacts/review-unique
```

Contract checks run the declared production tests with fail-closed XML verification. Missing, skipped
or failed required tests do not pass. `--verify-only` inspects existing XML and explicitly records that
it is not a fresh execution. Immutable release candidates must contain those contract classes.

Offline comparison needs `manifest.json`, `summary.json` and the per-trial `result.json` files named by
`archive_path`. It verifies that summary rows match the archived trial records, makes no model calls
and does not replace missing results. It writes `release-gate.json`, `release-gate.html` and a trial
report; evidence links point to the source directory.

## Decision semantics

| Status | Meaning |
| --- | --- |
| pass | Valid evidence, passing critical tasks, comparable baseline and locked model snapshot |
| review | First baseline admission, unlocked model alias or per-task regressions within the overall threshold |
| fail | A critical task fails a rollout or the overall pass-rate drop exceeds the frozen policy |
| invalid | Missing, unsupported, unscored or unverifiable trials; absent framework evidence; incompatible baseline |

All predeclared rollouts remain in the denominator. Improvements do not erase individual regressions.
The default five-percentage-point threshold is an operational policy, not a significance test.
Automation must require `release_passed=true`; completed collection alone does not certify a release.
Comparison exits 0 for pass, 3 for review and 2 for fail/invalid.

A runner integrating `runtime.schedule.run_schedule` must preserve the preflight barrier, isolated
per-trial state, no whole-trial automatic retries, separate verification and trusted evidence collection.
The core package does not implement deployment, recovery or cloud publishing. Store generated evidence
under ignored `artifacts/` and keep source revisions, licenses and frozen contracts in the repository.
