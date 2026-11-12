import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { ModelRuntime } from "@earendil-works/pi-coding-agent";
import {
  createAssistantMessageEventStream,
  InMemoryCredentialStore,
  getSystemMessageText,
} from "@earendil-works/pi-ai";
import { PiAdapter, PiDurableAdapter } from "../dist/index.js";
import { ManagedModels } from "../dist/models.js";
import {
  TurnRejected,
  runClaimed,
  sha256,
  TextPreview,
} from "@managed-agents/harness-runtime";

function assistant(content, stopReason = "stop") {
  return {
    role: "assistant",
    provider: "fixture",
    model: "fixture",
    api: "openai-completions",
    content,
    stopReason,
    timestamp: Date.now(),
    usage: {
      input: 2,
      output: 3,
      cacheRead: 0,
      cacheWrite: 0,
      totalTokens: 5,
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
    },
  };
}

async function fixture(t, responder, options = {}) {
  const cwd = await mkdtemp(join(tmpdir(), "managed-pi-test-"));
  t.after(() => rm(cwd, { recursive: true, force: true }));
  const runtime = await ModelRuntime.create({
    credentials: new InMemoryCredentialStore(),
    modelsPath: null,
    refreshOnCreate: false,
  });
  let requests = 0;
  const requestedModels = [];
  runtime.registerProvider("fixture", {
    api: "openai-completions",
    apiKey: "fixture-key",
    baseUrl: "http://unused.invalid",
    models: [
      {
        id: "fixture",
        name: "Fixture",
        reasoning: false,
        input: ["text"],
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
        contextWindow: 128000,
        maxTokens: 1024,
      },
    ],
    streamSimple: (model, context, request) => {
      requests++;
      requestedModels.push(model.id);
      const stream = createAssistantMessageEventStream();
      void Promise.resolve()
        .then(() => responder(context, request, host, stream))
        .then((message) => {
          stream.push(
            message.stopReason === "aborted" || message.stopReason === "error"
              ? { type: "error", reason: message.stopReason, error: message }
              : { type: "done", reason: message.stopReason, message },
          );
          stream.end();
        })
        .catch((error) => {
          const message = {
            ...assistant([], "error"),
            errorMessage: String(error),
          };
          stream.push({ type: "error", reason: "error", error: message });
          stream.end();
        });
      return stream;
    },
  });
  const owner = new AbortController();
  const user = new AbortController();
  const listeners = new Set();
  const boundaries = [];
  const publicFacts = [];
  const previewRequests = [];
  let snapshotProvider;
  const host = {
    turnId: options.turnId ?? "turn-1",
    signal: owner.signal,
    workSignal: AbortSignal.any([owner.signal, user.signal]),
    cancelled: false,
    snapshot: options.snapshot,
    textPreview(modelCallId, messageId) {
      return new TextPreview({
        async post(operation, request) {
          previewRequests.push({ operation, ...request });
          return operation === "previews/interest" ? { interested: true } : { accepted: true };
        },
      }, {}, modelCallId, messageId, this.workSignal);
    },
    check() {
      owner.signal.throwIfAborted();
    },
    restore: async () => options.snapshot,
    useSnapshot: (capture) => {
      snapshotProvider = capture;
    },
    async boundary(changes = {}, native, facts = []) {
      this.check();
      const snapshot = structuredClone(native ?? (await snapshotProvider()));
      boundaries.push(structuredClone(changes));
      this.snapshot = snapshot;
      publicFacts.push(...structuredClone(facts));
    },
    onCancel(listener) {
      listeners.add(listener);
      if (this.cancelled) listener();
      return () => listeners.delete(listener);
    },
    cancel() {
      this.cancelled = true;
      user.abort(new Error("user cancelled"));
      for (const listener of listeners) listener();
    },
    async modelStart() {
      if (options.rejectModel) throw new TurnRejected("budget rejected");
      if (this.cancelled) throw new Error("cancelled");
      return "model-" + boundaries.length;
    },
    async modelCompletion(id, message) {
      return { modelCallId: id, phase: "complete", status: message.stopReason };
    },
    async invokeTool(callId) {
      if (options.toolFailure) throw options.toolFailure;
      return {
        resultId: `result-${callId}`,
        status: "failed",
        content: { inline: { error: "fixture failure" } },
      };
    },
    async resultContent(result) {
      return result.content.inline;
    },
    resultApplied(callId, result) {
      boundaries.push({ applied: callId, result: result.resultId });
    },
    closeInputs: async () => {},
    inputs: async () =>
      host.cancelled
        ? [{ inputId: "cancel", type: "cancel", content: { inline: null } }]
        : [],
    inputApplied: async () => {},
    rejectInput: async () => {},
  };
  return {
    runtime,
    host,
    boundaries,
    publicFacts,
    previewRequests,
    requests: () => requests,
    requestedModels,
    config: {
      cwd,
      prompt: "hello",
      system: "fixture",
      model: { provider: "fixture", id: "fixture" },
      maxModelCalls: 10,
      tools: [],
      toolConfiguration: {},
    },
  };
}

