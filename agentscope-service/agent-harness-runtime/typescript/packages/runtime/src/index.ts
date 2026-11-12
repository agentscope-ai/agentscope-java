import { NativeSnapshots, type SnapshotArray } from "./snapshot.js";
import { loadRecovery } from "./recovery.js";
import { TextPreview } from "./preview.js";
export { TextPreview } from "./preview.js";
import { createHash, randomUUID } from "node:crypto";
import { createRequire } from "node:module";
const canonicalize: (value: unknown) => string | undefined = createRequire(
  import.meta.url,
)("canonicalize");

export type Json =
  | null
  | boolean
  | number
  | string
  | Json[]
  | { [key: string]: Json };
export interface Scope {
  tenant: string;
  namespace: string;
  sessionId: string;
}
export interface Owner {
  instanceId: string;
  incarnationId: string;
}
export interface Fence {
  owner: Owner;
  epoch: string;
  executionToken: string;
}
export interface ObjectRef {
  scope: Scope;
  backendId: string;
  key: string;
  sha256: string;
  size: string;
  mediaType: string;
  formatVersion: string;
}
export interface NativeState {
  harnessType: string;
  stateFormatVersion: string;
  nativeCursor: string;
  checkpointRef?: ObjectRef;
  journalRefs?: ObjectRef[];
}
export interface ToolOperation {
  operationId: string;
  taskId: string;
  executionId: string;
  nativeCallIds: string[];
  toolName: string;
  arguments: { inline: Json };
  configurationRef: ObjectRef;
}
export interface ToolResult {
  resultId: string;
  taskId: string;
  executionId: string;
  status: "succeeded" | "failed" | "cancelled";
  content: { inline?: Json; ref?: ObjectRef };
  artifacts: ObjectRef[];
  output: {
    mode: string;
    throughSourceSeq: string;
    byteLength: string;
    gapEventIds?: string[];
  };
}
export interface Changes {
  toolCalls?: { nativeCallId: string; position: { nativeCursor: string } }[];
  toolOperations?: ToolOperation[];
  resultApplications?: {
    nativeCallId: string;
    resultIds: string[];
    position: { nativeCursor: string };
  }[];
  inputApplications?: Json[];
  modelUpdates?: Json[];
  interactionUpdates?: Json[];
  suspension?: Json;
  terminal?: { status: string; output: { inline: Json } | { ref: ObjectRef }; messageId?: string };
}
export interface Recovery {
  brainRevision: string;
  nativeState?: NativeState;
  acceptedResults: ToolResult[];
  toolOperations: ToolOperation[];
  resultApplications: { nativeCallId: string; resultIds: string[] }[];
  inputApplications: { inputId: string; status: string }[];
  pendingInputs: {
    inputId: string;
    type: string;
    content: { inline: Json };
    actionId?: string;
  }[];
  interactionUpdates: Json[];
  modelUpdates: Json[];
  terminal?: Json;
}
export interface CommitReceipt {
  commitId: string;
  contentHash: string;
  brainRevision: string;
  nativeCursor: string;
  outcome: "committed";
}
export interface ToolDescriptor {
  name: string;
  operation: string;
  description: string;
  parameters: Record<string, unknown>;
  permission?: string;
  argumentEnvelope?: Record<string, Json>;
}
export interface RunConfiguration {
  prompt: string;
  system?: string;
  maxModelCalls?: number;
  /** Time to wait inline before committing a suspension; zero still checks once. */
  inlineWaitMs?: number;
  cwd: string;
  model: {
    provider: string;
    id: string;
    api?: "openai-completions" | "openai-responses" | "anthropic-messages";
    cost?: {
      input: number;
      output: number;
      cacheRead: number;
      cacheWrite: number;
    };
    baseUrl?: string;
    credentialEnv?: string;
    contextWindow?: number;
    outputLimit?: number;
    reasoning?: boolean;
    requestTimeoutSeconds?: number;
    parameters?: {
      temperature?: number;
      topP?: number;
      parallelToolCalls?: boolean;
      toolChoice?: "auto" | "none" | "required";
      maxTokens?: number;
      reasoning?: "minimal" | "low" | "medium" | "high" | "xhigh";
    };
  };
  toolConfiguration: ObjectRef;
  tools: ToolDescriptor[];
}
export interface HarnessAdapter {
  readonly type: string;
  readonly stateFormat: string;
  readonly recoveryPageSize?: number;
  run(host: RunHost, configuration: RunConfiguration): Promise<Json>;
}

