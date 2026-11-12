import type { ModelRuntime } from "@earendil-works/pi-coding-agent";
import {
  createAssistantMessageEventStream,
  type AssistantMessageEventStream,
  type AssistantMessage,
  type Api,
  type Context,
  type Model,
  type ModelsSimpleStreamOptions,
  type AssistantMessageEvent,
} from "@earendil-works/pi-ai";
import {
  ToolDenied,
  TurnRejected,
  type Json,
  type RunHost,
  type RunConfiguration,
  type TextPreview,
} from "@managed-agents/harness-runtime";

export interface PiDependencies {
  createModels?: () => Promise<ModelRuntime>;
}

type ManagedMessage = { managed_agents?: { model_call_id: string; message_id: string } };
export function messageIdentity(message: unknown, fallback: string): string {
  const metadata = (message as ManagedMessage | undefined)?.managed_agents;
  return typeof metadata?.message_id === "string" ? metadata.message_id : fallback;
}

/** One gate for inference AND auxiliary requests (including native compaction).
 * SDK event handlers are notifications and may swallow exceptions. Never admit there.
 */
export class ManagedModels {
  private failure: unknown;
  private readonly stop = new AbortController();
  readonly failureSignal = this.stop.signal;
  private pending: Json[] = [];
  readonly signal: AbortSignal;
  private readonly previews = new Map<string, TextPreview>();

  constructor(
    private readonly host: RunHost,
    private readonly maximum: number | undefined,
    private readonly beforeRequest: () => Promise<void> = async () => {},
    private readonly parameters: RunConfiguration["model"]["parameters"] = {},
    private readonly previewStreams = false,
    private readonly requestTimeoutMs = 180_000,
  ) {
    if (!Number.isSafeInteger(requestTimeoutMs) || requestTimeoutMs <= 0)
      throw new TurnRejected("Model request timeout must be a positive integer");
    this.signal = AbortSignal.any([host.workSignal, this.stop.signal]);
  }

  check(): void {
    if (this.failure) throw this.failure;
    this.host.check();
  }

  /** Propagate infrastructure failures even if the kernel turns them into tool errors. */
  async protect<T>(operation: () => Promise<T>): Promise<T> {
    this.check();
    try {
      return await operation();
    } catch (error) {
      if (
        !(error instanceof ToolDenied) &&
        !this.host.cancelled &&
        !this.host.signal.aborted
      ) {
        this.failure ??= error;
        this.stop.abort(error);
      }
      throw error;
    }
  }

  updates(): Json[] {
    const updates = this.pending;
    this.pending = [];
    return updates;
  }

  preview(event: AssistantMessageEvent): void {
    if (event.type !== "text_delta") return;
    const metadata = (event.partial as ManagedMessage).managed_agents;
    if (!metadata) return;
    let preview = this.previews.get(metadata.model_call_id);
    if (!preview) {
      preview = this.host.textPreview(metadata.model_call_id, metadata.message_id);
      this.previews.set(metadata.model_call_id, preview);
    }
    preview.accept(event.delta);
  }

  async finishPreview(message: unknown): Promise<void> {
    const id = (message as ManagedMessage | undefined)?.managed_agents?.model_call_id;
    if (!id) return;
    await this.previews.get(id)?.finish();
    this.previews.delete(id);
  }

  closePreviews(): void {
    this.previews.forEach((preview) => preview.close());
    this.previews.clear();
  }

  wrap(runtime: ModelRuntime): ModelRuntime {
    const stream = (
      simple: boolean,
      model: Model<Api>,
      context: Context,
      options?: ModelsSimpleStreamOptions,
      preview = this.previewStreams,
    ) => {
      const { topP, parallelToolCalls, toolChoice, ...nativeParameters } = this.parameters ?? {};
      options = {
        ...options,
        ...nativeParameters,
        maxRetries: 0,
        timeoutMs: this.requestTimeoutMs,
        ...(this.parameters?.maxTokens === undefined
          ? {}
          : {
              maxTokens: Math.min(
                this.parameters.maxTokens,
                options?.maxTokens ?? this.parameters.maxTokens,
              ),
            }),
      };
      const priorPayload = options.onPayload;
      options.onPayload = async (payload, selectedModel) => {
        const prior = priorPayload ? await priorPayload(payload, selectedModel) : undefined;
        return managedRequestParameters(prior ?? payload, this.parameters, selectedModel.api);
      };
      return this.stream(model, context, options, preview, (signal) =>
        simple
          ? runtime.streamSimple(model, context, { ...options, signal })
          : runtime.stream(model, context, { ...options, signal }),
      );
    };
    return new Proxy(runtime, {
      get: (target, key) => {
        if (key === "streamSimple" || key === "stream")
          return (
            model: Model<Api>,
            context: Context,
            options?: ModelsSimpleStreamOptions,
          ) => stream(key === "streamSimple", model, context, options);
        if (key === "completeSimple" || key === "complete")
          return (
            model: Model<Api>,
            context: Context,
            options?: ModelsSimpleStreamOptions,
          ) =>
            stream(key === "completeSimple", model, context, options, false).result();
        // These paths require their own platform result/usage contracts. Never bypass admission.
        if (
          [
            "streamDeferred",
            "fetchDeferred",
            "generateImages",
            "classify",
          ].includes(String(key))
        )
          return () => {
            const error = new TurnRejected(
              `Unsupported managed PI model operation: ${String(key)}`,
            );
            this.failure ??= error;
            this.stop.abort(error);
            throw error;
          };
        const value = Reflect.get(target, key, target);
        return typeof value === "function" ? value.bind(target) : value;
      },
    });
  }

