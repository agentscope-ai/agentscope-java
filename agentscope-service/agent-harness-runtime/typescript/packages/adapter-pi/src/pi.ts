import { configureModel, createManagedModelRuntime } from "./configuration.js";
import {
  assistantOutput,
  assistantMessageFact,
  ToolDenied,
  TurnRejected,
} from "@managed-agents/harness-runtime";
import {
  createAgentSession,
  DefaultResourceLoader,
  SessionManager,
  SettingsManager,
  type ToolDefinition,
  type ExtensionFactory,
} from "@earendil-works/pi-coding-agent";
import { Type } from "@sinclair/typebox";
import type {
  HarnessAdapter,
  RunHost,
  RunConfiguration,
  Json,
  ToolResult,
} from "@managed-agents/harness-runtime";
import { ManagedModels, messageIdentity, type PiDependencies } from "./models.js";

type Entries = NonNullable<Parameters<typeof SessionManager.inMemory>[2]>;
interface PiSnapshot {
  version: 1;
  turnId: string;
  entries: Entries;
  leafId?: string;
  promptAccepted: boolean;
}
/** PI library and adapter share one Node process. Only declared custom tools may reach Hands. */
export class PiAdapter implements HarnessAdapter {
  readonly type = "pi";
  readonly stateFormat = "pi-1.4.0-session-v1";
  readonly recoveryPageSize = 16;
  constructor(private readonly dependencies: PiDependencies = {}) {}
  async run(host: RunHost, config: RunConfiguration): Promise<Json> {
    const restored = (await host.restore()) as PiSnapshot | undefined;
    if (restored && restored.version !== 1)
      throw new Error("Unsupported PI state format");
    const manager = SessionManager.inMemory(
      config.cwd,
      undefined,
      restored?.entries,
    );
    if (restored?.leafId) manager.branch(restored.leafId);
    let promptAccepted =
      (restored?.turnId === host.turnId && restored.promptAccepted) || false;
    let applyingInput: string | undefined;
    const capture = async (): Promise<Json> =>
      ({
        version: 1,
        turnId: host.turnId,
        entries: [manager.getHeader()!, ...manager.getEntries()],
        leafId: manager.getLeafId() ?? undefined,
        promptAccepted,
      }) as unknown as Json;
    host.useSnapshot(capture, "entries");
    const completed = new Map<string, ToolResult>();
    // Restored entries were already committed with their public facts. New Turns must
    // not re-publish historical context as newly generated messages.
    const published = new Set(manager.getEntries().map((entry) => entry.id));
    let models: ManagedModels;
    const pendingInputs = new Set<string>();
    const flush = async () => {
      for (const entry of manager.getEntries())
        if (entry.type === "message" && entry.message.role === "toolResult") {
          const result = completed.get(entry.message.toolCallId);
          if (result) {
            host.resultApplied(entry.message.toolCallId, result);
            completed.delete(entry.message.toolCallId);
          }
        }
      models.check();
      const modelUpdates = models.updates();
      const inputs = [...pendingInputs];
      const messages = manager.getEntries().filter((entry) =>
        !published.has(entry.id) &&
        entry.type === "message" &&
        entry.message.role === "assistant",
      );
      await host.boundary(
        {
          modelUpdates,
          inputApplications: inputs.map((inputId) => ({
            inputId,
            status: "applied",
            position: { nativeCursor: "pending" },
          })),
        },
        undefined,
        messages.map((entry) => assistantMessageFact(
          host.turnId,
          messageIdentity(entry.type === "message" ? entry.message : null, entry.id),
          (entry.type === "message" ? entry.message : null) as unknown as Json,
        )),
      );
      for (const entry of messages) published.add(entry.id);
      for (const input of inputs) pendingInputs.delete(input);
    };
    models = new ManagedModels(
      host,
      config.maxModelCalls,
      flush,
      config.model.parameters,
      false,
      (config.model.requestTimeoutSeconds ?? 180) * 1000,
    );
    const extension: ExtensionFactory = (api) => {
      if (process.env.MANAGED_AGENTS_EVALUATION_CONTEXT_MODE === "short" && config.system !== undefined) {
        // Public PI hook replaces the complete prompt, including its implicit cwd section.
        api.on("before_agent_start", () => ({ systemPrompt: config.system }));
      }
      // Only the main assistant event path is public. Compaction uses the same
      // model runtime but must not publish its summary as an assistant answer.
      api.on("message_update", (event) => {
        models.preview(event.assistantMessageEvent);
      });
      api.on("message_end", async (event) => {
        if (event.message.role === "assistant")
          await models.finishPreview(event.message);
        if (event.message.role === "user") {
          promptAccepted = true;
          if (applyingInput) pendingInputs.add(applyingInput);
        }
      });
    };
    const customTools: ToolDefinition[] = config.tools.map((tool) => ({
      name: tool.name,
      label: tool.name,
      description: tool.description,
      parameters: Type.Unsafe(tool.parameters),
      executionMode: "sequential",
      execute: async (callId, args) =>
        models.protect(async () => {
          await flush();
          const result = await host.invokeTool(
            callId,
            tool,
            args as Json,
            config.toolConfiguration,
          );
          completed.set(callId, result);
          return {
            content: [
              {
                type: "text",
                text: JSON.stringify(await host.resultContent(result)),
              },
            ],
            details: { resultId: result.resultId },
            isError: result.status !== "succeeded",
          };
        }),
    }));
    const loader = new DefaultResourceLoader({
      cwd: config.cwd,
      agentDir: config.cwd,
      noExtensions: true,
      noSkills: true,
      noContextFiles: true,
      noPromptTemplates: true,
      noThemes: true,
      systemPrompt: config.system,
      disabledBuiltinExtensions: ["mcp"],
      extensionFactories: [extension],
    });
    await loader.reload();
    const modelRuntime = models.wrap(
      await configureModel(
        await (this.dependencies.createModels?.() ??
          createManagedModelRuntime()),
        config.model,
      ),
    );
    const model = modelRuntime.getModel(config.model.provider, config.model.id);
    if (!model) throw new TurnRejected("Configured PI model is unavailable");
    const { session } = await createAgentSession({
      cwd: config.cwd,
      agentDir: config.cwd,
      modelRuntime,
      model,
      resourceLoader: loader,
      sessionManager: manager,
      noTools: "builtin",
      tools: config.tools.map((tool) => tool.name),
      customTools,
      settingsManager: SettingsManager.inMemory({ cacheWarming: "off", retry: { enabled: false },
        ...(process.env.MANAGED_AGENTS_EVALUATION_CONTEXT_MODE === "short"
          ? { compaction: { enabled: false } } : {}),
      }),
    });
    const abort = () => {
      void session.abort().catch(() => {});
    };
    models.signal.addEventListener("abort", abort, { once: true });
    try {
      try {
        models.check();
        if (host.cancelled) {
          /* Settle already accepted cancellation without dispatch. */
        } else if (!promptAccepted) await session.prompt(config.prompt);
        else {
          // PI's SessionManager restores Context. Repair only unanswered native tool-call records,
          // always using the original call ID and the platform's accepted result.
          const messages = manager.buildSessionContext().messages;
          const last = messages.at(-1);
          const answered = new Set(
            messages
              .filter((message) => message.role === "toolResult")
              .map((message) => message.toolCallId),
          );
          const assistant = [...messages]
            .reverse()
            .find((message) => message.role === "assistant");
          if (assistant?.role === "assistant")
            for (const block of assistant.content)
              if (block.type === "toolCall" && !answered.has(block.id)) {
                const tool = config.tools.find(
                  (tool) => tool.name === block.name,
                );
                if (!tool) throw new Error("Recovered tool is not admitted");
                let result: ToolResult;
                try {
                  result = await host.invokeTool(
                    block.id,
                    tool,
                    block.arguments as Json,
                    config.toolConfiguration,
                  );
                } catch (error) {
                  if (!(error instanceof ToolDenied)) throw error;
                  // Match the SDK's live tool-error path. Denial never authorizes Hands
                  // and consequently must not claim a platform result was applied.
                  manager.appendMessage({
                    role: "toolResult",
                    toolCallId: block.id,
                    toolName: block.name,
                    content: [{ type: "text", text: error.message }],
                    isError: true,
                    timestamp: Date.now(),
                  });
                  continue;
                }
                manager.appendMessage({
                  role: "toolResult",
                  toolCallId: block.id,
                  toolName: block.name,
                  content: [
                    {
                      type: "text",
                      text: JSON.stringify(await host.resultContent(result)),
                    },
                  ],
                  isError: result.status !== "succeeded",
                  timestamp: Date.now(),
                });
                host.resultApplied(block.id, result);
              }
          await flush();
          session.agent.state.messages = manager.buildSessionContext().messages;
          if (
            last?.role !== "assistant" ||
            (assistant?.role === "assistant" &&
              assistant.content.some((block) => block.type === "toolCall"))
          )
            await session.agent.continue();
        }
      } catch (error) {
        if (!host.cancelled || host.signal.aborted) throw error;
      }
      models.check();
      await flush();
      await host.closeInputs();
      for (const input of await host.inputs()) {
        if (input.type === "cancel") {
          manager.appendCustomEntry("managed-cancel", {
            inputId: input.inputId,
          });
          pendingInputs.add(input.inputId);
          await flush();
        } else if (input.type === "message" && !host.cancelled) {
          applyingInput = input.inputId;
          try {
            await session.prompt(
              typeof input.content.inline === "string"
                ? input.content.inline
                : JSON.stringify(input.content.inline),
            );
          } catch (error) {
            if (!host.cancelled || host.signal.aborted) throw error;
          }
          applyingInput = undefined;
          await flush();
        } else
          await host.rejectInput(
            input.inputId,
            host.cancelled
              ? "cancelled"
              : "No pending interaction matches the input",
          );
      }
      const finalEntry = [...manager.getBranch()].reverse()
        .find((entry) => entry.type === "message");
      if (host.cancelled || finalEntry?.type !== "message" || finalEntry.message.role !== "assistant")
        return [];
      host.outputMessageId = messageIdentity(finalEntry.message, finalEntry.id);
      return assistantOutput(finalEntry.message as unknown as Json);
    } catch (error) {
      models.check();
      throw error;
    } finally {
      models.closePreviews();
      models.signal.removeEventListener("abort", abort);
      await session.abort();
      session.dispose();
    }
  }
}
