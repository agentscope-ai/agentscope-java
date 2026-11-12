import { createServer } from "node:http";
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  createManagedModelRuntime,
  configureModel,
} from "../dist/configuration.js";
import { ManagedModels } from "../dist/models.js";
import { createAssistantMessageEventStream } from "@earendil-works/pi-ai";
const cost = { input: 1, output: 2, cacheRead: 0.1, cacheWrite: 1 };
const config = {
  provider: "managed-custom",
  id: "custom",
  api: "openai-completions",
  baseUrl: "https://models.example.invalid/v1",
  contextWindow: 128000,
  outputLimit: 4096,
  credentialEnv: "PI_MODEL_KEY",
  cost,
};

test("custom provider uses sealed endpoint, explicit limits/cost and an in-memory credential", async () => {
  const runtime = await configureModel(
    await createManagedModelRuntime(),
    config,
    { PI_MODEL_KEY: "fixture-secret" },
  );
  const model = runtime.getModel(config.provider, config.id);
  assert.equal(model.baseUrl, config.baseUrl);
  assert.equal(model.maxTokens, 4096);
  assert.deepEqual(model.cost, cost);
  const auth = await runtime.getAuth(model);
  assert.equal(auth.auth.apiKey, "fixture-secret");
  assert.ok(
    !JSON.stringify(
      runtime.getRegisteredProviderConfig(config.provider),
    ).includes("fixture-secret"),
  );
  assert.ok(!JSON.stringify(config).includes("fixture-secret"));
});

test("invalid endpoint/credential mapping fails before provider dispatch", async () => {
  for (const override of [
    { baseUrl: "https://user:secret@example.invalid" },
    { baseUrl: "https://example.invalid?key=secret" },
    { credentialEnv: "MANAGED_AGENTS_INTERNAL_TOKEN" },
    { credentialEnv: "PI_MODEL_MISSING" },
    { outputLimit: 256000 },
    { parameters: { maxTokens: 4097 } },
    { reasoning: false, parameters: { reasoning: "low" } },
    { cost: undefined },
    { requestTimeoutSeconds: 0 },
    { requestTimeoutSeconds: 3601 },
    { parameters: { topP: 1.1 } },
    { parameters: { parallelToolCalls: "false" } },
    { parameters: { toolChoice: "invalid" } },
  ]) {
    await assert.rejects(
      configureModel(
        await createManagedModelRuntime(),
        { ...config, ...override },
        { PI_MODEL_KEY: "fixture-secret" },
      ),
      (error) => !error.message.includes("fixture-secret"),
    );
  }
});

test("per-instance credentials and model options stay isolated across shared provider names", async () => {
  const instances = await Promise.all(["ALPHA", "BETA"].map(async (name, index) => {
    const binding = { ...config, id: name, credentialEnv: `MANAGED_MODEL_${name}`,
      baseUrl: `https://${name.toLowerCase()}.example.invalid/v1`, outputLimit: 1024 + index };
    const runtime = await configureModel(await createManagedModelRuntime(), binding,
      { [`MANAGED_MODEL_${name}`]: `${name}-secret` });
    return { runtime, binding };
  }));
  for (const { runtime, binding } of instances) {
    const model = runtime.getModel(binding.provider, binding.id);
    assert.equal(model.baseUrl, binding.baseUrl);
    assert.equal(model.maxTokens, binding.outputLimit);
    assert.equal((await runtime.getAuth(model)).auth.apiKey, `${binding.id}-secret`);
    assert.ok(!JSON.stringify(runtime.getRegisteredProviderConfig(binding.provider)).includes("-secret"));
  }
});

test("sealed sampling and output cap apply to normal and auxiliary calls and their budget request", async () => {
  const requests = [],
    dispatched = [];
  const signal = new AbortController().signal;
  const host = {
    signal,
    workSignal: signal,
    check() {},
    modelStart: async (_id, request) => {
      requests.push(request);
      return "call";
    },
    modelCompletion: async () => ({}),
  };
  const gate = new ManagedModels(host, 100, undefined, {
    maxTokens: 100,
    temperature: 0.2,
    reasoning: "low",
  });
  const dispatch = (_model, _context, options) => {
    dispatched.push(options);
    const stream = createAssistantMessageEventStream();
    stream.push({
      type: "done",
      reason: "stop",
      message: {
        role: "assistant",
        content: [],
        stopReason: "stop",
        usage: {},
      },
    });
    stream.end();
    return stream;
  };
  const runtime = gate.wrap({ streamSimple: dispatch, stream: dispatch });
  for (const method of ["complete", "completeSimple"])
    await runtime[method](
      { provider: "test", id: "test" },
      { messages: [] },
      { maxTokens: 1000, maxRetries: 5, timeoutMs: 1 },
    );
  await runtime.completeSimple(
    { provider: "test", id: "test" },
    { messages: [] },
    { maxTokens: 20 },
  );
  assert.deepEqual(
    dispatched.map((o) => o.maxTokens),
    [100, 100, 20],
  );
  assert.deepEqual(
    requests.map((o) => o.maxTokens),
    [100, 100, 20],
  );
  assert.ok(
    requests.every((o) => o.temperature === 0.2 && o.reasoning === "low"),
  );
  assert.ok(dispatched.every((o) => o.maxRetries === 0 && o.timeoutMs === 180000));
});