for (const Adapter of [PiAdapter, PiDurableAdapter]) {
  test(`${Adapter.name}: short evaluation preserves a large JSON result without native compaction`, async (t) => {
    const previous = process.env.MANAGED_AGENTS_EVALUATION_CONTEXT_MODE;
    process.env.MANAGED_AGENTS_EVALUATION_CONTEXT_MODE = "short";
    t.after(() => {
      if (previous === undefined) delete process.env.MANAGED_AGENTS_EVALUATION_CONTEXT_MODE;
      else process.env.MANAGED_AGENTS_EVALUATION_CONTEXT_MODE = previous;
    });
    const original = { content: [{ type: "text", text: "x".repeat(50000) }],
      structuredContent: { nested: [null, { value: "a\\b\n" }] }, isError: false };
    const f = await fixture(t, (context) => {
      const system = context.messages.find((message) => message.role === "system");
      if (Adapter === PiAdapter)
        assert.equal(system ? getSystemMessageText(system) : context.systemPrompt, "fixture");
      assert.ok(!JSON.stringify(context).includes("<cwd>"));
      const result = context.messages.find((message) => message.role === "toolResult");
      if (result) {
        assert.deepEqual(JSON.parse(result.content[0].text), original);
        return assistant([{ type: "text", text: "complete" }]);
      }
      const message = assistant([{ type: "toolCall", id: "large", name: "lookup", arguments: {} }], "toolUse");
      message.usage.input = 127000;
      message.usage.totalTokens = 127003;
      return message;
    });
    f.host.invokeTool = async () => ({ resultId: "large-result", status: "succeeded", content: { inline: original } });
    f.config.tools = [{ name: "lookup", description: "fixture", parameters: { type: "object", properties: {} } }];
    assert.deepEqual(await new Adapter({ createModels: async () => f.runtime }).run(f.host, f.config),
      [{ type: "text", text: "complete" }]);
    assert.equal(f.requests(), 2, "an auxiliary compaction request escaped the short baseline");
  });
  test(`${Adapter.name}: public text arrives before model completion and shares the persisted final identity`, async (t) => {
    const text = "你好🙂 streamed answer";
    const f = await fixture(t, async (_context, _request, _host, stream) => {
      const partial = assistant([{ type: "thinking", thinking: "private reasoning" }]);
      stream.push({ type: "start", partial });
      stream.push({ type: "thinking_delta", contentIndex: 0, delta: "private reasoning", partial });
      partial.content.push({ type: "text", text });
      stream.push({ type: "text_delta", contentIndex: 1, delta: text, partial });
      for (let count = 0; !f.previewRequests.some((r) => r.operation === "previews/publish"); count++) {
        assert.ok(count < 200, "delta must arrive while the native model is still running");
        await new Promise((resolve) => setTimeout(resolve, 5));
      }
      assert.ok(!f.boundaries.some((b) => b.modelUpdates?.some((u) => u.phase === "complete")));
      return assistant([{ type: "text", text }]);
    });
    assert.deepEqual(await new Adapter({ createModels: async () => f.runtime }).run(f.host, f.config), [{ type: "text", text }]);
    const batches = f.previewRequests.filter((r) => r.operation === "previews/publish");
    assert.equal(batches.flatMap((r) => r.chunks).join(""), text);
    assert.equal(batches[0].sequence, "0");
    assert.equal(batches[0].messageId, f.host.outputMessageId);
    assert.equal(f.publicFacts[0].content.inline.item.message_id, f.host.outputMessageId);
    assert.ok(!JSON.stringify(f.previewRequests).includes("private reasoning"));
  });
  test(`${Adapter.name}: native message commits expose text once and retain final identity through recovery`, async (t) => {
    const f = await fixture(t, (context) => context.messages.some((message) => message.role === "toolResult")
      ? assistant([{ type: "text", text: "final answer" }])
      : assistant([
        { type: "text", text: "checking" },
        { type: "thinking", thinking: "private reasoning" },
        { type: "toolCall", id: "message-tool", name: "lookup", arguments: {} },
      ], "toolUse"));
    f.config.tools = [{ name: "lookup", description: "fixture", parameters: { type: "object", properties: {} } }];
    const output = await new Adapter({ createModels: async () => f.runtime }).run(f.host, f.config);
    assert.deepEqual(output, [{ type: "text", text: "final answer" }]);
    const messages = f.publicFacts.map((fact) => fact.content.inline.item);
    assert.equal(messages.length, 2);
    assert.deepEqual(messages.map((message) => message.content), [
      [{ type: "text", text: "checking" }], [{ type: "text", text: "final answer" }],
    ]);
    assert.notEqual(messages[0].message_id, messages[1].message_id);
    assert.equal(f.host.outputMessageId, messages[1].message_id);
    assert.ok(!JSON.stringify(f.publicFacts).includes("private reasoning"));
    const recovered = await fixture(t, () => { throw new Error("completed native Turn must not infer again"); }, { snapshot: f.host.snapshot });
    recovered.config.tools = f.config.tools;
    assert.deepEqual(await new Adapter({ createModels: async () => recovered.runtime }).run(recovered.host, recovered.config), output);
    assert.equal(recovered.host.outputMessageId, f.host.outputMessageId);
    assert.deepEqual(recovered.publicFacts, []);
  });
  test(`${Adapter.name}: a new Turn applies its sealed model to restored native context`, async (t) => {
    const first = await fixture(t, () =>
      assistant([{ type: "text", text: "first answer" }]),
    );
    await new Adapter({ createModels: async () => first.runtime }).run(
      first.host,
      first.config,
    );
    const next = await fixture(
      t,
      () => assistant([{ type: "text", text: "next answer" }]),
      { turnId: "turn-2", snapshot: first.host.snapshot },
    );
    const provider = next.runtime.getRegisteredProviderConfig("fixture");
    next.runtime.registerProvider("fixture", {
      ...provider,
      models: [...provider.models, { ...provider.models[0], id: "next-model" }],
    });
    next.config.model.id = "next-model";
    await new Adapter({ createModels: async () => next.runtime }).run(
      next.host,
      next.config,
    );
    assert.deepEqual(next.requestedModels, ["next-model"]);
  });

  test(
    `${Adapter.name}: real SDK returns text and reopens completed native state without another request`,
    { timeout: 15000 },
    async (t) => {
      const one = await fixture(t, () =>
        assistant([{ type: "text", text: "answer" }]),
      );
      const adapter = new Adapter({ createModels: async () => one.runtime });
      assert.deepEqual(await adapter.run(one.host, one.config), [
        { type: "text", text: "answer" },
      ]);
      assert.equal(one.requests(), 1);
      assert.ok(
        one.boundaries.some((b) =>
          b.modelUpdates?.some((u) => u.phase === "complete"),
        ),
      );
      const two = await fixture(
        t,
        () => {
          throw new Error("must not dispatch on recovery");
        },
        { snapshot: one.host.snapshot },
      );
      assert.deepEqual(
        await new Adapter({ createModels: async () => two.runtime }).run(
          two.host,
          two.config,
        ),
        [{ type: "text", text: "answer" }],
      );
      assert.equal(two.requests(), 0);
    },
  );

  test(
    `${Adapter.name}: budget denial blocks provider and propagates beyond SDK error conversion`,
    { timeout: 15000 },
    async (t) => {
      const f = await fixture(
        t,
        () => assistant([{ type: "text", text: "forbidden" }]),
        { rejectModel: true },
      );
      await assert.rejects(
        new Adapter({ createModels: async () => f.runtime }).run(
          f.host,
          f.config,
        ),
        /budget rejected/,
      );
      assert.equal(f.requests(), 0);
    },
  );

  test(
    `${Adapter.name}: failed Hands result reaches the next model as isError`,
    { timeout: 15000 },
    async (t) => {
      let seen;
      const f = await fixture(t, (context) => {
        const result = context.messages.find(
          (message) => message.role === "toolResult",
        );
        if (!result)
          return assistant(
            [
              {
                type: "toolCall",
                id: "original-call",
                name: "execute",
                arguments: {},
              },
            ],
            "toolUse",
          );
        seen = result;
        return assistant([{ type: "text", text: "tool failed" }]);
      });
      f.config.tools = [
        {
          name: "execute",
          description: "fixture",
          parameters: { type: "object", properties: {} },
          operation: "sandbox.exec",
        },
      ];
      assert.deepEqual(
        await new Adapter({ createModels: async () => f.runtime }).run(
          f.host,
          f.config,
        ),
        [{ type: "text", text: "tool failed" }],
      );
      assert.equal(seen.isError, true);
      assert.equal(seen.toolCallId, "original-call");
      assert.ok(f.boundaries.some((b) => b.applied === "original-call"));
    },
  );

  test(
    `${Adapter.name}: user cancellation aborts the active provider while settlement remains available`,
    { timeout: 15000 },
    async (t) => {
      let interrupted = false;
      const f = await fixture(t, async (_context, request, host) => {
        const done = new Promise((resolve) =>
          request.signal.addEventListener(
            "abort",
            () => {
              interrupted = true;
              resolve(assistant([], "aborted"));
            },
            { once: true },
          ),
        );
        queueMicrotask(() => host.cancel());
        return done;
      });
      await new Adapter({ createModels: async () => f.runtime }).run(
        f.host,
        f.config,
      );
      assert.equal(interrupted, true);
      assert.equal(f.host.signal.aborted, false);
      assert.ok(f.host.snapshot);
    },
  );

  test(
    `${Adapter.name}: infrastructure tool failure cannot become a successful follow-up model call`,
    { timeout: 15000 },
    async (t) => {
      const f = await fixture(
        t,
        () =>
          assistant(
            [
              {
                type: "toolCall",
                id: "original-call",
                name: "execute",
                arguments: {},
              },
            ],
            "toolUse",
          ),
        { toolFailure: new Error("service unavailable") },
      );
      f.config.tools = [
        {
          name: "execute",
          description: "fixture",
          parameters: { type: "object", properties: {} },
          operation: "sandbox.exec",
        },
      ];
      await assert.rejects(
        new Adapter({ createModels: async () => f.runtime }).run(
          f.host,
          f.config,
        ),
        /service unavailable/,
      );
      assert.equal(f.requests(), 1);
      const savedMessages =
        Adapter === PiAdapter
          ? f.host.snapshot.entries
              .filter((e) => e.type === "message")
              .map((e) => e.message)
          : f.host.snapshot.commits.flatMap((c) =>
              c.writes
                .filter((w) => w.type === "entry")
                .flatMap((w) => w.value.model ?? []),
            );
      assert.equal(
        savedMessages.some((m) => m.role === "toolResult"),
        false,
        "transport failure must not be persisted as a native tool result",
      );
      const recovered = await fixture(
        t,
        (context) => {
          assert.ok(
            context.messages.some(
              (m) =>
                m.role === "toolResult" && m.toolCallId === "original-call",
            ),
          );
          return assistant([{ type: "text", text: "recovered" }]);
        },
        { snapshot: f.host.snapshot },
      );
      recovered.config.tools = f.config.tools;
      assert.deepEqual(
        await new Adapter({ createModels: async () => recovered.runtime }).run(
          recovered.host,
          recovered.config,
        ),
        [{ type: "text", text: "recovered" }],
      );
    },
  );

  test(
    `${Adapter.name}: a new Turn preserves context and submits its new input exactly once`,
    { timeout: 15000 },
    async (t) => {
      const first = await fixture(t, () =>
        assistant([{ type: "text", text: "first" }]),
      );
      await new Adapter({ createModels: async () => first.runtime }).run(
        first.host,
        first.config,
      );
      let messages;
      const second = await fixture(
        t,
        (context) => {
          messages = context.messages;
          return assistant([{ type: "text", text: "second" }]);
        },
        { snapshot: first.host.snapshot, turnId: "turn-2" },
      );
      second.config.prompt = "next question";
      assert.deepEqual(
        await new Adapter({ createModels: async () => second.runtime }).run(
          second.host,
          second.config,
        ),
        [{ type: "text", text: "second" }],
      );
      assert.equal(
        messages.filter(
          (m) =>
            m.role === "user" &&
            JSON.stringify(m.content).includes("next question"),
        ).length,
        1,
      );
      assert.ok(
        messages.some(
          (m) =>
            m.role === "assistant" && m.content.some((b) => b.text === "first"),
        ),
      );
    },
  );

  test(
    `${Adapter.name}: cancellation before kernel startup sends no model request`,
    { timeout: 15000 },
    async (t) => {
      const f = await fixture(t, () =>
        assistant([{ type: "text", text: "forbidden" }]),
      );
      f.host.cancel();
      await new Adapter({ createModels: async () => f.runtime }).run(
        f.host,
        f.config,
      );
      assert.equal(f.requests(), 0);
      assert.equal(f.host.signal.aborted, false);
    },
  );
}