export function sha256(value: Uint8Array | string): string {
  return createHash("sha256").update(value).digest("hex");
}
export function canonicalHash(value: unknown): string {
  const json = canonicalize(value);
  if (json === undefined) throw new Error("Invalid JSON");
  return sha256(json);
}
/** Java String.length and JavaScript String.length both count UTF-16 code units. */
export function fingerprint(...values: string[]): string {
  return sha256(values.map((value) => `${value.length}:${value}`).join(""));
}
export function scopeKey(scope: Scope): string {
  return Buffer.from(
    fingerprint(scope.tenant, scope.namespace, scope.sessionId),
    "hex",
  ).toString("base64url");
}
export function taskIdentity(
  scope: Scope,
  turnId: string,
  operationId: string,
): string {
  return `tool_${fingerprint(scopeKey(scope), turnId, operationId)}`;
}
export class ServiceError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
  ) {
    super(code);
  }
}
export class UnconfirmedResponse extends Error {}
export class TurnRejected extends Error {}
export class ToolDenied extends Error {}
export class ExecutionSuspended extends Error {}

export class SessionClient {
  readonly base: URL;
  constructor(
    url: string,
    private readonly token: string,
    private readonly timeoutMs = 30_000,
  ) {
    this.base = new URL(url.replace(/\/+$/, "") + "/");
    if (
      !["http:", "https:"].includes(this.base.protocol) ||
      this.base.username ||
      this.base.password ||
      !token
    )
      throw new Error("Session Service URL and credential required");
  }
  async post<T>(
    operation: string,
    body: unknown,
    signal?: AbortSignal,
  ): Promise<T> {
    if (!/^[a-z][a-z/-]*$/.test(operation))
      throw new Error("Invalid operation");
    const controller = AbortSignal.timeout(this.timeoutMs);
    let response: Response;
    try {
      response = await fetch(new URL(operation, this.base), {
        method: "POST",
        redirect: "error",
        headers: {
          authorization: `Bearer ${this.token}`,
          "content-type": "application/json",
        },
        body: JSON.stringify(body),
        signal: signal ? AbortSignal.any([signal, controller]) : controller,
      });
    } catch (error) {
      throw new UnconfirmedResponse(
        "Session Service response was not confirmed",
        { cause: error },
      );
    }
    let data: unknown;
    try {
      const reader = response.body?.getReader();
      if (!reader) throw new Error("Missing response body");
      const chunks: Uint8Array[] = [];
      let size = 0;
      try {
        while (true) {
          const next = await reader.read();
          if (next.done) break;
          size += next.value.length;
          if (size > 90 * 1024 * 1024) throw new Error("Response limit");
          chunks.push(next.value);
        }
      } catch (error) {
        await reader.cancel();
        throw error;
      } finally {
        reader.releaseLock();
      }
      data = JSON.parse(Buffer.concat(chunks, size).toString("utf8"));
    } catch (error) {
      if (
        response.status >= 400 &&
        response.status < 500 &&
        ![408, 429].includes(response.status)
      )
        throw new ServiceError(response.status, `http_${response.status}`);
      throw new UnconfirmedResponse(
        "Invalid or incomplete Session Service response",
        { cause: error },
      );
    }
    // A proxy/server may fail after the transaction committed. Only the original
    // receipt can distinguish rejection from a lost acknowledgement.
    if (response.status >= 500 || [408, 429].includes(response.status))
      throw new UnconfirmedResponse(`Unconfirmed HTTP ${response.status}`);
    if (!response.ok)
      throw new ServiceError(
        response.status,
        (data as { code?: string }).code ?? "service_error",
      );
    return data as T;
  }
  async commit(
    request: unknown & { scope: Scope; turnId: string; commitId: string },
    signal?: AbortSignal,
  ): Promise<CommitReceipt> {
    const hash = canonicalHash(request);
    let receipt: CommitReceipt;
    try {
      receipt = await this.post("commits", request, signal);
    } catch (error) {
      if (!(error instanceof UnconfirmedResponse)) throw error;
      try {
        receipt = await this.post(
          "commits/receipt",
          {
            protocolVersion: "0.1.0",
            scope: request.scope,
            turnId: request.turnId,
            commitId: request.commitId,
          },
          signal,
        );
      } catch (missing) {
        if (
          !(missing instanceof ServiceError) ||
          missing.code !== "receipt_not_found"
        )
          throw missing;
        receipt = await this.post("commits", request, signal); // retry identical bytes/ID, never a replacement authorization
      }
    }
    if (
      receipt.outcome !== "committed" ||
      receipt.contentHash !== hash ||
      receipt.commitId !== request.commitId
    )
      throw new Error("Commit receipt differs from request");
    return receipt;
  }
  async put(
    scope: Scope,
    value: Uint8Array,
    formatVersion: string,
    signal?: AbortSignal,
  ): Promise<ObjectRef> {
    if (!value.length || value.length > 64 * 1024 * 1024)
      throw new Error("Native object exceeds limit");
    const hash = sha256(value);
    const result = await this.post<{ ref: ObjectRef }>(
      "objects/write",
      {
        protocolVersion: "0.1.0",
        scope,
        objectId: hash,
        sha256: hash,
        size: String(value.length),
        mediaType: "application/json",
        formatVersion,
        dataBase64: Buffer.from(value).toString("base64"),
      },
      signal,
    );
    if (
      result.ref.sha256 !== hash ||
      result.ref.size !== String(value.length) ||
      canonicalHash(result.ref.scope) !== canonicalHash(scope)
    )
      throw new Error("Staged object identity differs");
    return result.ref;
  }
  async read(ref: ObjectRef, signal?: AbortSignal): Promise<Uint8Array> {
    const result = await this.post<{ ref: ObjectRef; dataBase64: string }>(
      "objects/read",
      { protocolVersion: "0.1.0", ref },
      signal,
    );
    const bytes = Buffer.from(result.dataBase64, "base64");
    if (
      canonicalHash(ref) !== canonicalHash(result.ref) ||
      String(bytes.length) !== ref.size ||
      sha256(bytes) !== ref.sha256
    )
      throw new Error("Native object integrity failure");
    return bytes;
  }
}

