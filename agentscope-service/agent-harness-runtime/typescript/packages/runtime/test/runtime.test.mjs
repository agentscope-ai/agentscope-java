import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import {
  SessionClient,
  RunHost,
  canonicalHash,
  taskIdentity,
  ServiceError,
  UnconfirmedResponse,
  assistantMessageFact,
} from "../dist/index.js";
const scope = { tenant: "t", namespace: "n", sessionId: "s" };
const fence = {
  owner: { instanceId: "i", incarnationId: "p" },
  epoch: "1",
  executionToken: "token",
};
const recovery = {
  brainRevision: "0",
  acceptedResults: [],
  toolOperations: [],
  resultApplications: [],
  inputApplications: [],
  pendingInputs: [],
  interactionUpdates: [],
  modelUpdates: [],
};

test("PI accounting includes cache input and preserves missing or invalid usage as unknown", async () => {
  const client = { put: async () => ({ key: "model-result" }) };
  const host = new RunHost(client, scope, "turn", fence, recovery, "pi", "v1", new Date(Date.now() + 60_000).toISOString());
  const known = await host.modelCompletion("model", {}, { input: 10, output: 5, cacheRead: 7, cacheWrite: 3 });
  assert.deepEqual(known.usage, { inputTokens: "20", outputTokens: "5", cacheReadInputTokens: "7", cacheWriteInputTokens: "3" });
  for (const usage of [undefined, { input: -1, output: 3 }, { input: Number.MAX_SAFE_INTEGER, output: 1, cacheRead: 1 }]) {
    const unknown = await host.modelCompletion("model", {}, usage);
    assert.equal(unknown.usageUnknown, true);
    assert.equal(unknown.usage, undefined);
  }
});

test("large public messages and final output use scoped objects without enlarging commit metadata", async () => {
  const objects = new Map();
  const commits = [];
  const client = {
    async put(scope, bytes, formatVersion) {
      const key = `${formatVersion}-${objects.size}`;
      objects.set(key, JSON.parse(bytes.toString()));
      return { scope, key, formatVersion };
    },
    async commit(request) {
      commits.push(request);
      return { brainRevision: String(commits.length) };
    },
    async post() { return { pendingInputs: [] }; },
  };
  const host = new RunHost(client, scope, "turn", fence, recovery, "pi", "v1", new Date(Date.now() + 60_000).toISOString());
  host.useSnapshot(async () => ({ native: true }));
  const text = "x".repeat(300_000);
  const fact = assistantMessageFact("turn", "message", { content: [{ type: "text", text }] });
  await host.boundary({}, undefined, [fact]);
  host.outputMessageId = "message";
  await host.finish([{ type: "text", text }]);
  assert.deepEqual(objects.get(commits[0].publicFacts[0].content.ref.key), fact.content.inline);
  assert.deepEqual(objects.get(commits[1].changes.terminal.output.ref.key), [{ type: "text", text }]);
  assert.equal(commits[1].changes.terminal.messageId, "message");
  assert.ok(commits.every((commit) => Buffer.byteLength(JSON.stringify(commit)) < 16_384));
});

for (const release of ["terminal", "suspension"]) {
  test(`${release} receipt survives an in-flight input poll observing released ownership`, async (t) => {
    t.mock.timers.enable({ apis: ["setInterval"] });
    let failPoll, pollStarted, receipt, queries = 0;
    const pendingPoll = new Promise((resolve) => { pollStarted = resolve; });
    const client = new SessionClient("http://localhost", "fixture");
    client.put = async () => ({
      scope, backendId: "objects", key: "snapshot", sha256: "a".repeat(64),
      size: "2", mediaType: "application/json", formatVersion: "v1",
    });
    let reads = 0;
    client.post = async (operation, body, signal) => {
      signal?.throwIfAborted();
      if (operation === "executions/renew") return {
        serverTime: new Date().toISOString(),
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
      };
      if (operation === "inputs/read") {
        if (++reads === 1) return { pendingInputs: [] };
        pollStarted();
        return new Promise((_resolve, reject) => { failPoll = reject; });
      }
      if (operation === "commits") {
        receipt = { commitId: body.commitId, contentHash: canonicalHash(body),
          brainRevision: "1", nativeCursor: "1", outcome: "committed" };
        // The service has durably released the fence, but the commit response is lost.
        failPoll(new ServiceError(409, "execution_fenced"));
        await new Promise((resolve) => setImmediate(resolve));
        throw new UnconfirmedResponse("commit acknowledgement lost");
      }
      assert.equal(operation, "commits/receipt");
      queries++;
      return receipt;
    };
    const host = new RunHost(client, scope, "turn", fence, recovery, "pi", "v1",
      new Date(Date.now() + 60_000).toISOString());
    host.useSnapshot(async () => ({ messages: [] }));
    try {
      await host.startRenewal();
      t.mock.timers.tick(1000);
      await pendingPoll;
      const result = host.boundary(release === "terminal"
        ? { terminal: { status: "succeeded", output: { inline: [] } } }
        : { suspension: { suspensionId: "suspend", pendingTaskIds: ["task"],
            pendingActionIds: [], resumePhase: "WAIT_TOOLS", waitPolicy: "all" } });
      if (release === "terminal") await result;
      else await assert.rejects(result, /durably suspended/);
      assert.equal(queries, 1, "released ownership must not abort receipt recovery");
      assert.equal(host.isSuspended, release === "suspension");
    } finally { host.close(); }
  });
}