test("auxiliary completeSimple calls use the same admission and accounting gate", async (t) => {
  const f = await fixture(t, (_context, _request, _host, stream) => {
    const partial = assistant([{ type: "text", text: "private compaction summary" }]);
    stream.push({ type: "start", partial });
    stream.push({ type: "text_delta", contentIndex: 0, delta: "private compaction summary", partial });
    return assistant([{ type: "text", text: "summary" }]);
  });
  f.host.useSnapshot(async () => ({}));
  const gate = new ManagedModels(f.host, 10, undefined, {}, true);
  const models = gate.wrap(f.runtime);
  const result = await models.completeSimple(
    models.getModel("fixture", "fixture"),
    {
      messages: [{ role: "user", content: "summarize", timestamp: Date.now() }],
    },
  );
  assert.equal(result.content[0].text, "summary");
  assert.equal(gate.updates().length, 1);
  assert.equal(f.requests(), 1);
  assert.deepEqual(f.previewRequests, [], "auxiliary model streams must never publish public previews");
});

/** Protocol peer double: real RunHost and SDK, no database/MQ/external side effects. */
for (const Adapter of [PiAdapter, PiDurableAdapter]) {
  test(`${Adapter.name}: provider error has one attempt, committed accounting and a failed Turn`, async (t) => {
    const peer = servicePeer();
    const f = await fixture(t, () => ({...assistant([], "error"), errorMessage: "429 fixture rate limit"}));
    await runClaimed(new Adapter({createModels: async () => f.runtime}), peer.client, peer.claim(1), f.config);
    assert.equal(f.requests(), 1);
    assert.equal(peer.commits.at(-1).changes.terminal.status, "failed");
    const completions = peer.commits.flatMap((c) => c.changes.modelUpdates ?? []).filter((u) => u.phase === "complete");
    assert.equal(completions.length, 1);
    assert.equal(completions[0].status, "failed");
  });
}

