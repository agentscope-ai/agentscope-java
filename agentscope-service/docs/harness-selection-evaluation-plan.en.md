# Managed Agents Harness Evaluation Objectives and Selection Research

Date: 2026-11-02. [中文](harness-selection-evaluation-plan.md) · Second document: [Implementation guide](harness-selection-evaluation-implementation.en.md)

This document supports product, architecture and selection reviews through two distinct evaluations. Part One asks **how well a Harness solves problems**. Part Two asks **whether Managed Agents can reliably host the execution process**. Each defines its objectives, scenarios, methods and conclusions; both inform selection. Deployment topology, test components, commands, scoring calculations and recovery procedures belong to the second document.

| Evaluation part | Object and central question | Main output |
| --- | --- | --- |
| Part One: Harness effectiveness and capabilities | Under explicit model, tool and environment conditions, how does a Harness understand, plan, use tools and complete tasks? | Capability profiles by scenario, task quality, repeat performance, feature gains and task consumption |
| Part Two: Managed Agents platform requirements | Does the service chain hosting each Harness support persistent execution, tenant isolation and correct side effects? | Hosting eligibility, reliability and capacity evidence, platform/adapter defects, operations and maintenance cost |

**Selection requires both suitable capabilities and compliance with platform requirements.** High task scores cannot offset authorization or side-effect defects; passing platform acceptance does not demonstrate better problem solving. The initial formal candidates are AgentScope Java, Pi and Codex Rust. The outcome may be one default kernel or different kernels for new Sessions in different scenarios.

## Part One: Harness Problem-Solving Effectiveness and Capabilities

### 1.1 Objectives and approach

This part asks one question: **under comparable models, tasks, tools and resource budgets, how well does each Harness solve problems, and what differences do its reasoning and execution mechanisms make?** It evaluates planning, tool use, context management, feedback-driven correction, Skills and collaboration. Models and external tools also affect results, so conclusions remain specific to the validated experimental configurations.

| Question | Approach | Capability evidence |
| --- | --- | --- |
| Which Harness completes tasks better with the same model and tools? | Run verifiable tasks under common conditions, paired by task type | Task success, result quality and reasoning/execution error categories |
| Are native context, Skills and subagent mechanisms effective? | Use native-capability lanes and paired with/without-mechanism experiments | Individual mechanism gains, applicable conditions and additional calls |
| Where are the capability limits as problems become more complex? | Stratify by task difficulty, action-chain length, tool count and context length | Performance degradation, failure points and capability boundaries |
| Is task performance consistent and economical? | Repeat matched tasks while recording quality, model/tool consumption and duration | Repeat success, cost per success and quality/consumption tradeoffs |

Application domains supply tasks for capability experiments. The focus is how different Harnesses handle the same problem and which mechanisms explain the differences. Public leaderboards provide tasks and methods; conclusions come from controlled experiments and traceable per-task evidence.

### 1.2 Capability dimensions to cover

| Capability dimension | Tasks exposing the capability | Assessment focus |
| --- | --- | --- |
| Planning and decomposition | Multiple steps, multiple tools, dependencies and intermediate goals | Effective steps, goal retention and plan revision based on intermediate results |
| Tool selection and invocation | Large tool sets, complex schemas, consecutive calls and tool errors | Selecting tools, constructing arguments, using results and correcting calls |
| Context and memory use | Clarification, long histories, compaction, fact updates and cross-conversation questions | Retaining key information, resolving conflicts, retrieving correctly and abstaining when evidence is insufficient |
| Execution feedback and self-correction | Failed commands/tests, unproductive searches and partially completed tasks | Diagnosing errors, changing strategy and eventually solving the problem |
| Skills and collaboration | Paired with/without-Skills and single-agent/subagent tasks | Repeatable quality gains relative to additional calls |
| Instruction and constraint adherence | Multiple rules, output requirements, untrusted tool content and injection | Recognizing valid instructions, following constraints and retaining the task goal |
| Result quality and completion efficiency | Verifiable answers, code, analysis and document artifacts | Correctness, completeness and usability, with steps, calls and time required for success |