test("lost commit response queries the original durable receipt without reauthorization", async () => {
  let receipt,
    commits = 0,
    queries = 0;
  const server = createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks));
    assert.equal(req.headers.authorization, "Bearer fixture");
    if (req.url === "/commits") {
      commits++;
      receipt = {
        commitId: body.commitId,
        contentHash: canonicalHash(body),
        brainRevision: "1",
        nativeCursor: "1",
        outcome: "committed",
      };
      req.socket.destroy();
      return;
    }
    assert.equal(req.url, "/commits/receipt");
    assert.equal(body.commitId, "commit");
    queries++;
    res
      .writeHead(200, { "content-type": "application/json" })
      .end(JSON.stringify(receipt));
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    const client = new SessionClient(
      `http://127.0.0.1:${server.address().port}`,
      "fixture",
    );
    const actual = await client.commit({
      scope,
      turnId: "turn",
      commitId: "commit",
      changes: {},
    });
    assert.deepEqual(actual, receipt);
    assert.equal(commits, 1);
    assert.equal(queries, 1);
  } finally {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  }
});

test("concurrent native callbacks reuse one authorization and one accepted Hands result", async () => {
  let commits = 0,
    queries = 0;
  const ref = {
    scope,
    backendId: "objects",
    key: "k",
    sha256: "a".repeat(64),
    size: "2",
    mediaType: "application/json",
    formatVersion: "v1",
  };
  const task = taskIdentity(scope, "turn", "call");
  const result = {
    resultId: "r",
    taskId: task,
    executionId: task,
    status: "succeeded",
    content: { inline: { answer: 42 } },
    artifacts: [],
    output: { mode: "none", throughSourceSeq: "1", byteLength: "0" },
  };
  const client = {
    put: async () => ref,
    commit: async (request) => {
      commits++;
      return {
        commitId: request.commitId,
        contentHash: canonicalHash(request),
        outcome: "committed",
        brainRevision: String(commits),
        nativeCursor: String(commits),
      };
    },
    post: async (operation) => {
      assert.equal(operation, "tools/state");
      queries++;
      return { state: "succeeded", result };
    },
  };
  const host = new RunHost(
    client,
    scope,
    "turn",
    fence,
    recovery,
    "pi",
    "v1",
    new Date(Date.now() + 60_000).toISOString(),
  );
  host.useSnapshot(async () => ({ messages: ["call"] }));
  const [one, two] = await Promise.all([
    host.executeTool("call", "sandbox.exec", { command: "echo ok" }, ref),
    host.executeTool("call", "sandbox.exec", { command: "echo ok" }, ref),
  ]);
  assert.deepEqual(one, two);
  assert.equal(commits, 1);
  assert.equal(queries, 1);
  await assert.rejects(
    host.executeTool("call", "sandbox.exec", { command: "changed" }, ref),
    /identity changed/,
  );
  host.resultApplied("call", one);
  await host.boundary();
  assert.equal(commits, 2);
  host.close();
});

test("renewal uses the service clock even when the claim timestamp is far behind this host", async () => {
  const client = {
    post: async (operation) => {
      if (operation === "inputs/read") return { pendingInputs: [] };
      assert.equal(operation, "executions/renew");
      return {
        serverTime: "2000-01-01T00:00:00Z",
        expiresAt: "2000-01-01T00:01:00Z",
      };
    },
  };
  const host = new RunHost(
    client,
    scope,
    "turn",
    fence,
    recovery,
    "pi",
    "v1",
    "2000-01-01T00:01:00Z",
  );
  try {
    await host.startRenewal();
    assert.doesNotThrow(() => host.check());
  } finally {
    host.close();
  }
});

