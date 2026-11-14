---
title: "Navigate authorized pages and verify completion"
zh_link: /dev/zh/docs/harness/jev/guides/browser-execution-api
---

`JevBrowserNavigator` exposes a `read_browser` tool that can choose navigation, scrolling or waiting from currently visible candidates. Use it to look up information in a controlled help center or policy site. The host defines the authorized pages and read-only operations; an independent verifier must confirm completion.

## Example: find the warranty period

The user asks how long the warranty lasts. The help center shows only a “Warranty policy” link. The Agent must visit the policy page and find the period before answering. A model selecting DONE is not proof.

The host authorizes the home and policy pages. JEV recommends a visible action, and the adapter rechecks the page version before dispatch. On the policy page, an independent verifier checks the actual text before returning VERIFIED.

## 1. Register the read-only browser tool

Initialize `client` as in [Client setup](/dev/en/docs/harness/jev/guides/client); `model` generates the answer. Start in SHADOW: inspect the recommendation without clicking or leaving the initial page.

```java
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.browser.JevBrowserNavigator;
import io.agentscope.extensions.judge.jev.browser.JevBrowserReadTool;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.*;
import io.agentscope.extensions.judge.jev.browser.JevPlaywrightSession;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.nio.file.Path;
import java.util.Set;

var navigator = new JevBrowserNavigator(
    client::systemOne, 0.8, JevBrowserNavigator.Limits.defaults(),
    new JevExecution.Options(JevExecution.Mode.SHADOW,
        Duration.ofSeconds(10), "browser-v1",
        (current, record) -> System.out.println(record)));
var toolkit = new Toolkit();
toolkit.registerTool(new JevBrowserReadTool(navigator));
var agent = HarnessAgent.builder().name("help-center-reader")
    .model(model).toolkit(toolkit).build();
```

Calibrate the 0.8 selection threshold. The ten-second budget covers observation, judgment, action and verification for the entire tool call. The default eight-step limit prevents loops. The normal permission chain still applies. The model supplies only the goal, never an arbitrary URL, script or selector.

## 2. Provide a session factory and completion verifier

`openSession` is a host-supplied `Function<JevBrowserSession.Request, JevBrowserSession>` that returns an authorized exclusive session per call. A `JevBrowserSession.Verifier` checks whether the current page satisfies the goal. See the runnable example below for both implementations.

```java
var context = RuntimeContext.builder().userId(userId).sessionId(sessionId)
    .put(Source.class, new Source(openSession, verifier))
    .build();
```

Pass this context to the Agent. In the warranty example the verifier checks the actual policy page for the requested period and binds its result to the current page version and goal digest. A model's DONE recommendation cannot substitute for verification.

For actual navigation, change the options to ENFORCE and rebuild the component. Only `VERIFIED` indicates successful independent verification. `OBSERVED` means a page was read; `UNVERIFIED` means completion evidence is missing. If the page changes, the adapter must return STALE before observing again rather than executing an old proposal.

## 3. Implement the session boundary

Source.open must be a nonblocking factory returning a new exclusive session for every call. Do startup and I/O in reactive session methods. Resolve user ACLs and authorized pages before supplying the Source; model arguments do not confer authorization. session.scope must match the runtime context; every Snapshot.scope must match the session's user, session and tab. Do not share browser sessions across calls.

Session contract:

- `observe()` returns an immutable Snapshot: scope, page version, URL, title, visible text, links with stable IDs, scroll state and truncation counts.
- `execute(permit, expected, action)` consumes a single-use Permit, atomically rechecks the page and target, then dispatches. STALE guarantees no dispatch. If dispatch occurred but the outcome is unknown, return UNKNOWN; do not retry as STALE.
- `close()` immediately prevents further dispatch and returns a Mono for asynchronous cleanup. Make it idempotent; clean up on cancellation and error too.
- Source.verifier independently returns Verification for the current page and business goal, binding goalDigest, pageVersion, passed and evidence. Never use JEV's DONE as its own proof.

Call `navigator.navigate(context, goal, source)` directly for a full Report with the final page, each originating page version, proposals, action receipts and independent verification. It contains page text; redact before storing it. The Agent tool returns only status, url, version, visibleText and evidence, keeping shadow proposals and internal traces out of subsequent model calls.

## From recommendation to execution

A request contains an operation Choice and an optional click_target Choice. Shared state includes all authorized visible candidates so both questions can see the targets. Offer only available operations; CLICK targets are authorized visible links plus none. Keep none even for one link because Choice requires at least two options. The limit is 128 links. Typing, selection, forms, uploads, downloads and purchases are unsupported.

JevClient validates both answers before the selected option probabilities are compared with explicit thresholds. Low confidence or CLICK/none stops enforcement. Only a target matching the operation can execute. DONE and BLOCKED trigger another page-version check; DONE also requires independent verification and a final page recheck. VERIFIED requires correct binding, a pass and evidence.

