# Managed Agents capacity tests with Alibaba Cloud PTS

Run from the repository root on its current branch. [中文](README_zh.md). The first measured run and its limitations are recorded in [the October 22 report](report-20261022.en.md).

## Workloads and acceptance

Each iteration creates a separate Session. `short` requests one real model response without tools;
`tool --seconds 5|30|90` invokes the real MCP fixture once; `fifo` queues T2 and injects into T1.
Use dedicated test Agents, a sandbox-free environment and a unique `load_test_batch`. Preserve unrelated
Sessions and the old unresolved Session. Start with a functional probe, then 1, 2, 5, 10 Session/s.
Review every stage after draining accepted work. Capacity stages need at least ten minutes; confirm a
candidate for 30–60 minutes. The real model's throughput is part of this deployed-system result.

HTTP admission success is separate from successful Session completion. Reconcile durable Turn states,
committed output, admission queue latency, end-to-end latency, active Session growth and tool execution
identity. Report observed peak concurrency separately from supported stable concurrency. Stop on business
failure above 0.1%, HTTP failure above 1%, missing fresh telemetry, or accepted work older than the stage
limit. The native runner implements those guards; do not continue increasing load after a failed gate.
Review restarts, FIFO violations, duplicate physical tool executions and outbox growth before promotion.

## Working PTS native engine

`native.py` creates a bounded open-loop PTS scene. PTS chains Session creation and Turn admission using
response exports. `--rps` is the target **Session arrival rate**: two APIs at 1 RPS each mean 1 Session/s,
not 2 Sessions/s. PTS native RPS mode requires at least 1 RPS per API. The runner saves scene parameters,
engine timeline, observer snapshots and report identity. Private scene files include the login token;
keep the output directory outside version control with restricted permissions.

For a VPC-only target, create the optional [temporary Gateway Service](private-gateway.yaml), adapting
the VSwitch and release labels to the target cluster. Supply the VPC, VSwitch and dedicated PTS security
group. Preserve the existing public Gateway's access list. VPC generator preparation can take several
minutes; preparing generators do not count as a measured stage. Outbound connectivity must allow PTS
control traffic and the target. Remove the temporary Service and security group after engines stop.

```bash
python3 managed-agents/tests/pts/native.py \
  --base-url http://YOUR_PRIVATE_GATEWAY --agent YOUR_TEST_AGENT --environment YOUR_ENVIRONMENT \
  --token-file /private/login.json --batch pts-short-unique --scenario short --rps 1 --minutes 10 \
  --vpc YOUR_VPC --vswitch YOUR_VSWITCH --security-group YOUR_PTS_GROUP \
  --observer-url http://127.0.0.1:19108 --out /private/unique-stage
python3 managed-agents/tests/pts/analyze.py --stage /private/unique-stage \
  --kubeconfig /private/kubeconfig --scenario short --rps 1
```

`analyze.py` queries read-only durable facts in bounded pages, retrieves the PTS final report using
SceneId + PlanId, and distinguishes failed terminal Sessions from still-active Sessions. PTS success
rates use the 0–1 scale. Native short scenarios check final committed text for PTS_OK, and also the Session ID when the prompt requests its echo.
A smoke run or missing observer coverage cannot certify capacity. Native FIFO injection occurs directly
after T2 admission; it is not guaranteed to land during the tool suspension. Use the JMeter scenario or
a dedicated functional probe to validate injection specifically while waiting for a tool.

## JMeter plan and local regression

`generate.py OUTPUT.jmx` embeds `session.groovy` into one self-contained JMeter 5.6.3 plan. It implements
closed-loop Session creation, admission, jittered three-second status polling, committed-output checks,
FIFO and injection checks. Its actual thread guard limits users to the `threads` property. A closed-loop
thread count is not a fixed Session arrival rate. `pts.py` supports importing/running this plan on PTS;
verify the imported FileList and actual samples before accepting an engine run.

On this account, PTS JMeter generators disappeared during allocation without producing samples or a
report, including a minimal native HTTP-only JMX. Native PTS over VPC worked and produced the measured
results. The generated Groovy plan passed official local JMeter regression and a six-Session real-service
functional run; neither is a substitute for a successful cloud JMeter capacity run.

PTS JMeter import required a directly accessible Shanghai OSS object URL in this run; signed URLs left
FileList empty. The JMX contains no credentials or target IDs. If object public-read is needed, apply it
to that single temporary object only, then restore private ACL. Do not modify bucket ACL. Use
`subresults.disable_renaming=true` to preserve separate admission/completion labels. Never publish scene
exports, JMeter properties, request logs or report snapshots containing authentication headers.

```bash
python3 managed-agents/tests/pts/generate.py /private/sessions.jmx
JMETER_HOME=/path/to/apache-jmeter-5.6.3 python3 -m unittest discover -s managed-agents/tests/pts -v
```

