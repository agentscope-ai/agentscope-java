# Managed Agents Java client

Managed Session Draft 3 uses one `/v1/sessions/{id}/events` interaction endpoint.
Use send_events/sendEvents, submit_turn/submitTurn, steer, inject and cancel helpers. Read final output
independently through output/final_output/finalOutput. Listing, lifecycle, snapshots, files, tool replies
and reliable resume are not enabled in the first release; consult capabilities before optional APIs.
Source protocol and pinned SDK verification: `managed-agents/tests/session-protocol/`.


Java 17 client for the public Agent Service API. The module depends on Jackson;
it does not depend on Spring, Harness, or the Service server modules. Maven
coordinates are `io.agentcore.managed:managed-agents-client`; select the Java revision
in the Service release manifest. Local artifacts and release packaging do not
confirm publication to a Maven repository.

Build and install from the repository root:

```bash
mvn -T1 -pl managed-agents/java-client -am install
```

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.client.ServiceClient;

var mapper = new ObjectMapper();
var client = ServiceClient.builder(System.getenv("BASE_URL"))
        .apiKey(System.getenv("APPLICATION_API_KEY"))
        .tenant("my-tenant")
        .namespace("my-namespace")
        .build().managedSessions();
var definition = mapper.createObjectNode().put("agent", "agent-id")
        .put("environment_id", "environment-id");
var session = client.create(definition, "create-order-123");
var sessionId = session.path("id").asText();
var turn = client.submitMessage(sessionId, "Summarize this order", "turn-order-123");
var turnId = turn.path("id").asText();
try (var events = client.stream(sessionId, "")) {
    for (var event = events.nextEvent(); event != null; event = events.nextEvent()) {
        // Apply the event, then persist event.path("cursor").asText().
        if (event.path("type").asText().equals("turn.completed")
                && event.path("data").path("turn_id").asText().equals(turnId)) break;
    }
}
```

For an authorized human caller use `apiToken(...)` instead of `apiKey(...)`.
For local development without authentication, omit both. The client sends the
original identity and rejects HTTP redirects. It never accepts a Service internal
token. Calls block: run them on application threads, never reactive event-loop
threads.

`managedSessions()` returns the native `/v1/sessions` client. It creates pinned definitions,
wraps Agent input in the Turn envelope, and submits `steer/inject/reply/cancel` through
`input(session, turn, type, payload, actionId, key)`. Read `inputReceipt` for accepted,
applied or rejected status; cancellation acceptance does not confirm a stopped Turn.
Keep explicit keys for `lifecycle(session, kind, key)` and poll `lifecycleReceipt` until
cleanup completes. SSE covers the Session and validates each event's Session ID.
Files, explicit Artifact publication, budget, usage and pinned resources are available;
Fork and checkpoint restoration remain implementation work. Turn snapshot pages use
the Session `resources` route with the same snapshot ID. Native `usage` retains `data/as_of`.

The flat `ServiceClient` API below remains for other execution families. Its Session,
command and export contracts are distinct from the native Managed protocol; no automatic
fallback or compatibility translation is performed.

Session/Turn reads, keyset lists, capabilities, snapshots, resource pages, event
pages and SSE, action answers, steer, inject, cancel, resume, tool/workspace
recovery receipts, lifecycle receipts, usage, files, artifacts and Session
Webhooks use the current Agent Service routes. Inspect capabilities before file input.
`budget(session)` reads desired and applied settings plus indexed usage.
`setBudget(session, limits, revision)` returns accepted settings; pass the current
revision to prevent lost updates (`412`). Limits apply before the next model call.
`restoreCheckpoint(...)` and `fork(...)` return a durable 202 command; poll `command(...)` for completed/rejected. Both roots require interact permission, settled Turns and inputs. Admission is paused during bounded validation, and journal commit witnesses make retries safe.

`checkpoints(session, after, through, limit)` lists metadata through Agent Service.
Keep `as_of` as `through`, and advance using `next_cursor` while `has_more` is true,
including empty pages. Raw recovery state is never returned.

`exportEvents(session, turn, after, through, limit)` streams a finite public NDJSON
window (limit 1..1000). Pass a snapshot's `as_of` as `through` to pin the history.
Read `nextEvent()` until it returns null, then use `nextCursor()` with the same
`throughCursor()` if `hasMore()` is true. Close each window with try-with-resources.
On failure, resume from the last applied event cursor; do not advance to the window's
next cursor. Truncated records and unexpected record counts fail explicitly.

Keep `snapshot_id` while paging a snapshot, then reconnect SSE from its `as_of`
cursor. A command receipt is checked against the requested Turn. Network retries
are explicit; reuse the same idempotency key and input. Archive/delete return an
accepted lifecycle operation; query `lifecycleCommand(...)` for its receipt.

Each request, including its response body or SSE connection, has a total deadline
(default 30 seconds). Close streams when consumption stops; reconnect from the
last applied cursor after timeout or EOF. Limits are 16 MiB for JSON and uploads,
1 MiB for errors and individual SSE events, and 100 MiB for buffered file/artifact
downloads. Streaming large downloads and a broader client failure matrix remain
in the test/implementation backlog.

Representative real Agent Service/PostgreSQL fixture:

```bash
mvn -T1 -pl managed-agents/java-client -am package -DskipTests
mvn -T1 -pl managed-agents/java-client dependency:build-classpath \
  -Dmdep.outputFile=target/client-classpath.txt
```

Then run `tests/orchestration_runtime_team_smoke.py` with a dedicated loopback
PostgreSQL schema as documented in that fixture. Build Agent Service, Go binaries and
the DSH SDK first. The fixture uses a local configuration source and fenced fake
Host, and stops its seed control plane before execution; it does not call a model
provider.

`trace(session, after, through, limit)` reads privileged native history when the
server enables it and the caller has a current human trace grant. Use its
`next_seq` with the same `as_of_seq` watermark. Application keys cannot use it.