Record dispatch intent first, then the receipt, then observe the next page. Keep an APPLIED receipt if later observation fails. Dispatch errors, empty receipts or cancellation in flight leave UNKNOWN; never replay automatically. STALE allows a fresh observation and judgment, with two retries by default. Three consecutive non-WAIT actions without a version change stop the loop. WAIT consumes the step and time budgets.

| Mode | Actual browser behavior | Tool output |
| --- | --- | --- |
| OFF | Read only the host's initial page; no JEV call | OBSERVED and the original page |
| SHADOW | Read the same page and judge one proposal; no action or completion check | Same as OFF; recommendation stays in the Report/observer |
| ENFORCE | Run a bounded loop over authorized candidates subject to guards | VERIFIED, UNVERIFIED, BLOCKED, ABSTAIN or a specific stop reason |

UNVERIFIED, OBSERVED, UNKNOWN, TIMEOUT, STEP_LIMIT, STALE_LIMIT, NO_PROGRESS and errors do not establish completion. The tool does not generate the final answer. The Agent answers from tool evidence and can apply response review afterward.

## Budgets, cancellation and telemetry

Default limits: 8 decision steps, 2 stale retries, 3 steps without progress, 32,000 serialized state characters and an 8,000-character goal. All judgments, page operations and verification share one tool-call budget. Oversized state stops processing rather than treating truncated state as complete. The adapter provides viewport text and explicitly reports omitted candidates.

Cancellation stops the subscription and further dispatch and closes the exclusive session. An already-dispatched action may have an unknown outcome; cancellation cannot undo it. The adapter listens for closure and rejects calls immediately after close instead of queuing them on a stopped worker. Observer failures do not change the tool result.

JevExecution records version, mode, status, added judgment latency, proposed operation and known model/token usage for `browser_action`. `browser_task` records total duration, step/receipt counts and stop reason. Metadata omits URLs, full pages, goals, authorized catalogs and credentials. Unknown prices produce no monetary estimate.

## Playwright adapter

`JevPlaywrightSession` uses the optional `com.microsoft.playwright:playwright:1.45.0` dependency. Consumers using only the Session interface and offline examples do not need it. For a real driver, add the dependency and supply an absolute browser path. The example neither installs a browser nor uses a personal profile.

```java
var session = new JevPlaywrightSession(
    new Scope(userId, sessionId, tabId), Path.of(chromeExecutable),
    authorizedStartUrl, Set.of(authorizedStartUrl, authorizedPolicyUrl),
    Duration.ofSeconds(10));
```

Each session creates an isolated headless context. All Playwright calls run on one dedicated thread to satisfy its [threading requirements](https://playwright.dev/java/docs/multithreading). Service workers and downloads are disabled. Only GET requests to host-enumerated URLs are allowed; URLs containing credentials are rejected. The host must still verify that GET requests and page scripts are read-only in business terms.

Atomic DOM snapshots keep node identities in a JSHandle closure inaccessible to model-supplied selectors or scripts. Enumerate only authorized, enabled, non-form links visible in the viewport, excluding hidden/inert/aria-hidden links, downloads, new windows and inline click handlers. Before clicking or scrolling, the fixed script recomputes page and form guards, resolves the current node and checks for occlusion. Recalculate moved coordinates; never reuse model-generated coordinates.

The page version includes document timing identity, URL, viewport, visible text, candidate identities and safe form properties. Form properties are local guards and never enter model state; password, hidden and file fields are excluded. Guards cover the visible page. Recreate the snapshot controller after navigation and retain UNKNOWN on ambiguous dispatch. Cross-frame navigation, shadow DOM, pop-ups and general interactive sites are unsupported.

## Runnable example

[JevBrowserExample](/examples/jev/source/JevBrowserExample.java.txt) supplies a scripted session and independent verifier. Set `JEV_CP` using the [build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples), then run:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevBrowserExample
```

No credentials, browser or network are required by default. A real ReActAgent calls read_browser, the scripted browser navigates once, and the Agent answers after independent verification. Expect judgments=2, modelCalls=2, browserActions=1 and closedSessions=1. Scripted decisions verify the execution path, not semantic accuracy.

To use a real browser without contacting external models or websites:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevBrowserLocalExample \
  --browser "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
```

Append `--live-jev MODEL` or `--live-qwen MODEL ENDPOINT` and supply the corresponding environment credentials for live judgments. See [JevBrowserLocalExample](/examples/jev/source/JevBrowserLocalExample.java.txt) for full source and [Evaluate your Harness](/dev/en/docs/harness/jev/evaluation) for fixed action comparisons.

The example starts a loopback HTTP site, opens the home page in a fresh browser context, clicks the warranty policy, checks the actual text and closes the page and server. Supply your own browser executable path.