function servicePeer() {
  const scope = {
    tenant: "tenant",
    namespace: "namespace",
    sessionId: "session",
  };
  const objects = new Map();
  const commits = [];
  let ready = false;
  let inputs = [];
  const recovery = () => ({
    brainRevision: String(commits.length),
    nativeState: commits.at(-1)?.nativeState,
    acceptedResults: ready ? operations().map(result) : [],
    toolOperations: operations(),
    resultApplications: commits.flatMap(
      (c) => c.changes.resultApplications ?? [],
    ),
    inputApplications: commits.flatMap(
      (c) => c.changes.inputApplications ?? [],
    ),
    interactionUpdates: commits.flatMap(
      (c) => c.changes.interactionUpdates ?? [],
    ),
    modelUpdates: commits.flatMap((c) => c.changes.modelUpdates ?? []),
    pendingInputs: inputs,
  });
  const operations = () =>
    commits.flatMap((c) => c.changes.toolOperations ?? []);
  const result = (operation) => ({
    resultId: `r-${operation.taskId}`,
    taskId: operation.taskId,
    executionId: operation.executionId,
    status: "succeeded",
    content: { inline: { answer: 42 } },
    artifacts: [],
    output: { mode: "none", throughSourceSeq: "1", byteLength: "0" },
  });
  const client = {
    async read(ref) {
      return objects.get(ref.key);
    },
    async put(scope, bytes, formatVersion) {
      const key = sha256(bytes);
      objects.set(key, bytes);
      return {
        scope,
        key,
        backendId: "fixture",
        sha256: key,
        size: String(bytes.length),
        mediaType: "application/json",
        formatVersion,
      };
    },
    async commit(request, signal) {
      signal?.throwIfAborted();
      assert.equal(request.expectedBrainRevision, String(commits.length));
      if (request.changes.suspension) {
        assert.equal(request.changes.suspension.waitPolicy, "all");
        assert.ok(
          ["WAIT_TOOLS", "APPLY_INPUTS"].includes(
            request.changes.suspension.resumePhase,
          ),
        );
      }
      commits.push(structuredClone(request));
      const settled = new Set(
        (request.changes.inputApplications ?? []).map((i) => i.inputId),
      );
      inputs = inputs.filter((i) => !settled.has(i.inputId));
      return { brainRevision: String(commits.length) };
    },
    async post(operation, body, signal) {
      signal?.throwIfAborted();
      if (operation === "recovery/read") return { bundle: recovery() };
      if (operation === "executions/renew")
        return {
          serverTime: new Date().toISOString(),
          expiresAt: new Date(Date.now() + 60000).toISOString(),
        };
      if (operation === "models/reserve" || operation === "inputs/close")
        return {};
      if (operation === "inputs/read") return { pendingInputs: inputs };
      if (operation === "tools/state") {
        const tool = operations().find((o) => o.taskId === body.taskId);
        assert.ok(tool);
        return ready
          ? { state: "succeeded", result: result(tool) }
          : { state: "unknown" };
      }
      throw new Error(`Unexpected operation ${operation}`);
    },
  };
  return {
    client,
    commits,
    operations,
    ready: () => {
      ready = true;
    },
    reply(actionId, approved) {
      inputs = [
        {
          inputId: "approval",
          type: "interaction_response",
          actionId,
          content: { inline: { approved } },
        },
      ];
    },
    claim: (epoch) => ({
      scope,
      turnId: "turn",
      fence: {
        owner: { instanceId: `i-${epoch}`, incarnationId: "p" },
        epoch: String(epoch),
        executionToken: `token-${epoch}`,
      },
      expiresAt: new Date(Date.now() + 60000).toISOString(),
    }),
  };
}