/** One serial commit lane per execution. Native SDKs may remain concurrent outside this lane. */
export class RunHost {
  /** Native identity of the selected final message, also used by durable public facts. */
  outputMessageId?: string;
  private lane: Promise<unknown> = Promise.resolve();
  private revision: bigint;
  private cursor = 0n;
  private snapshot: (() => Promise<Json>) | undefined;
  private snapshotArray?: SnapshotArray;
  private snapshots?: NativeSnapshots;
  private nativeSnapshots(): NativeSnapshots {
    return (this.snapshots ??= new NativeSnapshots(
      this.client,
      this.scope,
      this.stateFormat,
      this.signal,
    ));
  }
  private readonly outcomes = new Map<string, ToolResult>();
  private readonly authorized = new Map<string, ToolOperation>();
  private readonly applied = new Set<string>();
  private readonly inflight = new Map<
    string,
    { hash: string; result: Promise<ToolResult> }
  >();
  private readonly queuedApplications = new Map<string, ToolResult>();
  private readonly controller = new AbortController();
  private renewal?: NodeJS.Timeout;
  private renewing = false;
  private inputPoll?: NodeJS.Timeout;
  private inputPolling = false;
  private cancelling = false;
  private readonly userCancellation = new AbortController();
  private cancelListeners = new Set<() => void>();
  private modelCalls = 0;
  private modelFailed = false;
  private suspended = false;
  private releasing = false;
  private completed = false;
  get isSuspended(): boolean {
    return this.suspended;
  }
  get cancelled(): boolean {
    return this.cancelling;
  }
  onCancel(listener: () => void): () => void {
    this.cancelListeners.add(listener);
    if (this.cancelling) listener();
    return () => this.cancelListeners.delete(listener);
  }
  async inputs(): Promise<Recovery["pendingInputs"]> {
    const value = await this.client.post<{
      pendingInputs: Recovery["pendingInputs"];
    }>("inputs/read", this.identity(), this.signal);
    if (
      value.pendingInputs.some((input) => input.type === "cancel") &&
      !this.cancelling
    ) {
      this.cancelling = true;
      this.userCancellation.abort(new Error("Execution cancelled by user"));
      for (const listener of this.cancelListeners) listener();
    }
    return value.pendingInputs;
  }
  async closeInputs(): Promise<void> {
    await this.client.post("inputs/close", this.identity(), this.signal);
  }
  async inputApplied(inputId: string): Promise<void> {
    await this.boundary({
      inputApplications: [
        {
          inputId,
          status: "applied",
          position: { nativeCursor: String(this.cursor + 1n) },
        },
      ],
    });
  }
  async rejectInput(inputId: string, reason: string): Promise<void> {
    await this.boundary({
      inputApplications: [{ inputId, status: "rejected", reason }],
    });
  }