Map benchmarks to these dimensions. Browser/desktop tasks expose perception and action decisions; multi-agent tasks expose delegation and information synthesis; office tasks expose complex artifact generation. Include a comparison only when configurations provide the required interfaces and grading is reproducible; explicitly mark untested areas.

### 1.3 Harness candidates and capability scope

The candidates for this round are **AgentScope, Pi and Codex**, with equal standing in the evaluation. **Every scenario included in the round is scheduled for comparison across all three Harnesses, using the same tasks and grading criteria.** Task scenarios and capability dimensions determine coverage; product positioning or prior impressions do not assign different tasks or presume any candidate's strengths.

| Comparison item | Common requirements for all three Harnesses |
| --- | --- |
| Scenario and task coverage | Cover every scenario actually activated from Section 1.4, with identical task lists, difficulty strata and repetition counts within each scenario |
| Capability dimensions | Evaluate planning, tool use, context and memory, feedback-driven correction, Skills and collaboration, instruction adherence, result quality and efficiency using Section 1.2 |
| Experimental conditions | Fix the model, task instructions, initial environment state, resources and call budgets within each track; declare common tool conditions and permitted native mechanism differences in advance under Section 1.6 |
| Grading and reporting | Use the same official grader version or fixed rubric and consistent reporting rules; include per-task results, repeated performance, task consumption and gradable coverage |
| Mechanism comparisons | Run paired with/without experiments for available context, Skills, subagent and other mechanisms; establish gains from evidence rather than treating feature lists as capability conclusions |

Lacking a native mechanism does not exclude a candidate from the entire task scenario: candidates that can complete the tasks through common tools still participate. If a required task interface is unavailable, record “unsupported” or “not evaluated” with a reason and retain the entry in the coverage matrix. Compare effectiveness on the common gradable tasks and report each candidate's coverage gaps separately; do not improve scores by selectively removing tasks.

Record each candidate's version, model, prompt configuration, tool set and enabled mechanisms. Additional candidates or variants follow the same task scope and fairness rules; use separate controlled experiments to test specific mechanisms. Names, implementation languages and feature lists do not earn capability scores; evaluation results establish the actual strengths.

### 1.4 Benchmark selection and tradeoffs

There is no single authoritative leaderboard covering all Agent capabilities. Select for sensitivity to Harness mechanisms, discriminative task difficulty, verifiable official grading, resettable environments and comparability with existing research. Retain representative suites to cover different capability dimensions with a manageable task set.

**P0 establishes core capability baselines; P1 adds long tasks and mechanism comparisons; P2 extends to experiments needing specialized interfaces or grading conditions.** Validate each environment and grader before freezing tasks, versions and budgets. Never follow upstream `main` automatically.

