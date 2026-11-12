import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { setTimeout as sleep } from "node:timers/promises";
import { runWorker } from "../dist/worker.js";
import { RetrySchedule, classifyFailure } from "../dist/retry.js";
import {
  ServiceError,
  UnconfirmedResponse,
  canonicalHash,
} from "../dist/index.js";

const scope = { tenant: "t", namespace: "n", sessionId: "s" };
const fence = {
  owner: { instanceId: "i", incarnationId: "p" },
  epoch: "1",
  executionToken: "token",
};
const delivery = {
  scope,
  turnId: "turn",
  trigger: { kind: "ready", messageId: "ready", queueVersion: "1" },
};
async function until(predicate) {
  for (let i = 0; i < 500; i++) {
    if (await predicate()) return;
    await sleep(10);
  }
  assert.fail("Condition timed out");
}
async function fixture(t, { handler, run = async () => [], env = {} } = {}) {
  const calls = [],
    events = [],
    stop = new AbortController();
  const portServer = createServer();
  await new Promise((resolve) => portServer.listen(0, "127.0.0.1", resolve));
  const port = portServer.address().port;
  await new Promise((resolve) => portServer.close(resolve));
  let offered = false,
    revision = 0;
  const service = createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks));
    calls.push({ path: req.url, body });
    if (await handler?.(req, res, body)) return;
    let value;
    switch (req.url) {
      case "/executions/next":
        value = { deliveries: offered ? [] : [delivery] };
        offered = true;
        break;
      case "/executions/claim":
        value = {
          ...delivery,
          fence,
          expiresAt: new Date(Date.now() + 60000).toISOString(),
        };
        break;
      case "/executions/configuration":
        value = {
          protocolVersion: "0.1.0", scope, turnId: delivery.turnId,
          identity: {
            formatVersion: "managed-effective-agent-v1", harnessType: "pi",
            agentId: "agent", agentVersion: 1,
            sourceDigest: "0".repeat(64), effectiveDigest: "1".repeat(64),
          },
          configuration: {
            prompt: "fixture", cwd: "/workspace",
            model: { provider: "fixture", id: "test" },
            toolConfiguration: { scope },
            tools: [],
          },
        };
        value.identity.configurationDigest = canonicalHash(value);
        break;
      case "/recovery/read":
        value = {
          bundle: {
            brainRevision: "0",
            acceptedResults: [],
            toolOperations: [],
            resultApplications: [],
            inputApplications: [],
            pendingInputs: [],
            interactionUpdates: [],
            modelUpdates: [],
          },
        };
        break;
      case "/executions/renew":
        value = {
          serverTime: new Date().toISOString(),
          expiresAt: new Date(Date.now() + 60000).toISOString(),
        };
        break;
      case "/inputs/read":
        value = { pendingInputs: [] };
        break;
      case "/inputs/close":
        value = { throughSequence: "0" };
        break;
      case "/objects/write":
        value = {
          ref: {
            scope,
            backendId: "test",
            key: body.objectId,
            sha256: body.sha256,
            size: body.size,
            mediaType: body.mediaType,
            formatVersion: body.formatVersion,
          },
        };
        break;
      case "/commits":
        value = {
          commitId: body.commitId,
          contentHash: canonicalHash(body),
          outcome: "committed",
          brainRevision: String(++revision),
          nativeCursor: body.nativeState.nativeCursor,
        };
        break;
      case "/deliveries/defer":
        value = { outcome: "deferred" };
        break;
      default:
        assert.fail(req.url);
    }
    res
      .writeHead(200, { "content-type": "application/json" })
      .end(JSON.stringify(value));
  });
  await new Promise((resolve) => service.listen(0, "127.0.0.1", resolve));
  const running = runWorker(
    {
      type: "pi",
      stateFormat: "test-v1",
      run: async (host) => {
        host.useSnapshot(async () => ({}));
        return run(host);
      },
    },
    {
      signal: stop.signal,
      log: (e) => events.push(e),
      env: {
        SESSION_SERVICE_URL: `http://127.0.0.1:${service.address().port}`,
        MANAGED_AGENTS_INTERNAL_TOKEN: "fixture",
        PORT: String(port),
        BRAIN_POLL_MS: "10",
        BRAIN_DRAIN_MS: "100",
        BRAIN_ABORT_GRACE_MS: "30",
        BRAIN_RETRY_BASE_MS: "20",
        BRAIN_RETRY_MAX_MS: "60",
        ...env,
      },
    },
  );
  // Register rejection handling immediately; callers can still assert the exact error.
  running.catch(() => {});
  t.after(async () => {
    stop.abort();
    await running.catch(() => {});
    service.closeAllConnections();
    await new Promise((resolve) => service.close(resolve));
  });
  const probe = (path) => fetch(`http://127.0.0.1:${port}${path}`);
  return { calls, events, stop, running, probe };
}

test("retry schedule uses bounded jitter, cooldown after budget and resets on success", () => {
  let now = 0;
  const retry = new RetrySchedule(
    100,
    1000,
    3,
    () => 0,
    () => now,
  );
  assert.deepEqual(retry.failure("turn"), {
    attempts: 1,
    retryAfterMs: 50,
    exhausted: false,
  });
  assert.equal(retry.ready("turn"), false);
  now = 50;
  assert.equal(retry.ready("turn"), true);
  assert.equal(retry.failure("turn").retryAfterMs, 100);
  assert.deepEqual(retry.failure("turn"), {
    attempts: 3,
    retryAfterMs: 1000,
    exhausted: true,
  });
  retry.success("turn");
  assert.equal(retry.failure("turn").attempts, 1);
  assert.equal(
    classifyFailure(new ServiceError(409, "stale_execution")),
    "ownership",
  );
  assert.equal(
    classifyFailure(new ServiceError(401, "forbidden")),
    "configuration",
  );
  assert.equal(
    classifyFailure(new UnconfirmedResponse("lost ack")),
    "transient",
  );
});

