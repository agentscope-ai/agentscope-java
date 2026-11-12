# Managed Agents general-agent evaluation plan

Updated: 2026-10-26. Implementation plan; adapters are not implemented. Infrastructure status is based on the 2026-10-24 acceptance record and must be rechecked. [中文](general-agent-evaluation-plan.md)

## 1. Goal and initial scope

Evaluate planning, tool use, dialogue, state retention and execution reliability. Coding is not the initial focus. Hold the model and tool environment fixed when comparing Harness configurations or versions.

| Priority | Benchmark | Capability | Suggested initial coverage |
| --- | --- | --- | --- |
| P0 | [AppWorld](https://github.com/StonyBrookNLP/appworld) | Stateful, multi-step tasks across applications | Pin 20 dev tasks, then expand |
| P0 | [τ-bench](https://github.com/sierra-research/tau-bench) | Dialogue, business tools and policy compliance | Pin 20 retail tasks; add airline later |
| P1 | [GAIA](https://huggingface.co/datasets/gaia-benchmark/GAIA) | Search, file understanding and tool-assisted reasoning | Select 20 validation tasks with available answers; record levels and modalities |
| P2 | [WebArena](https://github.com/web-arena-x/webarena) | Browser-based business workflows | Add after reliable environment reset |
| Supplement | [BFCL](https://gorilla.cs.berkeley.edu/leaderboard.html) | Tool selection, arguments and multi-turn calls | Tool-protocol regression, not a substitute for task evaluation |

These are internal pilot sizes, not full leaderboard results. Defer SWE-bench. Prefer AppWorld's function-calling/MCP track initially; report interactive-code execution separately. Pin the original τ-bench repository commit; treat τ²/τ³ and later versions as separate benchmarks rather than following upstream main automatically.

## 2. Integration architecture

```text
Eval Runner -> Session API -> Gateway / Agent Service -> Agent Harness Runtime
     |                                                           |
 prepare/reset/score                                  server-side tool execution
     v                                                           v
Benchmark Adapter <------------------------------------ Tool Worker / MCP
     |
     +-- isolated application databases, files and browser state per trial

Official Evaluator -> final environment state and output -> scores/artifacts in OSS
```

A trial is one independent execution of one task: a new Session and a clean environment. Reuse that Session for all turns in one τ-bench conversation. The Runner schedules tasks, drives the simulated user, enforces budgets, scores and cleans up; the tested Harness makes agent decisions.

Preserve official tool names, arguments, responses, errors and state transitions. Prefer official MCP servers, otherwise build thin wrappers. Configure Environment bindings before Session creation and validate trial routing server-side, rather than trusting model-provided task IDs. Keep answers, evaluator code, hidden task details and simulator-private instructions outside the tested Agent's accessible environment.

## 3. Benchmark environments

| Benchmark | Environment customization | Isolation and scoring |
| --- | --- | --- |
| AppWorld | Pinned Python dependencies, task data and an independent AppWorld instance; official MCP/function tools. A separate code track must use its stateful execution environment rather than an ordinary shell | Restore initial task databases and execution state for each trial; retain official evaluator results |
| τ-bench | Python, domain data/policy, tool service and independent LLM user simulator. Give policy to the Agent and private user goals to the simulator | Tools and simulator share the same trial environment. Feed assistant replies into the official interaction loop; retain its termination and evaluation behavior |
| GAIA | Search, browser, file tools and Python; add PDF/spreadsheet/image/audio support for selected tasks and stage attachments | Report the supported selection and excluded coverage. Apply official answer-scoring rules rather than replacing them with subjective LLM grading |
| WebArena | Official self-hosted sites/databases, authentication state and Playwright/Chromium; configure site URLs | Browser contexts do not isolate database writes: use separate site stacks per worker or serialize/reset task groups. Use the official evaluator |
| BFCL | Pinned tasks, tool schemas, required execution environments and actual tool-call traces | Use category-specific official grading and document adapter differences; final prose alone is insufficient |

Keep benchmark application data in official benchmark environments, separate from platform PostgreSQL. Obtain GAIA under its access conditions and retain data/answers privately. Record time, tool versions and permitted evidence for changing web/search dependencies.

Mirror images into an accessible registry and pin digests. Check ACS compatibility before provisioning extra compute; place environments requiring unsupported privileged containers, nested Docker or system capabilities on separate ECS in the same VPC when necessary.

## 4. Session API lifecycle

Use the repository's [Session API contract](../../docs/v2/en/service/claude-session-api.md), checking deployed versions and capabilities first. This is a design, not an executable script.

1. `adapter.prepare(task, trial)` resets state and stages files/policy, returning environment bindings and task input.
2. `POST /v1/sessions` selects a pinned Agent version and `environment_id`; metadata records run/task/trial identifiers.
3. Submit `user.message` to `/v1/sessions/{session}/events`, with explicit `new_turn` intent and a persisted `Idempotency-Key`.
4. Poll Turn state and paginate `/turns/{turn}/output`. HTTP 200 means admission, not completion. SSE is supplementary; disconnection is not task termination.
5. For τ-bench, feed assistant output to the official interaction loop/user simulator and submit the next user message to the same Session. One completed Turn is not a completed task.
6. At official termination or the predefined budget, stop further actions and ensure in-flight tools have stopped; score the final environment state.
7. Archive output, tool traces, scores, errors and artifacts; release the trial environment. Do not assume Session deletion or file-upload APIs exist.

The current protocol does not support client-submitted tool results. Benchmark tools must execute through the server-side tool/MCP path, whose supported capabilities must be checked before implementation. On timeout, submit `user.interrupt` for the specific turn_id and wait for actual termination and environment quiescence. Retry uncertain admissions with the original body/key; do not blindly create another Turn.

## 5. Shanghai deployment checklist

Recorded infrastructure: ACS, PostgreSQL, Redis, non-Serverless RocketMQ with Lite capability and six Topic/group pairs, OSS, NAT and SLS. Use `managed-agents` for the platform and `managed-agents-eval` for evaluation. Isolate benchmark state from platform databases.

- [ ] Recheck resources, quotas, credentials and connectivity. The Kubernetes API Server endpoint is for cluster administration, **not the Session API**.
- [ ] Complete the namespace and pull credentials in the existing ACR. Reuse `sca-registry.cn-hangzhou.cr.aliyuncs.com`, with planned namespace `managed-agents-eval-shanghai`; creation was blocked by missing owner-account management access.
- [ ] Select platform code/image versions; deploy Gateway, Control Plane, Agent Service, Agent Harness Runtime and Tool Worker; apply matching table migrations and business-role grants.
- [ ] Configure models, tools/MCP, application API keys and Session API access. Do not expose platform credentials to benchmark environments.
- [ ] Verify real RocketMQ Lite send/consume and Session creation, tool execution, output and cancellation.
- [ ] Build pinned AppWorld/τ-bench images and implement environment services, scratch storage and per-task reset. Start with one worker, then increase to 2–4.
- [ ] Run the Runner as an independent Job/process; archive by run/task/trial in the evaluation OSS bucket and enforce environment TTL/failure cleanup.

Environment services, tool adapters and lifecycle management are the main customization points; changing platform container images alone is insufficient.

## 6. Metrics, deliverables and rollout

Report official metrics separately per benchmark, plus success rate, latency, Agent tokens/cost, tool calls, retries and recoveries. Account for τ-bench simulator tokens/cost separately. Use official `pass^k` calculations for repeated-run reliability; do not confuse this with `pass@k`.

Separate agent/task failures, platform failures, initialization failures and evaluator failures. Report all scheduled tasks and end-to-end completion as well as scores among valid executions; never silently discard failed cases.

Compare official reference agents where adaptable, or Harness versions, under the same model version/settings, tools, tasks, initial state and budgets. Keep injected-failure recovery experiments separate from standard benchmark scores. Freeze comparison configurations before running test splits.

Deliver a Runner, Session client, AppWorld/τ-bench adapters, environment images/manifests, official evaluator wrappers, run manifests, per-case JSONL and a Markdown summary. Record benchmark commit/split, task/trial, seed, model/Harness versions, image digests, Session/Turn IDs, score/failure reason, timing/tokens/cost and artifact paths; exclude credentials from snapshots.

| Stage | Work | Acceptance |
| --- | --- | --- |
| 1 | Platform deployment and common Runner | A Session changes test state through server-side tools; output, timeout and cleanup verified |
| 2 | Five serial smoke tasks each for AppWorld and τ-bench | Real tools, official grading, traces and resets work; agent mistakes are classified correctly |
| 3 | Twenty pinned tasks each, 2–4 workers, three repetitions | No cross-task state contamination or omitted failures; comparable quality/reliability/cost report |
| 4 | Expand coverage, add GAIA, then assess WebArena/BFCL | State selection boundaries; claim leaderboard comparability only when official settings are satisfied |

Initial completion means finishing the pinned AppWorld and τ-bench suites through the real Session API, explaining every outcome, and rerunning under the same configuration to compare Harness versions.