| Capability | Selected benchmark and priority | Rationale | Task capability covered |
| --- | --- | --- | --- |
| Multi-tool planning and execution | **[AppWorld](https://github.com/StonyBrookNLP/appworld), P0** | Combines planning, tool discovery and cross-app state changes in a controlled environment, exposing complete tool execution chains | Tests task decomposition, tool composition, action ordering and final-state correctness. |
| Multi-turn interaction and constraint adherence | **[τ-bench family](https://taubench.com/): original τ-bench P0; τ²-bench P1** | Combines user interaction, explicit rules and tool calls to expose repeated multi-turn decision performance | Tests clarification strategy, goal retention, constraint adherence and repeated success. |
| General assistance and combined tool reasoning | **[GAIA](https://huggingface.co/datasets/gaia-benchmark/GAIA), P0** | Combines search, file understanding and tools, providing different problem-solving paths | Tests combined search, files and reasoning; modality-specific coverage prevents text results from implying full multimodal support. |
| MCP and function calling | **[MCPMark](https://github.com/eval-sys/mcpmark), P0; [BFCL V4](https://gorilla.cs.berkeley.edu/leaderboard.html), P1 diagnostics** | MCPMark checks state changes; BFCL helps diagnose tool selection, arguments and multi-turn protocol failures | Tests tool selection, argument construction, call sequencing and result use, supported by final-state checks and protocol diagnostics. |
| Terminal and system tasks | **[Terminal-Bench](https://www.tbench.ai/), P0** | Directly tests files, Shell, repair and long execution chains across candidates | Tests command planning, execution-feedback use, error diagnosis and iterative repair. |
| Software engineering | **[SWE-Bench Pro V2](https://labs.scale.com/leaderboard/swe_bench_pro_public_v2?tab=full), P1** | Repository work with network restrictions and clean-environment regrading helps control shortcuts and test contamination | Tests repository understanding, change localization, patch generation and repair driven by test feedback. |
| Search and research | **[BrowseComp-Plus](https://texttron.github.io/BrowseComp-Plus/) and [DeepResearch Bench II](https://agentresearchlab.com/benchmarks/deepresearch-bench-ii/), P1** | A fixed corpus isolates search infrastructure effects; report rubrics add information, analysis and presentation coverage | Fixed retrieval conditions isolate retriever differences; reports test evidence collection, synthesis and usable deliverables. |
| Browser and desktop | **[WebArena-Verified](https://servicenow.github.io/webarena-verified/latest/), P2; [OSWorld 2.0](https://osworld-v2.xlang.ai/), P2** | Resettable web workflows and long tasks spanning desktop applications | Tests interface understanding, action selection, feedback and long-chain goal retention; requires the relevant visual/action interfaces. |
| Long histories and persistent memory | **[LongMemEval](https://github.com/xiaowu0162/LongMemEval), P1** | Tests updates, cross-session retrieval, temporal relationships and abstention | Tests factual retention, updates, retrieval and conflict resolution over long histories. |
| Skills and extensibility | **[SkillsBench v1.1](https://www.skillsbench.ai/), P1** | Paired with/without-Skills runs quantify the extension mechanism's value | Tests Harness discovery, selection and application of Skills, and whether additional calls are worthwhile. |
| Data analysis and office deliverables | **[DABstep](https://arxiv.org/abs/2506.23719) and [GDPval public gold subset](https://openai.com/index/gdpval/), P2** | Verifiable multi-step analysis complements real document/spreadsheet deliverables | Tests analysis steps, computational correctness, result validation and artifact quality. |
| Multi-agent collaboration | **[MultiAgentBench / MARBLE](https://arxiv.org/abs/2503.01935), P2** | Measures collaboration topology, completion and milestones | Tests whether delegation improves task completion and information synthesis, and whether the additional calls are worthwhile. |
| Safety and prompt injection | **[AgentDojo](https://agentdojo.spylab.ai/), P0** | Introduces untrusted instructions into tool results to expose instruction judgment during task execution | Tests instruction-source discrimination and goal retention, measuring task utility alongside attack success. |

AppWorld emphasizes cross-tool planning and state changes; MCPMark emphasizes MCP tool use and execution outcomes. GAIA tests combined problem solving; BrowseComp-Plus tests search strategies under fixed retrieval conditions. Account for overlap by interpreting results by capability dimension rather than summing task counts.

Keep SWE-bench Verified as a historical reference; use a frozen release and explicit contamination controls for the main coding evaluation. Scientific training, full paper reproduction, mobile interaction and cyber competitions are not separate suites in this round because they require additional experimental conditions. Complete the current capability comparisons first, then consider additions to investigate unexplained differences.

#### Why this portfolio

Prefer benchmarks that connect success or failure to planning, context, tool use or correction behavior. State checks, tests and explicit answers provide direct outcome evidence; use fixed rubrics and human review for reports and office artifacts.

- **Establish planning and interaction baselines.** AppWorld and τ-bench combine multi-step tool actions and rules, supporting comparison of execution strategies and multi-turn decisions.
- **Broaden tools and execution modes.** GAIA, Terminal-Bench and MCPMark expose combined problem solving, terminal work and MCP tool use.
- **Directly test Harness mechanisms.** Paired long-history, Skills and collaboration experiments quantify mechanism effects.
- **Observe instruction judgment.** AgentDojo tests how untrusted content affects decisions and task completion.
- **Limit redundant coverage.** Retain representative suites and aggregate by capability dimension so large suites do not obscure other capabilities.

After public benchmarks reveal failure patterns, add small mechanism-diagnostic sets: vary tool count, irrelevant-history length, error feedback or conflicting information. Change one factor per comparison to explain failures, and report diagnostic findings separately from official scores.

### 1.5 Evaluation framework selection

The tested Harness owns reasoning, planning and tool decisions. The evaluation framework supplies tasks, controls conditions, collects trajectories and judges results. It must not perform the capability being evaluated on the Harness's behalf.

| Layer | Selected implementation | Use in capability evaluation |
| --- | --- | --- |
| Task orchestration and result archiving | **Existing `general-agent-eval` Runner** | Reuse task execution, multi-turn user simulation, repeated experiments, trajectory collection and official grading |
| Container tasks and verification | **[Harbor](https://docs.harborframework.com/)** | Reuse environments and verifiers for Terminal-Bench and similar tasks, retaining Harness ownership of planning and execution decisions |
| Dataset and scorer reuse | **[Inspect AI](https://inspect.aisi.org.uk/agents.html), optional** | Reuse suitable datasets, scorers and experiment logs through its external-agent interface |

Harbor here is the evaluation framework. Choose frameworks for task/grading reuse, reproducibility and preservation of the Harness decision process. Verify benchmark versions, tool conditions and scoring conventions after integration; see the [implementation guide](harness-selection-evaluation-implementation.en.md) for execution and integration procedures.

### 1.6 Comparing effectiveness and forming capability conclusions

Turn the three goals into an auditable analysis: **obtain official per-task results, compare capabilities under matched conditions, explain gains through mechanism on/off experiments, then evaluate quality alongside task consumption.** Apply the same process to all three Harnesses. Feature descriptions, individual successes or a single aggregate score cannot establish these conclusions.

#### 1.6.1 Fix comparison conditions first

| Capability lane | Controlled conditions | Allowed differences | Question answered |
| --- | --- | --- | --- |
| **A Common tools and short context** | Model, task instructions, tool schemas/result formats, input length, call budgets and compute resources | Native reasoning loop, necessary protocol prompts and reversible tool-name mapping | Differences in basic planning, tool use and execution loops |
| **B Native context management** | Same model, long tasks, initial inputs, external tools and total budget | Native compaction/pruning, summaries and retained history; auxiliary calls remain budgeted | Context-management benefits and losses on long tasks |
| **C Native capability combinations** | Same tasks, model and total budget; explicit tool and mechanism inventory | Available Skills, files/Shell, subagents and tool discovery | Combined task performance, capability boundaries and additional consumption |

B examines context strategies; C compares enabled capability combinations. Report configurations with different tool sets or interfaces separately. A score difference with multiple changed conditions does not establish a single mechanism's benefit. Within each lane, use identical tasks, difficulty strata and repetition counts across all candidates; fix resources, concurrency and scheduling rules, retaining every attempt. Tune on development data and form formal conclusions on an independent decision set.

#### 1.6.2 Conclusion one: which Harness is stronger on which dimensions

**Step one: score each task execution.** Check final answers, artifacts, tests or environment state rather than accepting the Agent's completion claim.

| Result type | Obtaining a score | Aggregation |
| --- | --- | --- |
| Official success/failure, such as state checks or complete test passage | Use a fixed official rule version: success 1, failure 0 | Average repetitions within each task, then aggregate by task/template group; retain the benchmark's official aggregate metrics |
| Continuous scores or artifact quality, such as reports and office deliverables | Use the official grader or a frozen rubric; fix judge version and prompt, hide candidate identity and audit disputed results | Retain original units and subscores; compare repeated task means, without labeling custom grading as official |
| Multiple metrics, such as utility under injection and attack success | Calculate each official metric separately and declare whether higher or lower is better | Report metrics side by side rather than adding attack success, task success and milestone scores |

Store official scores separately from execution state. For binary tasks, report all three: **end-to-end success rate = official successes / all attempted trials; gradable success rate = official successes / trials with valid official scores; scoring coverage = trials with valid official scores / all attempted trials.** List unattempted work separately. Do not invent official zeros for execution or grading anomalies; retain valid official failures. Separately report the proportion of tasks succeeding in every planned repetition, showing both fully graded tasks and all planned tasks as denominators. Do not select the best attempt as the task result.

**Step two: organize results by capability dimension.** Freeze the task/benchmark-to-dimension mapping and primary metrics before testing. For each dimension, show all three candidates' scores, difficulty strata, repeated performance and coverage under matched tasks and budgets. A benchmark may involve planning, tools and context together: a higher task score first establishes better performance on that task category, while a specific mechanism explanation requires the controls below. Retain original benchmark scores by default. Any aggregate requires predetermined normalization and weights, without counting a task repeatedly because it has multiple capability labels.

**Step three: determine whether the difference supports a conclusion.** Compute paired differences in per-task mean scores or success rates (percentage points) and obtain 95% confidence intervals by resampling independent task/template groups. Repetitions, Turns and tool calls do not create additional independent tasks. Prespecify multiple-comparison correction and the smallest meaningful difference. Claim “stronger on this dimension, configuration and budget” only when the interval's lower bound, oriented so that positive means better, exceeds that meaningful difference and coverage is credible. Otherwise report a possible improvement or insufficient evidence; failure to find a difference does not establish equal capability.

Use common gradable tasks for paired comparisons while showing failures and missingness across all attempts. If missingness concentrates in a candidate or difficult task category, withhold a ranking, assess sensitivity to missing outcomes and, when needed, confirm on a new complete matched batch after repairs.

#### 1.6.3 Conclusion two: which mechanisms explain the differences

Within **the same Harness, task set, model and budget**, change only one predefined mechanism: for example, context compaction on/off, the same Skills provided/withheld, or subagents enabled/disabled. Hold other conditions fixed and verify actual mechanism activation. If a mechanism cannot be toggled independently or other settings change with it, label the experiment as a configuration combination.

**Mechanism quality gain = mean task score with the mechanism − mean task score without it; additional consumption = consumption with the mechanism − consumption without it.** Express binary quality gains in success-rate percentage points and reverse interpretation for lower-is-better metrics. Apply the same paired differences, intervals and meaningful-difference rules, using observable tool actions, retained information and grading checks to explain where improvements or regressions occur.

Claim a mechanism benefit on the tested tasks only when a single-factor comparison supports repeatable improvement. Cross-Harness score gaps, whole-configuration A/B/C differences and trajectory correlations provide clues, not isolated mechanism effects. When several mechanisms change together, report only their combined effect. Even a demonstrated mechanism benefit does not establish that it explains the entire gap between two Harnesses.

#### 1.6.4 Conclusion three: how much task consumption the gains require

Associate official quality and execution consumption with each attempt on the same task set. Include the main model, context summarization and subagent calls in Harness consumption. Record simulated users, graders and environment preparation separately as evaluation overhead.

| Consumption metric | Calculation | Question answered |
| --- | --- | --- |
| Per-attempt consumption | Model/tool calls, tokens by category and execution wall-clock duration across all attempts; report means and distributions | Typical resource demand and long-tail behavior |
| Cost per success | Harness execution cost of all attempted tasks in the same reporting population, including failures, divided by official successes; disclose ungraded trials and missing usage | The actual attempt cost of obtaining a success, rather than the price of successful trials alone |
| Mechanism gain and added consumption | Paired quality, call/token, duration and trustworthy monetary-cost differences on matched tasks | The extra consumption accompanying a mechanism's improvement |
| Quality versus budget | Repeat comparisons at predefined budget levels and plot quality–budget curves | Who performs better at equal budget, and who needs fewer resources to reach a predefined quality target |

Report monetary cost only with complete usage and verified pricing rules; otherwise retain observable consumption or lower bounds and mark monetary cost N/A. Cost per success is undefined when there are no official successes; unknown usage is not zero. Compare durations on matched tasks and execution conditions rather than selecting each candidate's different successful tasks. Summed parallel call durations are not task wall-clock time.

Value judgments require joint quality and consumption evidence: claim improved efficiency when quality stays within a predefined non-inferiority tolerance while consumption falls, or when quality improves at equal budget. If both quality and consumption rise, report the tradeoff rather than forcing a single winner.

#### 1.6.5 Deliverables corresponding to the three conclusions

| Target conclusion | Required results | Scope of the claim |
| --- | --- | --- |
| Which Harness is stronger on which dimensions | Three-candidate capability matrix, per-task official scores, paired differences and intervals, difficulty strata and coverage gaps | Bound claims by tasks, versions, configurations and budgets; withhold rankings when evidence is insufficient |
| Which mechanisms explain the differences | Single-mechanism on/off experiment tables, quality and consumption deltas, activation evidence and failure points | Distinguish verified mechanism benefits, combined effects and untested hypotheses |
| How much consumption the gains require | Call/token/duration distributions, cost per success including failures, and quality–budget curves | Disclose missing usage and pricing assumptions, and make benefit–cost tradeoffs explicit |

These outputs constitute the Part One capability report. Platform recovery, isolation, capacity and maintenance metrics do not enter the capability score. This section defines scoring and conclusion rules; collection, adapters, offline statistics and remaining implementation work are detailed in Section 7 of the second document.

### 1.7 Approximate capability evaluation path

| Stage | Focus | Outcome |
| --- | --- | --- |
| Fix capability dimensions and comparison conditions | Candidates, mechanisms, difficulty, models, tools, benchmark versions and budgets | Capability matrix, tasks and fairness contract |
| Establish core capability baselines | Reuse AppWorld/τ; add GAIA, terminal, MCP and instruction-judgment tasks | Planning, tool-use, correction and constraint-adherence profiles |
| Complete mechanism comparisons and difficulty stratification | Paired long-history, Skills and collaboration conditions; long-chain and difficult tasks | Individual mechanism gains, degradation trends and task consumption |
| Produce capability findings and regressions | Compare meaningful effects; retain trajectories and grading evidence | Capability rankings, failure analysis, evidence limitations and regression suite |

Validate a small task set and grading path before freezing formal experiments. Add diagnostic experiments for observed failure patterns and confirm findings on independent tasks. The [second document](harness-selection-evaluation-implementation.en.md) details execution and grading.

## Part Two: Managed Agents Platform Requirements

### 2.1 Objectives, evaluation object and boundaries

Managed Agents must host **persistent, multi-tenant execution with side effects**, beyond producing correct answers. This part evaluates the service chain comprising Gateway, Control Plane, Agent Service, Agent Harness Runtime, Tool Worker and their storage, messaging and external-tool dependencies, under each candidate Harness configuration.

It asks whether execution survives process failure; repeated requests and unknown outcomes avoid incorrect side effects; tenants and Sessions remain isolated; cancellation, budgets, accounting and outputs have trustworthy semantics; and concurrent operation is maintainable. Attribute defects to the shared platform, Harness adapter, kernel or external dependency rather than labeling every service failure as poor Harness reasoning.

Current Codex directly links a fixed `codex-core` version. It does not run CLI/app-server or use a Node host. Each deployment selects one kernel, and each Session pins its kernel and native state format. This round validates recovery across processes and replicas of the same kernel, not migration of an active AgentScope Session to Codex. See the [current implementation map](../agent-harness-runtime/protocol/implementation-map.en.md). Pi Durable requires its own images, state format and equivalent platform acceptance; ordinary Pi's results cannot establish its eligibility.

The three current candidates share an integrated Session entry point, an engineering prerequisite for this round. ADK, DeepAgents and others lack equivalent project integration and acceptance evidence; additions must complete integration and admission separately. Integration maturity does not confer a Part One capability advantage. Pi Durable's persistence differences are evaluated here; add capability comparisons only if reasoning or execution strategies also change.

### 2.2 Platform requirements and scenarios

| Platform requirement | Scenarios | Acceptance evidence and focus |
| --- | --- | --- |
| Durable state and continued execution | Suspension/resumption, restart, replica takeover, long tool waits and interrupted state commits | Acknowledged state is retained; resumed execution matches committed state; unknown outcomes have explicit handling |
| Side effects and duplicate handling | Duplicate requests/messages, lost responses after tool execution and takeover during writes | Verifiable idempotency/deduplication; no blind replay of unknown writes; final business state matches operation records |
| Session, Turn and cancellation semantics | Concurrent messages, queues, stream disconnection, output rereads and cancellation/tool-completion races | Consistent admission, execution, termination and output state; disconnection is not cancellation; residual execution converges after cancellation |
| Tenant and resource isolation | Cross-tenant/Session access, credential/workspace isolation and accidental state/data reuse | Unauthorized access is rejected; data, tool permissions and credentials do not leak across boundaries |
| Authorization and human confirmation | Unauthorized Agent calls, unconfirmed writes and expired/revoked confirmation | Server-side enforcement independent of Harness compliance; auditable denial and confirmation |
| Budgets and accounting | Long tasks, auxiliary-model/subagent calls, failures, cancellation and missing usage | Defined limits apply; usage is complete and reconcilable; missing usage is exposed rather than recorded as zero |
| Capacity and resource governance | Mixed task lengths, tenant concurrency, queues, rate limits and slow dependencies | Measurable throughput, tail latency, saturation and resources; avoidance of sustained starvation and explicit backpressure/degradation |
| Observability, operations and upgrades | Cross-component diagnosis, upstream upgrades, state compatibility, rollback and new kernels | Correlated Session/Turn/tool evidence, attributable failures and acceptable upgrade/maintenance cost |

One business task can supply both sets of evidence, but the criteria remain separate. AppWorld business-goal completion belongs to Part One; duplicate actions and state consistency after killing a process during a write belong here. AgentDojo behavior also cannot substitute for tenant isolation or server-side authorization tests.

### 2.3 Evaluation methods and framework choices

Before capability experiments through ACS and the Session API, establish a minimum execution baseline with trustworthy inputs, tool results, budgets and outputs. Attribute environment anomalies to their responsible layers; capability scores support only conclusions explainable under the specified experimental conditions.

Use **platform-specific lane D**: deterministic model/tool fixtures first isolate protocol and state behavior from reasoning randomness; real models and representative business tasks then confirm the full service chain. Freeze releases, resources and dependencies while varying fault location, load, restarts and network conditions, with ordinary fault-free runs as controls.

| Method/component | Rationale | Evidence supplied |
| --- | --- | --- |
| Existing platform acceptance and fault-regression tools | Direct coverage of project Session, budget, lease, cancellation and component boundaries | Service semantics and recovery under repeatable faults |
| Existing `general-agent-eval` Runner and audit records | Same Session entry point as capability experiments, with reusable evidence correlation | Consistency of real business execution, state, usage and tool audit |
| ACS load and resource observation | Scheduling, storage, networking and quotas jointly determine capacity | Throughput, latency and resource boundaries under explicit workloads |

Public Agent datasets do not cover this project's distributed protocols or tenancy model. This part therefore relies on project contracts, fault matrices and acceptance cases rather than another generic capability leaderboard. Harbor or Inspect AI may supply workload/grading reuse but do not replace platform acceptance.

Run the same admission matrix for all three candidates. Distinguish shared service failures from candidate-specific adapter failures. Existing checks provide a starting point and must be repeated after relevant releases or adapter changes; they do not establish full fault and capacity coverage.

### 2.4 Forming platform acceptance conclusions

This part separately produces acceptance matrices, fault evidence, capacity boundaries and maintenance assessments. It does not produce a problem-solving capability score.

| Conclusion category | Criteria and use |
| --- | --- |
| Mandatory correctness gates | Defined authorization/isolation, acknowledged-state durability, duplicate-side-effect, cancellation and budget semantics must pass; unresolved critical issues block final admission |
| Operating conditions | State supported workloads, resources, verifiable accounting and monitoring/diagnosis capabilities; incomplete conditions require explicit deployment limits |
| Engineering selection evidence | Record adaptation scope, upstream upgrade/rollback difficulty, state compatibility and team maintenance cost, supporting decisions when capabilities are close |

Platform tail latency and throughput require comparable concurrency and resources; single-task duration from Part One is not a capacity conclusion. This part establishes trustworthy usage records, which Part One uses to compare task consumption. Complete accounting and efficient reasoning are different properties.

List passed requirements, repairs and untested areas separately, with affected candidates and responsible layers. Preserve original failures and validate repairs in new batches. The [implementation guide](harness-selection-evaluation-implementation.en.md) defines thresholds, measurements and evidence formats.

### 2.5 Approximate platform validation path

| Stage | Focus | Outcome |
| --- | --- | --- |
| Establish a minimum trustworthy baseline | Freeze deployment/adapter versions; confirm basic Session, tools, budgets and grading | Configurations eligible for formal capability experiments and blocking repairs |
| Complete correctness testing | State recovery, side effects, isolation, authorization, cancellation and accounting faults | Admission findings by candidate and defect attribution |
| Validate capacity and maintenance | Mixed tenant workloads, resources, upgrade/rollback and diagnosis exercises | Operating envelope, resource cost and long-term maintenance evidence |
| Produce platform findings and regressions | Preserve acceptance evidence, open issues and repeatable checks | Platform admission report, fault regressions and release requirements |

Building on the three integrated kernels, assume one evaluation engineer, one platform engineer and part-time infrastructure/business reviewers. Plan roughly 4–6 weeks for the initial round across both parts, with some work in parallel. New kernels, GUI environments or major defects require separate estimates; evidence-based exits define completion.

### 2.6 Using both conclusions for final selection

Present **capability findings** and **platform admission findings** side by side. Map Managed Agents workloads, product commitments and deployment constraints to existing Part One capability evidence here to judge suitability for actual use. This mapping does not expand Part One's objectives or change its capability weights. First establish necessary platform eligibility; then compare task effectiveness, task consumption and maintenance effort among eligible candidates.

| Part One capability finding | Part Two platform finding | Selection treatment |
| --- | --- | --- |
| Better results in important scenarios | Mandatory gates pass and operating conditions are explicit | Consider as default or scenario candidate, accounting for cost and migration effort |
| Better results in important scenarios | Unresolved critical platform defects | Retain capability evidence; repair and revalidate before deployment admission |
| No clear effectiveness advantage | Mature platform operation with acceptable maintenance cost | Retain the baseline or use for a scenario with explicit benefit |
| Insufficient critical capability or platform evidence | Untested areas or uncertainty | Fill evidence gaps without declaring overall superiority or readiness |

Replacing the baseline is not the only successful outcome. One default, scenario-based choices for new Sessions, and retaining the baseline when replacement benefits are unproven are all valid. Deliver both reports and regression evidence; the [second document](harness-selection-evaluation-implementation.en.md) contains implementation procedures.

Benchmark/framework sources are linked in Part One. Project evidence includes [earlier Harness research](../../docs/brain-harness-selection-research.en.md), [Hangzhou acceptance](../tests/general-agent-eval/reports/hangzhou-consistency-20261101.md) and the [business batch record](../tests/general-agent-eval/reports/formal-business-20261101-182203/README.en.md). Cite engineering estimates, measured task effectiveness and platform acceptance separately; none substitutes for the others.