Install `psycopg[binary]==3.2.13` first. Without JMETER_HOME, executable Groovy regression is explicitly
skipped; the remaining tests still run.

## Monitoring deployment

`observer.py` publishes cached `/snapshot` JSON, `/metrics` Prometheus text and `/healthz` on ClusterIP
port 9108. It samples every 15 seconds. Use a read-only PostgreSQL account with connection and statement
timeouts, never an owner account. Grant CONNECT on the database, USAGE on ma and mcp_fixture, SELECT on
ma.turn, ma.session, ma.protocol_creation, ma.turn_resume, ma.tool_command_outbox,
ma.hands_tool_result_outbox, ma.hands_execution, ma.protocol_event, ma.public_event,
ma.hands_execution_event, ma.input, ma.protocol_output and mcp_fixture.execution, plus pg_read_all_stats.
Create `managed-agents-observer` Secret with `uri`, create `managed-agents-observer-source` ConfigMap
from observer.py, then apply observer.yaml. Set AGENT_IDS and SINCE_MS on the Deployment for test scope.
The image pin supplies Python and psycopg; it runs only the observer command. Update the source ConfigMap
and restart this Deployment when the observer changes.

Namespace RBAC permits only Pod and Pod-metric reads. Actuator metrics on Runtime, Agent Service and
Worker require the existing internal-service token; anonymous and ordinary JWT callers are rejected.
The observer uses the corresponding Secret key. No public Gateway metrics endpoint was introduced.
Prometheus scrape annotations are present; connecting an external monitoring backend is separate.

Series cover durable Turn counts, active Sessions, oldest Turn age, four outboxes, tool states,
queue/end-to-end percentiles, PostgreSQL connections/waits/deadlocks/transactions, Pod CPU/memory/restarts,
Hikari pool active/pending/max/timeouts/acquire and JVM/HTTP metrics. Failed collection is explicit;
never treat missing data as zero. Use status filters when testing oldest **nonterminal** Turn age.
`scope=all` includes preserved historical work; only `scope=test` controls load-test gates.

`record.py` additionally records lag API responses for all six RocketMQ consumer groups (including `ma_session_ready_v1`). In this LiteTopic
instance, parent-topic lag returned zero and last-consume-time zero even under real traffic. Those values
do not establish empty per-Session LiteTopic queues. Cross-check durable outboxes and oldest accepted age;
The recorder additionally samples readyCount for up to three oldest active Session LiteTopics; complete per-Session inflight visibility remains limited. The recorder creates no Session traffic.

## Active Turns and SSE acceptance

Primary load is `POST /v1/sessions/{id}/events` with `events[0].type=user.message` and
`managed_agents.intent=new_turn`. PTS names it `turn.admit`. Keep Session-create HTTP RPS, user.message
admission RPS/latency and successful Turn completions/s separate. `analysis.json` includes exact durable
peak active Turns, sampled nonterminal status/Turn trends, per-Pod load distributions and `load_timeline`.
Brain/Worker CPU, memory, JVM live/peak threads and active/pending Hikari connections are sampled;
older observer versions explicitly have no thread samples. Status gauges are test-Agent scoped and
stages must be drained between batches. Terminal status totals are not active Turns.

Run a paired SSE stage through `GET /v1/sessions/{id}/events/stream`, with the
same user.message load and image/resource configuration. The current Session API starts from head and
rejects Last-Event-ID/after, so verify its connected comment before admission. Measure actual concurrent
streams, connection errors/disconnects, first/terminal-event delay and completeness, together with
throughput, tails and per-Pod load. Do not claim replay verification for an API that does not support it.

The current acceptance scope is three ten-minute rounds at 10 Sessions/s: short fixed-marker requests,
one 30-second MCP tool per Session, then the same tool workload with SSE. Lower rates, additional
durations/FIFO pressure and long-duration confirmation are deferred by the latest user instruction.
`values-10sps.yaml` raises Worker execution concurrency to 256 per Pod: two replicas provide headroom
above the expected 300 simultaneous 30-second tools. Runtime concurrency is 256 per Pod, inline tool
waiting is one second, and model stream inactivity timeout is 60 seconds. The timeout uses the SDK
ExecutionConfig and does not limit tool duration; its production default remains disabled (0).
Durable cancellation uses AgentRun.cancel so an unresponsive model stream cannot delay it.
Keep all other resources constant between the
two tool rounds and snapshot the actual deployment; these settings are not capacity measurements.