  private deadline: number;
  readonly signal = this.controller.signal;
  /** Stops inference/waits while leaving the ownership-fenced settlement channel open. */
  readonly workSignal = AbortSignal.any([
    this.signal,
    this.userCancellation.signal,
  ]);
  constructor(
    readonly client: SessionClient,
    readonly scope: Scope,
    readonly turnId: string,
    readonly fence: Fence,
    readonly recovery: Recovery,
    readonly harnessType: string,
    readonly stateFormat: string,
    expiresAt: string,
    private readonly inlineWaitMs = 1000,
  ) {
    if (
      !Number.isFinite(inlineWaitMs) ||
      inlineWaitMs < 0 ||
      inlineWaitMs > 600_000
    )
      throw new Error("inlineWaitMs must be 0..600000");
    this.revision = BigInt(recovery.brainRevision);
    this.cursor = this.revision;
    this.deadline =
      performance.now() +
      Math.max(0, Date.parse(expiresAt) - Date.now() - 1000);
    for (const result of recovery.acceptedResults)
      this.outcomes.set(result.taskId, result);
    for (const operation of recovery.toolOperations)
      this.authorized.set(operation.operationId, operation);
    for (const result of recovery.resultApplications)
      this.applied.add(result.nativeCallId);
    if (
      recovery.nativeState &&
      (recovery.nativeState.harnessType !== harnessType ||
        recovery.nativeState.stateFormatVersion !== stateFormat)
    )
      throw new ServiceError(409, "native_format_mismatch");
  }
  check(): void {
    this.signal.throwIfAborted();
    if (this.completed) throw new Error("Execution already completed");
    if (performance.now() >= this.deadline) {
      this.abort(new ServiceError(409, "lease_expired"));
      this.signal.throwIfAborted();
    }
  }
  abort(reason: unknown): void {
    if (this.renewal) clearInterval(this.renewal);
    if (this.inputPoll) clearInterval(this.inputPoll);
    this.controller.abort(reason);
  }
  async startRenewal(): Promise<void> {
    // Renew before touching a kernel. Initial claim may be old and clocks may differ.
    await this.renew();
    await this.inputs();
    this.renewal = setInterval(() => {
      if (this.renewing || this.releasing) return;
      this.renewing = true;
      this.renew()
        .catch((error) => this.backgroundFailure(error))
        .finally(() => {
          this.renewing = false;
        });
    }, 10_000);
    this.renewal.unref();
    this.inputPoll = setInterval(() => {
      if (this.inputPolling || this.releasing) return;
      this.inputPolling = true;
      this.inputs()
        .catch((error) => this.backgroundFailure(error))
        .finally(() => {
          this.inputPolling = false;
        });
    }, 1000);
    this.inputPoll.unref();
  }
  private backgroundFailure(error: unknown): void {
    // A terminal/suspension commit releases the fence before its receipt reaches us.
    // An already in-flight poll may observe that release. Only the commit/receipt
    // exchange can establish its outcome; aborting it here loses that evidence.
    // No new native work is allowed in this interval. Failed commits still abort
    // through the serial lane, and external cancellation remains effective.
    if (!this.releasing) this.abort(error);
  }
  private async renew(): Promise<void> {
    const started = performance.now();
    const result = await this.client.post<{
      expiresAt: string;
      serverTime: string;
    }>(
      "executions/renew",
      { ...this.identity(), renewalId: randomUUID() },
      this.signal,
    );
    const duration =
      Date.parse(result.expiresAt) - Date.parse(result.serverTime);
    if (!Number.isFinite(duration) || duration <= 1000)
      throw new Error("Invalid lease receipt");
    this.deadline = started + duration - 1000;
    this.check();
  }
  close(): void {
    if (this.renewal) clearInterval(this.renewal);
    if (this.inputPoll) clearInterval(this.inputPoll);
    this.abort(new Error("Execution closed"));
  }
  identity(): {
    protocolVersion: string;
    scope: Scope;
    turnId: string;
    fence: Fence;
  } {
    return {
      protocolVersion: "0.1.0",
      scope: this.scope,
      turnId: this.turnId,
      fence: this.fence,
    };
  }
  useSnapshot(snapshot: () => Promise<Json>, array?: SnapshotArray): void {
    this.snapshot = snapshot;
    this.snapshotArray = array;
  }
  textPreview(modelCallId: string, messageId: string): TextPreview {
    return new TextPreview(this.client, this.identity(), modelCallId, messageId, this.workSignal);
  }
  async restore(): Promise<Json | undefined> {
    const state = this.recovery.nativeState;
    if (!state) return undefined;
    return this.nativeSnapshots().read(state);
  }