  private stream(
    model: Model<Api>,
    context: Context,
    options: ModelsSimpleStreamOptions | undefined,
    preview: boolean,
    dispatch: (signal: AbortSignal) => AssistantMessageEventStream,
  ): AssistantMessageEventStream {
    const output = createAssistantMessageEventStream();
    let timeout: AbortSignal | undefined;
    let signal = AbortSignal.any([this.signal, ...(options?.signal ? [options.signal] : [])]);
    void (async () => {
      try {
        this.check();
        signal.throwIfAborted();
        await this.protect(this.beforeRequest);
        // Persist provider/model and model-visible request, never credentials or callbacks.
        const request = JSON.parse(
          JSON.stringify({
            provider: model.provider,
            model: model.id,
            context,
            parameters: this.parameters,
            requestTimeoutSeconds: this.requestTimeoutMs / 1000,
            ...(options?.temperature === undefined
              ? {}
              : { temperature: options.temperature }),
            ...(options?.maxTokens === undefined
              ? {}
              : { maxTokens: options.maxTokens }),
            ...(options?.reasoning === undefined
              ? {}
              : { reasoning: options.reasoning }),
          }),
        ) as Json;
        const id = await this.protect(() =>
          this.host.modelStart(model.id, request, this.maximum),
        );
        signal.throwIfAborted();
        // Match Java/Rust: request timeout starts at provider dispatch, after platform admission.
        timeout = AbortSignal.timeout(this.requestTimeoutMs);
        signal = AbortSignal.any([signal, timeout]);
        const input = dispatch(signal);
        let ended = false;
        for await (const event of input) {
          const message = event.type === "done" ? event.message : event.type === "error" ? event.error : event.partial;
          // Adapter-owned metadata survives the native JSON log/storage. Providers
          // continue to receive the kernel's normal role/content conversion.
          Object.assign(message, { managed_agents: { model_call_id: id, message_id: `message_${id}` } });
          if (preview) this.preview(event);
          if (event.type === "done" || event.type === "error") {
            if (preview) await this.finishPreview(message);
            this.pending.push(
              await this.protect(() =>
                this.host.modelCompletion(
                  id,
                  message as unknown as Json,
                  message.usage,
                ),
              ),
            );
            ended = true;
          }
          output.push(event);
        }
        timeout.throwIfAborted();
        if (!ended)
          throw new Error("PI model stream ended without a terminal response");
      } catch (error) {
        if (!signal.aborted || timeout?.aborted) {
          this.failure ??= error;
          this.stop.abort(error);
        }
        // Native kernels understand aborted responses and will stop retrying. The original
        // failure is rethrown by check() before any adapter can report success.
        const message: AssistantMessage = {
          role: "assistant",
          content: [],
          api: model.api,
          provider: model.provider,
          model: model.id,
          stopReason: "aborted",
          timestamp: Date.now(),
          errorMessage: "Managed model execution stopped",
          usage: {
            input: 0,
            output: 0,
            cacheRead: 0,
            cacheWrite: 0,
            totalTokens: 0,
            cost: {
              input: 0,
              output: 0,
              cacheRead: 0,
              cacheWrite: 0,
              total: 0,
            },
          },
        };
        output.push({ type: "error", reason: "aborted", error: message });
      } finally {
        output.end();
      }
    })();
    return output;
  }
}

/** Runs after provider serialization, before HTTP dispatch; sealed values take precedence. */
export function managedRequestParameters(
  payload: unknown,
  parameters: RunConfiguration["model"]["parameters"] = {},
  api?: Api,
): unknown {
  if (!payload || typeof payload !== "object" || Array.isArray(payload))
    throw new TurnRejected("Invalid model request payload");
  const request = { ...payload } as Record<string, unknown>;
  if (parameters.topP !== undefined) request.top_p = parameters.topP;
  if (parameters.parallelToolCalls !== undefined)
    request.parallel_tool_calls = parameters.parallelToolCalls;
  if (parameters.toolChoice !== undefined) request.tool_choice = parameters.toolChoice;
  // Responses SDK defaults summary=auto. The sealed effort does not authorize
  // an additional summary output mode; preserve the common request contract.
  if (api === "openai-responses" && parameters.reasoning !== undefined)
    request.reasoning = { effort: parameters.reasoning };
  return request;
}