For the paired tool rounds, deploy `sse-bridge.yaml` with the `sse_bridge.py`/`sse.py` source ConfigMap.
The temporary VPC-only preparation proxy forwards the real Session-create call. Pass its
`/baseline/v1/sessions` URL to `native.py --session-create-url` for the baseline, and its
`/sse/v1/sessions` URL for SSE. In SSE mode it returns the new Session ID only after `:connected`;
PTS still submits the actual user.message directly to the normal Gateway URL at 10/s. The subscriber
closes after the terminal and following idle frame, so concurrent listeners follow active business
work instead of keeping thousands of future Sessions open. The proxy is present in both tool arms;
Session-create latency includes subscription setup only in the SSE arm and is reported separately.
Use `--companion-url` for health checks and stop on missing telemetry, disconnects or duplicate IDs.
After draining, `audit_sse.py` compares every streamed event ID and canonical payload hash with the
durable protocol ledger from Turn acceptance onward. Keep its result alongside the business analysis.
Monitor the companion separately and remove its private SLB/Service/Deployment after saving evidence.
The pinned MCP fixture image supplies the Python dependencies; no application image change is needed.


An alternative for explicitly testing persistent idle listeners is the bounded `sse.py` client using
httpx 0.28.1. `prepare_sessions.py` creates a unique,
no-header Session-ID CSV outside the measured window. Use independent batches for paired runs.
`native.py --session-file-url URL --session-file-name sessions.csv` omits Session creation and consumes
that CSV once, so every measured PTS request is user.message (plus T2/inject in FIFO). Verify actual
PTS samples and CSV exhaustion before accepting this variant. File URLs must be readable by PTS;
keep authentication out of the CSV and restore the temporary object's private ACL afterward.

For the SSE arm, start `sse.py --base-url TARGET --sessions /private/sessions.csv --token-file
/private/login.json --out /private/sse --max-streams COUNT --duration SECONDS`, then require `ready.json`
with all streams open before starting PTS. Run the client near the target, sized and monitored separately.
Hold streams through the measured run and drain; record actual idle/active stream counts because holding
all precreated Sessions open adds persistent listeners. The no-SSE arm must use the same precreated
Session count, prompt, rate and image/resource configuration. Any EOF/error fails the companion;
there is no automatic reconnect masking event loss. Private event records include event IDs/types/timing
but no message content or authentication. Reconcile those IDs against protocol facts after draining.


For an offline sanitized chart, install `requirements-plot.txt`, then run `plot.py --stage /private/stage
--out /private/stage/load.png`. It shows sampled nonterminal work and each Brain/Worker Pod's CPU,
memory and DB-pool pressure. Exact durable peak concurrency is displayed separately from sampled peaks.
Charts contain no credentials, message text or Session IDs.


For the JMeter FIFO suspension/injection probe, use a 30-second tool (the default for FIFO when no
explicit duration is provided). A five-second tool may complete without a polled waiting_tool window;
that produces a no-window test failure without sending an injection. Distinguish this from an emitted
input that was lost/rejected or omitted by the model. The real post-release probe uses two parallel
30-second tools and verifies four successful FIFO Turns, two applied injections visible in output,
and exactly two physical MCP executions with their real call IDs.


## Controlled Model and fixed business concurrency

`values-concurrency-1000.yaml` explicitly enables loadtest:short-v1, loadtest:tool30-v1 and a cancellation-probe model.
The feature defaults off. Only Agents explicitly selecting a configured loadtest: name use it; other model choices retain their behavior.
The common runtime/loadtest/LoadTestModel.java implements the stream; Runtime LoadTestModels reads immutable operator profiles.
Profiles configure first-chunk delay, chunk interval/count/minimum size, tool name/argument template, rounds and parallel calls.
Use the literal `{{marker}}` for the per-request tool marker; it avoids Spring environment placeholder expansion.
Reactor timers do not block event loops; response delivery uses the SDK scheduler and honors execution timeout/cancellation.
Client messages use `LOADTEST {"request_id":"unique-request","marker":"unique-marker"}`.

Normal ToolUseBlock outputs traverse real MQ/Worker/MCP. Only original ToolResultBlocks supply tool evidence.
Recovery decisions use persisted messages; stable call IDs derive from model configuration, request identity, round and ordinal.
Auxiliary memory calls never dispatch business tools. Model chunks are not final SSE-event counts. Synthetic usage/metrics do not
represent provider billing, real Token throughput or external model HTTP formatting/transport costs.

`closed_loop.py` holds business slots until read-only SQL confirms terminal Turns, then creates replacement Sessions.
One asynchronous HTTP client and batched200ms scope queries avoid adding per-Session status-poll pressure.
Prepared Sessions are not in-flight work. Uncertain admission keeps the original identity/slot and stops replacement traffic.
Successful output is checked through the normal output API against marker and original MCP call_id; read-only SQL verifies physical executions.
The target is1000 logical clients. Durable nonterminal concurrency is slightly lower during completion detection/replacement;
report its actual distribution rather than equating threads with active Sessions.