  async boundary(changes: Changes = {}, native?: Json, publicFacts: Json[] = []): Promise<void> {
    const execute = async () => {
      this.check();
      const applications = [...this.queuedApplications];
      const capture = native ?? (await this.requireSnapshot());
      const references = await this.nativeSnapshots().write(
        capture,
        this.snapshotArray,
      );
      const cursor = String(++this.cursor);
      const effective = structuredClone(changes);
      // A queued commit receives its actual native cursor, not the caller's earlier estimate.
      for (const entries of [
        effective.toolCalls,
        effective.resultApplications,
        effective.inputApplications,
        effective.modelUpdates,
        effective.interactionUpdates,
      ])
        for (const item of entries ?? [])
          if (
            item &&
            typeof item === "object" &&
            !Array.isArray(item) &&
            "position" in item
          )
            (item as { position: { nativeCursor: string } }).position = {
              nativeCursor: cursor,
            };
      // Results are marked applied only after the SDK has inserted them in the captured native state.
      for (const [call, result] of applications) {
        (effective.resultApplications ??= []).push({
          nativeCallId: call,
          resultIds: [result.resultId],
          position: { nativeCursor: cursor },
        });
      }
      const request = {
        ...this.identity(),
        commitId: randomUUID(),
        expectedBrainRevision: String(this.revision),
        nativeState: {
          harnessType: this.harnessType,
          stateFormatVersion: this.stateFormat,
          nativeCursor: cursor,
          ...references,
        },
        changes: effective,
        ...(publicFacts.length ? { publicFacts: await this.encodePublicFacts(publicFacts) } : {}),
      };
      if (effective.suspension || effective.terminal) this.releasing = true;
      const receipt = await this.client.commit(request, this.signal);
      this.revision = BigInt(receipt.brainRevision);
      for (const [call, result] of applications)
        if (this.queuedApplications.get(call) === result)
          this.queuedApplications.delete(call);
      if (effective.suspension) {
        // The receipt released ownership. Freeze immediately, before another queued
        // native write can turn the intentional interruption into a tool failure.
        this.suspended = true;
        this.abort(new ExecutionSuspended("Execution durably suspended"));
      }
      if (effective.terminal) {
        this.completed = true;
        return;
      }
      this.check();
    };
    const next = this.lane.then(execute);
    this.lane = next.catch((error) => {
      this.abort(error);
    });
    return next;
  }
  private async encodePublicFacts(facts: Json[]): Promise<Json[]> {
    let remaining = 128 * 1024;
    const result = structuredClone(facts);
    for (const fact of result) {
      if (!fact || typeof fact !== "object" || Array.isArray(fact)) throw new Error("Invalid public fact");
      const content = fact.content;
      if (!content || typeof content !== "object" || Array.isArray(content) || !("inline" in content)) continue;
      const bytes = Buffer.from(JSON.stringify(content.inline));
      if (bytes.length <= Math.min(16 * 1024, remaining)) remaining -= bytes.length;
      else fact.content = { ref: await this.client.put(this.scope, bytes, "public-fact-v1", this.signal) as unknown as Json };
    }
    return result;
  }
  private requireSnapshot(): Promise<Json> {
    if (!this.snapshot) throw new Error("Native snapshot provider missing");
    return this.snapshot();
  }
  /** Called by the adapter only after the corresponding native tool-result record exists. */
  resultApplied(nativeCallId: string, result: ToolResult): void {
    if (!this.applied.has(nativeCallId)) {
      this.queuedApplications.set(nativeCallId, result);
      this.applied.add(nativeCallId);
    }
  }
  async invokeTool(
    nativeCallId: string,
    tool: ToolDescriptor,
    args: Json,
    configurationRef: ObjectRef,
  ): Promise<ToolResult> {
    if (this.cancelled && !this.authorized.has(nativeCallId))
      throw new Error("Execution cancelled");
    if (tool.permission && tool.permission !== "always_allow") {
      if (tool.permission !== "always_ask")
        throw new ToolDenied("Tool policy does not allow execution");
      const actionId = `confirm_${fingerprint(this.turnId, nativeCallId)}`;
      const prior = this.recovery.interactionUpdates.find(
        (update) =>
          typeof update === "object" &&
          update !== null &&
          !Array.isArray(update) &&
          update.actionId === actionId &&
          update.phase === "resolved",
      );
      if (prior && (prior as { decision?: string }).decision !== "allow")
        throw new ToolDenied("Tool approval was not granted");
      if (!prior && !this.authorized.has(nativeCallId)) {
        const opened = this.recovery.interactionUpdates.some(
          (update) =>
            typeof update === "object" &&
            update !== null &&
            !Array.isArray(update) &&
            update.actionId === actionId,
        );
        if (!opened)
          await this.boundary({
            interactionUpdates: [
              {
                actionId,
                phase: "requested",
                actionType: "confirmation",
                request: {
                  inline: {
                    toolName: tool.name,
                    arguments: args,
                    nativeCallId,
                  },
                },
                position: { nativeCursor: String(this.cursor + 1n) },
              },
            ],
          });
        const started = performance.now();
        while (true) {
          const pending = await this.inputs();
          const reply = pending.find(
            (input) =>
              input.type === "interaction_response" &&
              input.actionId === actionId,
          );
          if (this.cancelled) throw new Error("Tool confirmation cancelled");
          if (reply) {
            const answer = reply.content.inline;
            // Unknown or malformed answers never grant permission.
            const approved =
              typeof answer === "object" &&
              answer !== null &&
              !Array.isArray(answer) &&
              (answer.approved === true || answer.decision === "allow");
            await this.boundary({
              inputApplications: [
                {
                  inputId: reply.inputId,
                  status: "applied",
                  position: { nativeCursor: String(this.cursor + 1n) },
                },
              ],
              interactionUpdates: [
                {
                  actionId,
                  phase: "resolved",
                  inputId: reply.inputId,
                  decision: approved ? "allow" : "deny",
                  position: { nativeCursor: String(this.cursor + 1n) },
                },
              ],
            });
            if (!approved) throw new ToolDenied("Tool approval denied");
            break;
          }
          if (performance.now() - started >= this.inlineWaitMs)
            await this.suspend([], [actionId]);
          await delay(250, this.workSignal);
        }
      }
    }
    const argumentsValue = tool.argumentEnvelope
      ? {
          ...tool.argumentEnvelope,
          arguments: args,
          meta: { "io.agentscope/toolCallId": nativeCallId },
        }
      : args;
    return this.executeTool(
      nativeCallId,
      tool.operation,
      argumentsValue,
      configurationRef,
    );
  }
  executeTool(
    nativeCallId: string,
    toolName: string,
    args: Json,
    configurationRef: ObjectRef,
  ): Promise<ToolResult> {
    const hash = canonicalHash({ toolName, args, configurationRef });
    const prior = this.inflight.get(nativeCallId);
    if (prior) {
      if (prior.hash !== hash)
        return Promise.reject(new Error("Concurrent tool identity changed"));
      return prior.result;
    }
    const result = this.executeOnce(
      nativeCallId,
      toolName,
      args,
      configurationRef,
    );
    this.inflight.set(nativeCallId, { hash, result });
    return result;
  }
  private async executeOnce(
    nativeCallId: string,
    toolName: string,
    args: Json,
    configurationRef: ObjectRef,
  ): Promise<ToolResult> {
    this.check();
    const operationId = nativeCallId;
    const taskId = taskIdentity(this.scope, this.turnId, operationId);
    const operation: ToolOperation = {
      operationId,
      taskId,
      executionId: taskId,
      nativeCallIds: [nativeCallId],
      toolName,
      arguments: { inline: args },
      configurationRef,
    };
    const prior = this.authorized.get(operationId);
    if (prior && canonicalHash(prior) !== canonicalHash(operation))
      throw new Error("Replayed tool identity changed");
    if (!prior) {
      await this.boundary({
        toolCalls: [
          {
            nativeCallId,
            position: { nativeCursor: String(this.cursor + 1n) },
          },
        ],
        toolOperations: [operation],
      });
      this.authorized.set(operationId, operation);
    }
    let result = this.outcomes.get(taskId);
    const started = performance.now();
    while (!result) {
      this.check();
      this.workSignal.throwIfAborted();
      const state = await this.client.post<{
        result?: ToolResult;
        state: string;
      }>(
        "tools/state",
        {
          protocolVersion: "0.1.0",
          scope: this.scope,
          turnId: this.turnId,
          taskId,
          executionId: taskId,
        },
        this.workSignal,
      );
      if (state.result) {
        result = state.result;
        this.outcomes.set(taskId, result);
        break;
      }
      // Unknown is not permission to run the external action again. Hands owns reconciliation.
      if (performance.now() - started >= this.inlineWaitMs)
        await this.suspend([taskId], []);
      await delay(250, this.workSignal);
    }
    return result;
  }
  private async suspend(
    taskIds: string[],
    actionIds: string[],
  ): Promise<never> {
    this.workSignal.throwIfAborted();
    await this.boundary({
      suspension: {
        suspensionId: `wait_${randomUUID()}`,
        pendingTaskIds: taskIds,
        pendingActionIds: actionIds,
        resumePhase: taskIds.length ? "WAIT_TOOLS" : "APPLY_INPUTS",
        waitPolicy: "all",
      },
    });
    throw new Error("Suspension did not stop execution");
  }
  async resultContent(result: ToolResult): Promise<Json> {
    if (result.content.inline !== undefined) return result.content.inline;
    if (!result.content.ref) throw new Error("Tool result has no content");
    return JSON.parse(
      Buffer.from(
        await this.client.read(result.content.ref, this.signal),
      ).toString("utf8"),
    ) as Json;
  }
  async modelStart(
    model: string,
    request: Json,
    maximum = 100,
  ): Promise<string> {
    if (this.cancelled) throw new Error("Execution cancelled");
    if (
      ++this.modelCalls +
        this.recovery.modelUpdates.filter(
          (update) =>
            typeof update === "object" &&
            update !== null &&
            !Array.isArray(update) &&
            update.phase === "dispatch",
        ).length >
      maximum
    )
      throw new TurnRejected("Model iteration limit reached");
    const id = `model_${randomUUID()}`;
    await this.client.post(
      "models/reserve",
      { ...this.identity(), modelCallId: id, model },
      this.signal,
    );
    const ref = await this.client.put(
      this.scope,
      Buffer.from(JSON.stringify(request)),
      "model-request-v1",
      this.signal,
    );
    await this.boundary({
      modelUpdates: [
        {
          modelCallId: id,
          phase: "dispatch",
          model,
          purpose: "reasoning",
          requestHash: canonicalHash(request),
          requestRef: ref as unknown as Json,
          position: { nativeCursor: String(this.cursor + 1n) },
        },
      ],
    });
    return id;
  }
  async modelCompletion(
    id: string,
    response: Json,
    usage?: {
      input: number;
      output: number;
      cacheRead?: number;
      cacheWrite?: number;
    },
  ): Promise<Json> {
    if (
      response &&
      typeof response === "object" &&
      !Array.isArray(response) &&
      (response.stopReason === "error" || response.stopReason === "aborted")
    )
      this.modelFailed = true;
    const ref = await this.client.put(
      this.scope,
      Buffer.from(JSON.stringify(response)),
      "model-result-v1",
      this.signal,
    );
    // PI reports uncached input, cache reads and cache writes as disjoint counts.
    // The platform's inputTokens is the total so cached context also consumes budget.
    const counts = usage ? [usage.input, usage.output, usage.cacheRead ?? 0, usage.cacheWrite ?? 0] : [];
    const inputTokens = counts[0]! + counts[2]! + counts[3]!;
    const knownUsage = counts.length === 4 && counts.every((count) => Number.isSafeInteger(count) && count >= 0)
      && Number.isSafeInteger(inputTokens);
    return {
      modelCallId: id,
      phase: "complete",
      status:
        response &&
        typeof response === "object" &&
        !Array.isArray(response) &&
        response.stopReason === "error"
          ? "failed"
          : response &&
              typeof response === "object" &&
              !Array.isArray(response) &&
              response.stopReason === "aborted"
            ? "cancelled"
            : "completed",
      resultRef: ref as unknown as Json,
      position: { nativeCursor: String(this.cursor + 1n) },
      ...(knownUsage
        ? {
            usage: {
              inputTokens: String(inputTokens),
              outputTokens: String(counts[1]),
              cacheReadInputTokens: String(counts[2]),
              cacheWriteInputTokens: String(counts[3]),
            },
          }
        : { usageUnknown: true }),
    };
  }
  async fail(): Promise<void> {
    this.check();
    await this.closeInputs();
    for (const input of await this.inputs())
      await this.rejectInput(
        input.inputId,
        "Execution failed before input application",
      );
    this.modelFailed = true;
    this.outputMessageId = undefined;
    await this.finish([
      { type: "text", text: "Execution could not continue." },
    ]);
  }
  async finish(output: Json): Promise<void> {
    await this.client.post("inputs/close", this.identity(), this.signal);
    const inputs = await this.client.post<{
      pendingInputs: Recovery["pendingInputs"];
    }>("inputs/read", this.identity(), this.signal);
    if (inputs.pendingInputs.length)
      throw new Error("Accepted inputs must be settled before finishing");
    const bytes = Buffer.from(JSON.stringify(output));
    const content = bytes.length <= 16 * 1024 ? { inline: output }
      : { ref: await this.client.put(this.scope, bytes, "public-output-v1", this.signal) };
    await this.boundary({
      terminal: {
        status: this.cancelled
          ? "cancelled"
          : this.modelFailed
            ? "failed"
            : "succeeded",
        output: content,
        ...(this.outputMessageId ? { messageId: this.outputMessageId } : {}),
      },
    });
  }
}
export function delay(
  milliseconds: number,
  signal: AbortSignal,
): Promise<void> {
  return new Promise((resolve, reject) => {
    signal.throwIfAborted();
    const timer = setTimeout(done, milliseconds);
    const abort = () => {
      clearTimeout(timer);
      signal.removeEventListener("abort", abort);
      reject(signal.reason);
    };
    function done() {
      signal.removeEventListener("abort", abort);
      resolve();
    }
    signal.addEventListener("abort", abort, { once: true });
  });
}

