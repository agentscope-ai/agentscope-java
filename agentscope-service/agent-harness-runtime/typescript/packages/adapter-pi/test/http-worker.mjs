// Launched by PiBrainHttpFlowTest. Only the model provider is deterministic;
// the SDK, adapter, Worker polling and Session Service HTTP client are real.
import { ModelRuntime } from "@earendil-works/pi-coding-agent";
import {
  createAssistantMessageEventStream,
  InMemoryCredentialStore,
} from "@earendil-works/pi-ai";
import { runWorker } from "@managed-agents/harness-runtime/worker";
import { PiAdapter, PiDurableAdapter } from "../dist/index.js";

const runtime = await ModelRuntime.create({
  credentials: new InMemoryCredentialStore(),
  modelsPath: null,
  refreshOnCreate: false,
});
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
  streamSimple: (_model, context) => {
    // PI normalizes the public Context into transcript system messages before invoking a provider.
    const instructions = context.messages.filter((message) => message.role === "system");
    if (_model.id !== "fixture" || !JSON.stringify(instructions).includes("fixture instructions from Session override"))
      throw new Error("Sealed Agent definition overrides did not reach the native model request");
    const result = context.messages.find(
      (message) => message.role === "toolResult",
    );
    if (
      result &&
      (result.isError ||
        !JSON.stringify(result.content).includes("hands-result"))
    )
      throw new Error("Hands result was not restored into the model context");
    const message = {
      role: "assistant",
      provider: "fixture",
      model: "fixture",
      api: "openai-completions",
      content: result
        ? [{ type: "text", text: "finished: hands-result" }]
        : [
            { type: "text", text: "checking: hands" },
            ...(Number(process.env.PI_TEST_PADDING_BYTES ?? 0) > 0
              ? [
                  {
                    type: "text",
                    text: "x".repeat(Number(process.env.PI_TEST_PADDING_BYTES)),
                  },
                ]
              : []),
            {
              type: "toolCall",
              id: "call_http_0",
              name: "execute",
              arguments: {
                command: process.env.PI_TEST_COMMAND,
                timeout_seconds: 40,
              },
            },
          ],
      stopReason: result ? "stop" : "toolUse",
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
    process.stdout.write(
      JSON.stringify({ event: "fixture.model", hasToolResult: !!result }) +
        "\n",
    );
    const stream = createAssistantMessageEventStream();
    // Keep the deterministic model response open so public deltas must cross
    // the real HTTP ingress while this model call is still reserved.
    const partial = { ...message, content: [message.content[0]] };
    stream.push({ type: "start", partial });
    stream.push({ type: "text_delta", contentIndex: 0, delta: message.content[0].text, partial });
    setTimeout(() => {
      stream.push({ type: "done", reason: message.stopReason, message });
      stream.end();
    }, 500);
    return stream;
  },
});
const Adapter =
  process.env.MANAGED_AGENTS_HARNESS_TYPE === "pi-durable"
    ? PiDurableAdapter
    : PiAdapter;
await runWorker(new Adapter({ createModels: async () => runtime }));