test("custom OpenAI-compatible route sends the managed credential and consumes an actual HTTP stream", async (t) => {
  let captured;
  const server = createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    captured = {
      url: req.url,
      auth: req.headers.authorization,
      body: JSON.parse(Buffer.concat(chunks)),
    };
    res.writeHead(200, { "content-type": "text/event-stream" });
    for (const [delta, finish] of [
      [{ role: "assistant", content: "configured" }, null],
      [{}, "stop"],
    ])
      res.write(
        `data: ${JSON.stringify({ id: "fixture", object: "chat.completion.chunk", created: 1, model: "custom", choices: [{ index: 0, delta, finish_reason: finish }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } })}\n\n`,
      );
    res.end("data: [DONE]\n\n");
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(async () => {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  });
  const runtime = await configureModel(
    await createManagedModelRuntime(),
    { ...config, baseUrl: `http://127.0.0.1:${server.address().port}/v1` },
    { PI_MODEL_KEY: "fixture-secret" },
  );
  const signal = new AbortController().signal;
  const gate = new ManagedModels({signal, workSignal: signal, check() {},
    modelStart: async () => "wire-call", modelCompletion: async () => ({})},
    1, undefined, {temperature: 0.2, maxTokens: 100, topP: 0.75,
      parallelToolCalls: false, toolChoice: "none"});
  const result = await gate.wrap(runtime).completeSimple(
    runtime.getModel(config.provider, config.id),
    { messages: [{ role: "user", content: "hello", timestamp: 1 }] },
    { temperature: 0.2, maxTokens: 100 },
  );
  assert.equal(captured.url, "/v1/chat/completions");
  assert.equal(captured.auth, "Bearer fixture-secret");
  assert.equal(captured.body.model, "custom");
  assert.equal(captured.body.temperature, 0.2);
  assert.equal(captured.body.top_p, 0.75);
  assert.equal(captured.body.parallel_tool_calls, false);
  assert.equal(captured.body.tool_choice, "none");
  assert.equal(
    result.content.find((c) => c.type === "text").text,
    "configured",
  );
});


test("model deadline aborts the provider and remains an infrastructure failure", async () => {
  const signal = new AbortController().signal;
  const gate = new ManagedModels({signal, workSignal: signal, check() {},
    modelStart: async () => "timeout-call", modelCompletion: async () => ({})},
    1, undefined, {}, false, 20);
  let dispatchedSignal;
  const runtime = gate.wrap({streamSimple(_model, _context, options) {
    dispatchedSignal = options.signal;
    const stream = createAssistantMessageEventStream();
    options.signal.addEventListener("abort", () => stream.end(), {once: true});
    return stream;
  }});
  const keepAlive = setTimeout(() => {}, 1000);
  try {
    const result = await runtime.completeSimple({provider: "test", id: "test"}, {messages: []});
    assert.equal(result.stopReason, "aborted");
    assert.equal(dispatchedSignal.aborted, true);
    assert.equal(signal.aborted, false);
    assert.throws(() => gate.check(), {name: "TimeoutError"});
  } finally { clearTimeout(keepAlive); }
});

test("Responses wire uses sealed controls and a 429 has one provider attempt", async (t) => {
  const captured = [];
  const server = createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    captured.push({url: req.url, body: JSON.parse(Buffer.concat(chunks))});
    res.writeHead(429, {"content-type": "application/json"});
    res.end(JSON.stringify({error: {message: "fixture rate limit", type: "rate_limit_error"}}));
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(async () => { server.closeAllConnections(); await new Promise((resolve) => server.close(resolve)); });
  const binding = {...config, reasoning: true, api: "openai-responses", baseUrl: `http://127.0.0.1:${server.address().port}/v1`};
  const native = await configureModel(await createManagedModelRuntime(), binding, {PI_MODEL_KEY: "fixture"});
  let reservations = 0;
  const signal = new AbortController().signal;
  const gate = new ManagedModels({signal, workSignal: signal, check() {},
    modelStart: async () => { reservations++; return "429-call"; }, modelCompletion: async () => ({})},
    1, undefined, {reasoning: "low", topP: 1, parallelToolCalls: false, toolChoice: "auto"});
  const result = await gate.wrap(native).completeSimple(native.getModel(binding.provider, binding.id),
    {messages: [{role: "user", content: "hello", timestamp: 1}]});
  assert.equal(result.stopReason, "error");
  assert.equal(reservations, 1);
  assert.equal(captured.length, 1);
  assert.equal(captured[0].url, "/v1/responses");
  assert.equal(captured[0].body.top_p, 1);
  assert.equal(captured[0].body.parallel_tool_calls, false);
  assert.equal(captured[0].body.tool_choice, "auto");
  assert.deepEqual(captured[0].body.reasoning, {effort: "low"});
  assert.doesNotThrow(() => gate.check()); // RunHost records this terminal provider response as failed.
});