export async function runClaimed(
  adapter: HarnessAdapter,
  client: SessionClient,
  claim: { scope: Scope; turnId: string; fence: Fence; expiresAt: string },
  configuration: RunConfiguration,
  signal?: AbortSignal,
): Promise<void> {
  const recovery = await loadRecovery(
    client,
    claim,
    adapter.recoveryPageSize,
    signal,
  );
  const host = new RunHost(
    client,
    claim.scope,
    claim.turnId,
    claim.fence,
    recovery,
    adapter.type,
    adapter.stateFormat,
    claim.expiresAt,
    configuration.inlineWaitMs,
  );
  const interrupt = () => host.abort(signal?.reason);
  signal?.addEventListener("abort", interrupt, { once: true });
  if (signal?.aborted) interrupt();
  try {
    await host.startRenewal();
    const output = await adapter.run(host, configuration);
    await host.finish(output);
  } catch (error) {
    if (host.isSuspended) return;
    if (
      error instanceof TurnRejected ||
      (error instanceof ServiceError &&
        ["budget_exceeded", "model_not_allowed"].includes(error.code))
    )
      await host.fail();
    else throw error;
  } finally {
    signal?.removeEventListener("abort", interrupt);
    host.close();
  }
}

export function assistantOutput(value: Json): Json {
  if (value === null || typeof value !== "object" || Array.isArray(value))
    return [];
  const content = value.content;
  return Array.isArray(content)
    ? content
        .filter(
          (block) =>
            block !== null &&
            typeof block === "object" &&
            !Array.isArray(block) &&
            block.type === "text" && typeof block.text === "string",
        )
        .map((block) => ({
          type: "text",
          text: (block as { text: Json }).text,
        }))
    : [];
}

/** Only an adapter-confirmed assistant message belongs here, never auxiliary model output. */
export function assistantMessageFact(turnId: string, messageId: string, message: Json): Json {
  const itemId = `item_${fingerprint(turnId, messageId)}`;
  return {
    factId: `message_${fingerprint(turnId, messageId)}`,
    type: "item.completed",
    occurredAt: new Date().toISOString(),
    content: { inline: {
      schema_version: 1, turn_id: turnId, item_id: itemId,
      item: { id: itemId, message_id: messageId, type: "message", role: "assistant",
        status: "completed", content: assistantOutput(message) },
    } },
  };
}