`closed-loop.yaml` runs a dedicated in-cluster driver, independent of laptop connectivity. Its report is an in-cluster business
closed-loop measurement, not a native PTS RPS report. Supply BASE_URL/AGENT_ID/ENVIRONMENT_ID/BATCH/CONCURRENCY/HOLD_SECONDS
through the config ConfigMap and authentication through a separate Secret. Database access uses only the observer read-only account.
Results contain sanitized identities/counts/timing; an internal evidence container exports artifacts.
The main case ramps in5s, holds600s and drains. Guards cover new deadlocks/restarts, work age, missing telemetry and output errors.
Download evidence and remove temporary driver Pod/ConfigMap/Secret afterward. Use the existing Python/Java regression commands.

Analyze an exported run with `analyze_closed_loop.py --run RUN_DIR --facts FACTS_JSON --out analysis.json`.
It deduplicates observer timestamps, requires actual durable concurrency to remain at least 98% of target for 99% of unique samples across the entire established hold window,
and checks monitoring coverage, terminal/output/tool reconciliation, deadlock/restart and connection-pool timeout increments.
Use `plot.py --stage RUN_DIR --out load.png` after placing analysis.json in that directory. The plot includes synthetic stream/chunk activity.

The benchmark overlay raises Brain and Agent Service database pools to 64; ordinary defaults remain 16.
Keep 5s burst results separate from the 60s-ramp steady-concurrency run. Retry HTTP409 only when the extension error code is
`request_in_progress`, always with the original body/idempotency key. Logs retain HTTP status, error code, request ID and retry cost.
The default early-stop business failure threshold is 1% after 100 terminals; final acceptance remains 99.9% success.

The closed-loop Pod explicitly allows at most six original-key attempts (`--http-attempts`; the CLI default is three). Every unsuccessful HTTP attempt and retry remains recorded. This budget does not disable deadlock, age, output or monitoring guards. `background-errors.jsonl` retains stop causes; eventual HTTP 200 counts alone do not establish ingress quality.

[Controlled Model and 1000-Session closed-loop results](report-20261022-synthetic.en.md).

- [10000-client quick burst (256-connection pools, failed)](report-20261022-10000-quick.en.md)

Use `--session-queue` to create `db_queue_v1` Sessions with `session-queue-v1`; the driver checks the returned mode. The flag defaults off. Apply the queue migration and grant the observer SELECT on `ma.session_queue` and `ma.session_dispatch` before recording queue gauges. Hold validation follows the configured `hold` duration, including 300-second runs; resource and telemetry guards still apply.

[DB queue deployment and 10,000-concurrency test](report-20261022-10000-session-queue.en.md).

### Direct commit and object-store acceptance

For the new architecture, use `closed_loop.py --direct-store --concurrency 10000 --ramp 60 --hold 300 --tool`. Session receipts must report `db_queue_v1` / `direct`. Session Store is embedded in Brain and Agent Service consumes projections; no standalone Session Store endpoint, token, or metrics port is needed. The observer reads `projection_source_head` / `projection_progress` and measures lag, blocked projections, object backend requests, caching, and capacity admission. Configure matching backend IDs, buckets, and fresh data spaces across all readers/writers, and capture actual MQ topic overrides. Historical reports retain their original configurations to explain measurements of those versions.

The canonical durable-concurrency sample can run in a separate process with `durable_sampler.py --batch BATCH --since-ms START --out /results/durable-independent.jsonl --summary /results/run/summary.json`. It uses a read-only SQL connection and a 0.5-second interval; its scoped count does not depend on the HTTP driver's event loop or output parsing. Pass the exported file through `analyze_closed_loop.py --durable-samples FILE`. Query failures, intervals longer than one second, insufficient concurrency, or incomplete final facts still fail acceptance. Keep the driver's original samples as supporting evidence and report any changes to database size or sampler placement between runs.

[Object-backed Session Store: two-replica deployment and failed 10,000-inflight acceptance](report-20261023-10000-object-store.en.md).

P2 reports identify the stricter acceptance rules as `data-plane-p2-v1`: no cropped hold window, no duplicate sample weighting, and no unexpected failed/cancelled Turn. Historical reports retain their original rules and are not retroactively relabeled.

The current 10,000-concurrency overlay, `values-publication-10000.yaml`, sets Brain `concurrency: 0`, disabling execution-fragment admission and the fixed execution-worker ceiling. The receive/ACK window remains `resumeHandoffCapacity: 64`, inline wait is one second, Brain uses four replicas, and other business components remain at two. This requires an image supporting zero; editing the local overlay does not update the cluster. `admission.execution_limit=0` and `execution_available=-1` identify unlimited mode; observe `execution_active` and actual resource pressure.

See the [R9 load-test report](report-20261026-data-plane-r9-brain4.en.md) for the deployed four-replica results: continuation latency improved, overall throughput regressed, and one initial notification required external wakeup, leaving acceptance incomplete.