for (const Adapter of [PiAdapter, PiDurableAdapter]) {
  for (const approval of [undefined, true, false]) {
    test(
      `${Adapter.name}: suspend ${approval === undefined ? "unknown tool" : "approval"}, rebuild and ${approval === false ? "deny" : "resume original call"}`,
      { timeout: 15000 },
      async (t) => {
        const peer = servicePeer();
        const f = await fixture(t, (context) => {
          const result = context.messages.find((m) => m.role === "toolResult");
          if (!result)
            return assistant(
              [
                {
                  type: "toolCall",
                  id: "stable-call",
                  name: "execute",
                  arguments: {},
                },
              ],
              "toolUse",
            );
          return assistant([
            { type: "text", text: result.isError ? "denied" : "resumed" },
          ]);
        });
        const config = {
          ...f.config,
          inlineWaitMs: 0,
          tools: [
            {
              name: "execute",
              description: "fixture",
              parameters: { type: "object", properties: {} },
              operation: "sandbox.exec",
              permission:
                approval === undefined ? "always_allow" : "always_ask",
            },
          ],
        };
        await runClaimed(
          new Adapter({ createModels: async () => f.runtime }),
          peer.client,
          peer.claim(1),
          config,
        );
        const suspended = peer.commits.at(-1).changes.suspension;
        assert.ok(suspended);
        assert.equal(
          peer.commits.some((c) => c.changes.terminal),
          false,
        );
        assert.equal(f.requests(), 1);
        if (approval !== undefined)
          peer.reply(suspended.pendingActionIds[0], approval);
        peer.ready();
        await runClaimed(
          new Adapter({ createModels: async () => f.runtime }),
          peer.client,
          peer.claim(2),
          config,
        );
        assert.deepEqual(peer.commits.at(-1).changes.terminal, {
          messageId: peer.commits.flatMap((commit) => commit.publicFacts ?? []).at(-1).content.inline.item.message_id,
          status: "succeeded",
          output: {
            inline: [
              { type: "text", text: approval === false ? "denied" : "resumed" },
            ],
          },
        });
        assert.equal(peer.operations().length, approval === false ? 0 : 1);
        if (approval !== false) {
          assert.deepEqual(peer.operations()[0].nativeCallIds, ["stable-call"]);
          assert.ok(
            peer.commits.some((c) =>
              c.changes.resultApplications?.some(
                (r) => r.nativeCallId === "stable-call",
              ),
            ),
          );
        }
        assert.equal(f.requests(), 2);
      },
    );
  }
}