for (const status of [408, 429, 500, 503]) {
  test(`HTTP ${status} resolves the original receipt before retrying authorization`, async () => {
    let receipt;
    const paths = [];
    const server = createServer(async (req, res) => {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const body = JSON.parse(Buffer.concat(chunks));
      paths.push(req.url);
      if (req.url === "/commits") {
        receipt = {
          commitId: body.commitId,
          contentHash: canonicalHash(body),
          brainRevision: "1",
          nativeCursor: "1",
          outcome: "committed",
        };
        res
          .writeHead(status, { "content-type": "application/json" })
          .end(JSON.stringify({ code: "unavailable" }));
      } else {
        assert.equal(body.commitId, receipt.commitId);
        res
          .writeHead(200, { "content-type": "application/json" })
          .end(JSON.stringify(receipt));
      }
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    try {
      const client = new SessionClient(
        `http://127.0.0.1:${server.address().port}`,
        "fixture",
      );
      assert.deepEqual(
        await client.commit({ scope, turnId: "turn", commitId: "original" }),
        receipt,
      );
      assert.deepEqual(paths, ["/commits", "/commits/receipt"]);
    } finally {
      server.closeAllConnections();
      await new Promise((resolve) => server.close(resolve));
    }
  });
}

test("a missing uncertain receipt retries the identical commit, but a policy rejection does not", async () => {
  const client = new SessionClient("http://unused.invalid", "fixture");
  const { UnconfirmedResponse, ServiceError } = await import(
    "../dist/index.js"
  );
  const request = { scope, turnId: "turn", commitId: "same-identity" };
  const calls = [];
  client.post = async (path, body) => {
    calls.push({ path, body });
    if (calls.length === 1) throw new UnconfirmedResponse("uncertain");
    if (path === "commits/receipt")
      throw new ServiceError(404, "receipt_not_found");
    return {
      commitId: body.commitId,
      contentHash: canonicalHash(body),
      outcome: "committed",
      brainRevision: "1",
      nativeCursor: "1",
    };
  };
  await client.commit(request);
  assert.equal(calls[0].body, calls[2].body);
  client.post = async () => {
    throw new ServiceError(403, "forbidden");
  };
  await assert.rejects(client.commit(request), { code: "forbidden" });
});

test("cancel interrupts a Hands wait without aborting the settlement channel", async () => {
  let cancel = false;
  const ref = { scope };
  const client = {
    put: async () => ref,
    commit: async () => ({ brainRevision: "1" }),
    post: async (operation) => {
      if (operation === "inputs/read")
        return {
          pendingInputs: cancel ? [{ inputId: "c", type: "cancel" }] : [],
        };
      assert.equal(operation, "tools/state");
      return { state: "running" };
    },
  };
  const host = new RunHost(
    client,
    scope,
    "turn",
    fence,
    recovery,
    "pi",
    "v1",
    new Date(Date.now() + 60000).toISOString(),
  );
  host.useSnapshot(async () => ({}));
  const waiting = host.executeTool("call", "sandbox.exec", {}, ref);
  const rejected = assert.rejects(waiting, /cancelled/);
  cancel = true;
  await host.inputs();
  await rejected;
  assert.equal(host.signal.aborted, false);
  assert.equal(host.workSignal.aborted, true);
  await host.boundary({
    inputApplications: [{ inputId: "c", status: "applied" }],
  });
  host.close();
});

test("suspension receipt freezes the native commit lane and never emits a replacement tool operation", async () => {
  const commits = [];
  const ref = { scope };
  const client = {
    put: async () => ref,
    commit: async (request) => {
      commits.push(request);
      return { brainRevision: String(commits.length) };
    },
    post: async () => ({ state: "unknown" }),
  };
  const host = new RunHost(
    client,
    scope,
    "turn",
    fence,
    recovery,
    "pi",
    "v1",
    new Date(Date.now() + 60000).toISOString(),
    0,
  );
  host.useSnapshot(async () => ({ originalCall: "call" }));
  await assert.rejects(
    host.executeTool("call", "sandbox.exec", {}, ref),
    /durably suspended/,
  );
  assert.equal(host.isSuspended, true);
  assert.equal(commits.length, 2);
  assert.deepEqual(commits[1].changes.suspension.pendingTaskIds, [
    taskIdentity(scope, "turn", "call"),
  ]);
  await assert.rejects(host.boundary(), /durably suspended/);
  assert.equal(commits.length, 2);
  host.close();
});

test("a rejected suspension never claims successful handoff", async () => {
  const ref = { scope };
  const client = {
    put: async () => ref,
    commit: async (request) => {
      if (request.changes.suspension) throw new Error("lost owner");
      return { brainRevision: "1" };
    },
    post: async () => ({ state: "running" }),
  };
  const host = new RunHost(
    client,
    scope,
    "turn",
    fence,
    recovery,
    "pi",
    "v1",
    new Date(Date.now() + 60000).toISOString(),
    0,
  );
  host.useSnapshot(async () => ({}));
  await assert.rejects(
    host.executeTool("call", "sandbox.exec", {}, ref),
    /lost owner/,
  );
  assert.equal(host.isSuspended, false);
  assert.equal(host.signal.aborted, true);
  host.close();
});
