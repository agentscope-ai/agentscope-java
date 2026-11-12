import { configureModel, createManagedModelRuntime } from "./configuration.js";
import {
  assistantOutput,
  assistantMessageFact,
  TurnRejected,
} from "@managed-agents/harness-runtime";
import {
  Harness,
  createRegistry,
  defineTool,
  ToolResultEntry,
  AssistantEntry,
} from "@earendil-works/pi-durable";
import {
  BACKGROUND_CONTEXT,
  withAbortSignal,
} from "@earendil-works/chord/context";
import { Type } from "@sinclair/typebox";
import type {
  HarnessAdapter,
  RunHost,
  RunConfiguration,
  Json,
  ToolResult,
  Changes,
} from "@managed-agents/harness-runtime";
import {
  SessionServicePiStorage,
  type DurableSnapshot,
} from "./durable-storage.js";
import { ManagedModels, messageIdentity, type PiDependencies } from "./models.js";

export class PiDurableAdapter implements HarnessAdapter {
  readonly type = "pi-durable";
  readonly stateFormat = "pi-durable-1.4.0-writes-v1";
  readonly recoveryPageSize = 16;
  constructor(private readonly dependencies: PiDependencies = {}) {}
  async run(host: RunHost, config: RunConfiguration): Promise<Json> {
    const completed = new Map<string, ToolResult>();
    const models = new ManagedModels(
      host,
      config.maxModelCalls,
      undefined,
      config.model.parameters,
      true,
      (config.model.requestTimeoutSeconds ?? 180) * 1000,
    );
    const storage = new SessionServicePiStorage(async (snapshot, writes) => {
      // A failed transport/admission must not become a durable native tool/model error.
      models.check();
      const changes: Changes = { modelUpdates: models.updates() };
      const publicFacts: Json[] = [];
      for (const write of writes)
        if (write.type === "entry") {
          // PI Durable reserves conversation 1 for the root. Child and compaction
          // output must not become a public root assistant message.
          if (write.value.conversationId === 1 && AssistantEntry.is(write.value))
            for (const [index, message] of (write.value.model ?? []).entries())
              if (message.role === "assistant")
                publicFacts.push(assistantMessageFact(
                  host.turnId,
                  messageIdentity(message, `entry_${write.value.id}_${index}`),
                  message as unknown as Json,
                ));
          if (ToolResultEntry.is(write.value))
            for (const message of write.value.model ?? [])
              if (message.role === "toolResult") {
                const result = completed.get(message.toolCallId);
                if (result) {
                  host.resultApplied(message.toolCallId, result);
                  completed.delete(message.toolCallId);
                }
              }
        }
      await host.boundary(changes, snapshot as unknown as Json, publicFacts);
    });
    const restored = await host.restore();
    if (restored) await storage.restore(restored as unknown as DurableSnapshot);
    host.useSnapshot(
      async () => storage.snapshot() as unknown as Json,
      "commits",
    );
    const context = withAbortSignal(
      AbortSignal.any([host.signal, models.failureSignal]),
      BACKGROUND_CONTEXT,
    );
    const registry = createRegistry();
    const tools = config.tools.map((tool) =>
      defineTool({
        name: tool.name,
        description: tool.description,
        parameters: Type.Unsafe(tool.parameters),
        replay: "safe",
        executionMode: "sequential",
        execute: async (args, api) =>
          models.protect(async () => {
            const result = await host.invokeTool(
              api.callId,
              tool,
              args as Json,
              config.toolConfiguration,
            );
            completed.set(api.callId, result);
            return {
              output: [
                {
                  type: "text",
                  text: JSON.stringify(await host.resultContent(result)),
                },
              ],
              isError: result.status !== "succeeded",
            };
          }),
      }),
    );
    const extension = {
      name: "managed-agents",
      tools,
    };
    registry.install(extension);
    const runtime = models.wrap(
      await configureModel(
        await (this.dependencies.createModels?.() ??
          createManagedModelRuntime()),
        config.model,
      ),
    );
    if (!runtime.getModel(config.model.provider, config.model.id))
      throw new TurnRejected("Configured PI model is unavailable");
    const harness = await Harness.open(
      storage,
      { models: runtime, registry, settings: { retry: { enabled: false, maxRetries: 0 }, stream: { maxRetries: 0 },
          ...(process.env.MANAGED_AGENTS_EVALUATION_CONTEXT_MODE === "short"
            ? { compaction: { enabled: false } } : {}),
        } },
      context,
    );
    let unsubscribe = () => {};
    let cancellation: Promise<void> | undefined;
    let cancellationError: unknown;
    try {
      const agent = {
        instructions: config.system ?? "",
        model: { provider: config.model.provider, modelId: config.model.id },
        extensions: [extension],
        tools,
      };
      const conversation = await harness.root(context, { agent });
      // root(options) configures only creation. Restored roots must use this Turn's sealed definition.
      if (restored) await conversation.configure(agent, context);
      unsubscribe = host.onCancel(() => {
        cancellation ??= conversation
          .abort(context, { background: true })
          .catch((error) => {
            cancellationError = error;
          });
      });
      // Native requestId deduplication reuses the admitted submission across process restarts.
      const submission = host.cancelled
        ? undefined
        : await conversation.submit(
            { type: "input", requestId: host.turnId, content: config.prompt },
            context,
          );
      try {
        await submission?.wait(context);
      } catch (error) {
        if (!host.cancelled || host.signal.aborted) throw error;
      }
      models.check();
      await host.closeInputs();
      for (const input of await host.inputs()) {
        if (input.type === "message" && !host.cancelled) {
          const addition = await conversation.submit(
            {
              type: "input",
              requestId: input.inputId,
              content:
                typeof input.content.inline === "string"
                  ? input.content.inline
                  : JSON.stringify(input.content.inline),
            },
            context,
          );
          await host.inputApplied(input.inputId);
          try {
            await addition.wait(context);
          } catch (error) {
            if (!host.cancelled || host.signal.aborted) throw error;
          }
          models.check();
        } else if (input.type === "cancel")
          await host.inputApplied(input.inputId);
        else
          await host.rejectInput(
            input.inputId,
            host.cancelled
              ? "cancelled"
              : "No pending interaction matches the input",
          );
      }
      await cancellation;
      if (cancellationError) throw cancellationError;
      models.check();
      const updates = models.updates();
      if (updates.length) await host.boundary({ modelUpdates: updates });
      // Native context selects the active root branch and works after process recovery.
      // Entry.model is an array, not a single AssistantMessage.
      const view = await conversation.context(context);
      if (host.cancelled) return [];
      // Contributions exclude failed responses and apply the active context selection.
      // Select the identity from the same contribution as the final content.
      for (let index = view.entries.length - 1; index >= 0; --index) {
        const messages = view.contributions[index] ?? [];
        for (let messageIndex = messages.length - 1; messageIndex >= 0; --messageIndex) {
          const message = messages[messageIndex]!;
          if (message.role !== "assistant") continue;
          host.outputMessageId = messageIdentity(message, `entry_${view.entries[index]!.id}_${messageIndex}`);
          return assistantOutput(message as unknown as Json);
        }
      }
      return [];
    } catch (error) {
      models.check();
      throw error;
    } finally {
      models.closePreviews();
      unsubscribe();
      await cancellation;
      await harness.close(BACKGROUND_CONTEXT);
    }
  }
}
