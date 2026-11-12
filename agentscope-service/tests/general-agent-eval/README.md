# General agent evaluation

Run AppWorld and the original τ-bench through the deployed Managed Agents Session API. The Harness performs reasoning and tool dispatch; the trusted Runner owns simulation, reset and official scoring. Historical results and the original priority table remain in [the plan](consistency-plan.en.md); implementation and acceptance evidence are tracked in [the implementation record](consistency-implementation.en.md).

Hangzhou delivery, 2026-11-01: all 21 engineering acceptance scenarios passed, and all six official τ-bench/AppWorld smoke trials scored 1. See the [final delivery report](reports/hangzhou-consistency-20261101.en.md).

## 2026-11-03 native-context batch

Use the [single-attempt native profile](evaluation-native-single-profile.json) and [real-window model profile](../../deploy/profiles/qwen38-native-eval.json) for new batches, preserving frozen historical profiles. Enable native context management in all Harnesses: model window 1,000,000 and model output capability 131,072, while the shared per-request output budget stays 8,192. See [official model specifications](https://help.aliyun.com/zh/model-studio/qwen3-8-max). Do not impose a shared compaction threshold; retain framework-native compaction, pruning and history retention.

Run each task once per Harness initially. Consider at most one separate retry after failure triage, preserving initial scores. The new AppWorld profile allows 1,800 seconds, 120 model/tool calls and sixteen million cumulative tokens to cover longer chains than the old 900-second baseline; auxiliary calls consume the same budget. Check the path using separate historical smoke tasks, then freeze budgets before formal execution without adjusting them in response to formal failures.

`run_appworld.py --split dev --task-list /private/new-tasks.json` accepts an explicit JSON ID array. Preserve order and reject empty, duplicate, wrong-split or out-of-range selections; nondefault offset/count cannot accompany a task list. Preflight exactly validates evaluated model bindings and records the full catalog hash and extra-binding count, preserving unrelated model aliases. See the [next-phase plan](../../../docs/poc/benchmark/harness-eval-next-phase-20261102.en.md).

## Frozen inputs

| Item | Version / contract |
| --- | --- |
| Original τ-bench | `59a200c6d575d595120f1cb70fea53cef0632f6b`, retail/test |
| AppWorld | `42b5bcf3cd334fee33f0c37c02070a9f5807add5`, official HTTP MCP/content_only |
| AppWorld data | 0.2.0/dev; SHA256 `c9299e6cafe92bce4592a3c117c047c973d1554a667c21dd81537e78ab2f532e` |
| Python / MCP | Separate Python 3.11 environments; MCP 1.30.0 |
| AppWorld compatibility | Pydantic 2.12.3, FastAPI 0.119.1, Starlette 0.48.0, sse-starlette 3.0.3, SQLAlchemy 2.0.44, SQLModel 0.0.27 |
| Agent model | [Shared model profile](../../deploy/profiles/qwen38-eval.json): qwen3.8-max, OpenAI Responses |
| User simulator | qwen-plus, temperature 0, no client retry; parameters, prompt hashes, returned model and usage retained |
| Sampling | Smoke: 1 repeat. Formal: 3 repeats. Rotate targets per task/repeat; retain every attempt |

The requirements locks and `bootstrap_environment.py` install pinned public sources, verify Git LFS objects and the data bundle, and export the actual installed versions. Model aliases remain mutable; a returned model name does not pin provider weights. No fixed seed or deterministic sampling is promised.

## Environment and isolation

Use one 40 GiB ESSD PVC for datasets, separate virtual environments, SQLite leases/audit, and outputs. The environment Deployment has two containers (each 2 CPU/4 GiB): services and Runner. Only the Runner mounts platform and simulator credentials. Services receive only the transport/control keys. A temporary Linux build Pod uses 8 CPU/16 GiB and an 80 GiB cache PVC; it is separate from tool execution.

`supervise_environment.py` runs the official AppWorld API on 9000, official MCP on 10000, τ MCP/control on 8000/8001, a synthetic acceptance MCP on 8003, and the audit gateway on 8002. Only the audit gateway is exposed to agents. Its per-trial MCP URL and persistent owner lease prevent late calls from entering a later case. Restarting an environment interrupts and fences existing cases; pending external outcomes prevent reuse.

Prepare the PVC with `bootstrap_environment.py --root /eval` and a Linux `uv` executable at `/eval/uv`, then use `deploy_environment.py`. The cluster must already provide the namespace, PVC and pinned bootstrap image. Private configuration stays outside Git, mode 0600:

- `targets`: name, namespace, Session `cluster_url`, authentication headers.
- `simulator_base`, `simulator_key`, `control_key`, `tool_key`, `tau_commit`, `appworld_commit`.
- `control_url`: Runner access to port 8001.
- `evaluation_gateway_url`: Runner access to port 8002.
- `evaluation_gateway_cluster_base`: service URL reachable from all Harness/Tool Worker Pods.

Inside the supplied Deployment, Runner control/audit URLs use localhost. Local runners use port forwarding; AppWorld additionally requires its API forwarded to local port 9000 and matching local data/dependencies. Agent MCP URLs always use the cluster service and a unique trial path. Do not expose AppWorld's API control endpoints as agent tools or put provider/platform keys in the shared PVC.

## Deploy and verify

`apply_model_profile.py` applies the shared model/evaluation contracts and a verified release manifest. `--overlay-dir` exports equivalent Helm values for future upgrades. The chart accepts per-component `image` references pinned by digest and opt-in `evaluation.contextMode` / `evaluation.toolEnvelope` / `evaluation.toolSurface` controls. Preserve the installation's existing infrastructure and credential values when applying those overlays.

```bash
python apply_model_profile.py --kubeconfig /private/kubeconfig \
  --profile ../../deploy/profiles/qwen38-eval.json --evaluation-profile evaluation-profile.json \
  --config /private/runner.json --images /private/release.json \
  --namespaces agencore-ma agencore-ma-pi agencore-ma-codex --overlay-dir /results/helm-values

python preflight.py --kubeconfig /private/kubeconfig --config /private/runner.json \
  --model-profile ../../deploy/profiles/qwen38-eval.json --evaluation-profile evaluation-profile.json \
  --release-manifest /private/release.json --output /results/preflight.json
```

Preflight rejects missing components, drifted model catalogs, gateway policy hashes, native controls, replicas/resources, image digests, mock models or unready rollouts. Use new Agents/Sessions after a change. Each batch requires a matching successful preflight from the last hour.

```bash
python acceptance.py --kubeconfig /private/kubeconfig --config /private/runner.json \
  --model-profile ../../deploy/profiles/qwen38-eval.json --profile evaluation-profile.json \
  --output /results/acceptance-new-batch
```

Acceptance uses real Sessions and synthetic tools: ordinary text, nested/long/empty/error/overflow MCP results, a model budget shared across Turns, 429, a truncated stream, a Harness Pod restart during a non-idempotent tool call, and continuous HTTP ownership during a 75-second model wait. The final scenario requires one model call, one execution attempt and zero recoveries, covering interference between legacy MQ repair and HTTP lease renewal. Delay injection is restricted to synthetic fixtures and never used in official datasets. Every invocation identity and attempt is preserved. Restart testing requires Kubernetes access; the local machine can run this controller using port forwards.

## Benchmark batches

```bash
python run_tau.py --config /private/runner.json --model-profile ../../deploy/profiles/qwen38-eval.json \
  --evaluation-profile evaluation-profile.json --preflight /results/preflight.json \
  --output /results/tau-new-batch --tasks 0 1 2 3 4

python run_appworld.py --config /private/runner.json --model-profile ../../deploy/profiles/qwen38-eval.json \
  --evaluation-profile evaluation-profile.json --preflight /results/preflight.json --root /eval/appworld-root \
  --output /results/appworld-new-batch --count 5

python report.py /results/tau-new-batch
```

`--formal` selects the profile's frozen repeat count. Explicit `--repeats` must match that mode. A new output directory is required; retries belong to separate batches. The manifest freezes the task list/order and definitions. AppWorld checks source DBs plus the saved live state; τ checks the official initial data hash. Only official evaluators assign rewards. A failed/uncertain trial stops reuse of that slot and retains its lease, evidence and cancellation receipt.

After terminal state is confirmed and the environment is safely released, `continue_batch.py` runs only the unattempted suffix of the original schedule. It checks frozen source hashes, exited prior runners, matching manifests, a unique ordered prefix of attempts, and absence of unrecorded trials before invoking the frozen Runner. Failed attempts are retained and never retried. Results go to new `tau-part-NNN` / `appworld-part-NNN` fragments with separate provenance, preserving original files and scores. The helper does not cancel tasks, release leases or reset uncertain business state; those steps require confirmed state and saved recovery evidence first.

`recover_appworld_case.py` is a separate operational guard restricted to batch `formal-business-20261101-182203`. It recognizes only the observed Codex `usage_unavailable` condition: cancelled exact Turn, terminal Session, settled requests, complete provider streams/usage, and the matching accounting/event discrepancy. Under the continuation lock, it verifies the frozen schedule/code and exact lease owner, saves evidence and Session events, fences the case, exports all final business databases, verifies unchanged original results, then releases the original lease. It does not retry the failed trial, reconcile usage, reset state or assign a score. An unfamiliar condition or an interrupted mutation stops for inspection. The private local controller archives the evidence before fresh preflight and a new suffix launch; it remains dependent on the local machine being available. This reduces recovery gaps without changing the serial benchmark conditions.

For an explicitly authorized parallel cutover, `parallel_batch.py` partitions only the untouched suffix into one lane per Harness, preserving each target's task/repeat order. Every lane has its own AppWorld API/MCP, audit database, lease database and PVC; its model catalog points to that lane's gateway. The frozen Runner and official scorer are reused. `drain_serial_batch.py` watches completed result records and briefly freezes the scheduler to verify an idle boundary; if a new attempt exists it resumes immediately. Only after confirming the last Session terminal, no unrecorded attempt and no active/pending request does it terminate the idle scheduler and release its lease. This preserves every prior result and does not cancel Sessions/tools. The serial exit `-15` is recorded as an intentional scheduling handoff. Parallel parts have independent provenance and must be merged by unique planned keys, with both execution phases disclosed.

`deploy_environment.py --seed-url ... --seed-sha256 ...` can initialize an empty lane PVC from a trusted dependency/data archive through `seed_environment.py`. The seed contains pinned Python environments, sources and immutable task data; credentials, audit/lease databases and results are excluded. The optional initializer is never used to overwrite an existing environment. ConfigMaps use server-side apply to avoid the client-side annotation size limit.

`merge_parallel_results.py` verifies completed private archives offline: the sealed serial prefix, each lane's ordered suffix, unique trial identities, frozen source and configuration manifests, and part provenance. It requires the full 360-attempt schedule and preserves every unscored failure. Generated reports include per-trial phase/start time/provider usage, phase summaries, and initial-state/definition/task/tool-catalog hash comparisons with missing evidence explicit. It never launches or scores trials. Run it only after all lane processes are terminal and requests/leases have settled; the private collection controller enforces those live checks and archives the official experiment outputs and SQLite backups. Only generated report files are publishable; `results.private.jsonl` and raw archives remain private.

`export_session_trajectories.py` additionally exports complete terminal Session histories and every Turn output into private archives. Session history uses `next_page` / `page`, while Turn output uses `has_more` / `next_cursor` / `after`; recovery and export handle both contracts. Exports reject nonterminal Turns, missing recorded Turns and duplicate history events. Trials that failed before Session creation are explicitly marked as such. This is read-only evidence collection and does not alter benchmark scoring or replay requests.

The private parallel collector retains complete compressed archives while extracting only frozen code, manifests, provenance and result rows needed for merging. Every full Session trajectory is hashed directly from the archive and checked against its export receipt; raw databases and duplicate case files remain in the archive. This reduces local disk use without dropping evidence. Missing, extra, duplicated or changed trajectories fail verification.

AppWorld, remote-state export and the official evaluator run in a separate subprocess. Its simulated task clock cannot change the Runner's real deadlines or elapsed time. Initialization and evaluation RPCs have bounded control timeouts. Failure terminates only the local controller, preserving remote state and the environment lease until terminal-state recovery is confirmed.

Before initializing each AppWorld case, the Runner calls the private reset endpoint in `appworld_api.py` to clear remote database connections and model-hash caches together. It is excluded from MCP and requires the control credential plus the current lease owner; unfinished cases or pending requests block the reset. Full initial-state fingerprints remain enforced.

Run `metrics.py` before and after a batch with `--kubeconfig`, `--config`, `--output`, and `--before` for the latter snapshot. It records cgroup throttling, memory/OOM and Pod restart evidence. A replaced Pod or unavailable counter cannot establish absence of throttling. Shared workload activity remains part of these counters.

## Interpretation

`evaluation-profile.json` is the short-context baseline: explicit compaction disablement, a common text JSON tool envelope, a 600000-byte input guard and a 65536-byte tool-result cap. Both tracks set `evaluation.toolSurface=mcp-only`: native subagent, skill and other implicit tools are disabled. Actual model tools must match the current case's MCP catalog before cross-harness schema comparison; the first harness cannot establish undeclared capabilities as the baseline. Overflow returns an observable tool error; it never silently truncates structured content. Context-limit samples are out of scope for this mode. Native tool names can be reversibly mapped; effective schemas and business policy preservation are audited after SDK formatting.

`evaluation-native-profile.json` preserves native compression/pruning under the same external budgets. Codex's native local summary path is admitted and charged separately from reasoning, and its history remains in native checkpoints. Native auxiliary requests without tools are separately classified by the gateway. Compaction algorithms, prompts, retained history and token estimators are framework behavior; their effects should be compared, not described as identical. Text-envelope mode is not a multimodal equivalence claim.

Reports retain all attempted failures, planned-but-unrun cases, paired scored cases, end-to-end and scored-only success, Wilson intervals, error rates, budget/provider/simulator usage, repeated tool decisions and durations. The gateway forwards SSE immediately and records provider first-byte/first-text and complete-response durations. These measure the common transport, not the time a console renders text. Server Turn lifecycle intervals are recorded separately from client observations at a 2-second polling interval; these do not isolate exact projection service time. Unknown provider usage remains unknown; token admission can overshoot by one response. SDK zero prices are placeholders, so cost remains unavailable without a verified tariff/bill.

`report.py` also writes `cases.md`, comparing each task across Harnesses with official scores in repeat order. The `tasks` section of `report.json` retains each trial/Session, status, duration and budget usage. Execution failures and unrun attempts have a `null` official score, not an official zero. `repeat_reliability` reports the fraction of fully scored tasks that succeeded on every repeat, with scoring coverage explicit; this is not `pass@k`. Repeats and variants of the same task template are correlated, so trial-level Wilson intervals are descriptive and cannot by themselves establish statistical superiority.

`model_usage_coverage` reports audited model requests, missing usage, incomplete response streams and missing trial audits for each trial and Harness. Observed tokens are labeled as a lower bound when coverage is incomplete. Saved budget snapshots, including `in_flight_calls`, describe the recorded trial state rather than live outstanding requests.

Per-trial `failure_category` and per-Harness `failure_categories` distinguish official task failures, provider errors, evaluation wait deadlines and unavailable model usage. These describe recorded outcomes without inferring root causes. An unavailable-usage denial is not evidence of numeric budget exhaustion; execution failures still have no official score.

Do not mix historical qwen-plus, different profiles, smoke retries, or native-context results into one ranking. Original τ-bench tasks are historical; do not combine them with τ²/τ³. Small smoke batches do not replace the planned 360-trial formal comparison.

## Local checks

```bash
python3 -m unittest discover -s managed-agents/tests/general-agent-eval -p 'test_*.py'
```

Java, TypeScript, Rust, HTTP integration, Helm rendering and protocol checks are listed with actual outcomes in the implementation record. Public reports exclude credentials and raw model prompts; private trajectories retain the official evaluation evidence.