test("transient execution failure defers the same delivery and fence; uncertain defer keeps its identity", async (t) => {
  let deferrals = 0;
  const f = await fixture(t, {
    run: async () => {
      throw new Error("temporary");
    },
    handler: (req, res) => {
      if (req.url !== "/deliveries/defer") return false;
      if (++deferrals === 1) res.writeHead(503).end("lost ack");
      else res.end("{}");
      return true;
    },
  });
  await until(() => deferrals === 2);
  const bodies = f.calls
    .filter((c) => c.path === "/deliveries/defer")
    .map((c) => c.body);
  assert.deepEqual(bodies[0], bodies[1]);
  assert.deepEqual(bodies[0].fence, fence);
  assert.deepEqual(bodies[0].trigger, delivery.trigger);
  assert.ok(!f.calls.some((c) => c.path === "/commits"));
  assert.ok(f.events.some((e) => e.event === "execution.retry"));
});

test("ownership conflict never defers another owner's work", async (t) => {
  const f = await fixture(t, {
    handler: (req, res) => {
      if (req.url !== "/executions/claim") return false;
      res.writeHead(409).end(JSON.stringify({ code: "execution_busy" }));
      return true;
    },
  });
  await until(() =>
    f.events.some((e) => e.event === "execution.ownership_lost"),
  );
  assert.ok(!f.calls.some((c) => c.path === "/deliveries/defer"));
});

test("unsealed configuration stops admission before recovery, native execution or model dispatch", async (t) => {
  let nativeRuns = 0;
  const f = await fixture(t, {
    run: async () => { nativeRuns++; return []; },
    handler: (req, res) => {
      if (req.url !== "/executions/configuration") return false;
      res.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify({
        configuration: { system: "private prompt", model: { provider: "fixture", id: "test" }, tools: [] },
      }));
      return true;
    },
  });
  await assert.rejects(f.running, { message: "Invalid effective configuration identity" });
  assert.equal(nativeRuns, 0);
  assert.ok(!f.calls.some((call) => ["/recovery/read", "/models/reserve", "/commits"].includes(call.path)));
  assert.ok(f.events.some((event) => event.event === "worker.configuration_error"));
  assert.ok(!JSON.stringify(f.events).includes("private prompt"));
});

for (const json of [false, true])
  test(`permanent HTTP 401 stops the worker (JSON=${json})`, async (t) => {
    const f = await fixture(t, {
      handler: (req, res) => {
        res
          .writeHead(401)
          .end(json ? '{"code":"unauthorized"}' : "Unauthorized");
        return true;
      },
    });
    await assert.rejects(f.running, { status: 401 });
    assert.equal(f.calls.length, 1);
  });

test("readiness requires a successful service poll and drops on outage", async (t) => {
  let release,
    outage = false;
  const first = new Promise((resolve) => (release = resolve));
  const f = await fixture(t, {
    handler: async (req, res) => {
      if (req.url !== "/executions/next") return false;
      await first;
      res.writeHead(outage ? 503 : 200).end(JSON.stringify({ deliveries: [] }));
      return true;
    },
  });
  await until(() => f.calls.length > 0);
  assert.equal((await f.probe("/readyz")).status, 503);
  release();
  await until(async () => (await f.probe("/readyz")).status === 200);
  outage = true;
  await until(async () => (await f.probe("/readyz")).status === 503);
  assert.equal((await f.probe("/healthz")).status, 200);
  assert.match(
    await (await f.probe("/metrics")).text(),
    /managed_brain_ready 0/,
  );
});

test("drain stops admission but lets the owned turn commit before its deadline", async (t) => {
  let host, release;
  const work = new Promise((resolve) => (release = resolve));
  const f = await fixture(t, {
    env: { BRAIN_DRAIN_MS: "2000" },
    run: async (h) => {
      host = h;
      await work;
      return [];
    },
  });
  await until(() => host);
  f.stop.abort();
  await until(async () => (await f.probe("/readyz")).status === 503);
  assert.equal(host.signal.aborted, false);
  release();
  await f.running;
  assert.equal(f.calls.filter((c) => c.path === "/executions/claim").length, 1);
  assert.equal(
    f.calls.filter((c) => c.path === "/commits").at(-1).body.changes.terminal
      .status,
    "succeeded",
  );
});

test("an uncooperative kernel cannot keep shutdown pending or commit after the deadline", async (t) => {
  let host;
  const f = await fixture(t, {
    run: async (h) => {
      host = h;
      await new Promise(() => {});
    },
  });
  await until(() => host);
  const started = Date.now();
  f.stop.abort();
  await assert.rejects(f.running, /shutdown timed out/);
  assert.ok(Date.now() - started < 1500);
  assert.equal(host.signal.aborted, true);
  await assert.rejects(host.boundary(), /deadline/);
  assert.ok(!f.calls.some((c) => c.path === "/commits"));
  host.close();
});
