/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/** SDK version reported on `/agentscope/info`. */
export const SDK_VERSION = '0.1.0'

/** Framework identifier advertised to the control plane. */
export const FRAMEWORK = 'deepseek-harness'

/** Contract level 3: discovery, sessions, and commands. */
export const CONTRACT_LEVEL = 3

export const PHASE_ACTIVE = 'active'
export const PHASE_IDLE = 'idle'
export const PHASE_COMPRESSING = 'compressing'
export const PHASE_TERMINATED = 'terminated'
export const PHASE_ARCHIVED = 'archived'

export type SessionPhase =
    | typeof PHASE_ACTIVE
    | typeof PHASE_IDLE
    | typeof PHASE_COMPRESSING
    | typeof PHASE_TERMINATED
    | typeof PHASE_ARCHIVED

export const CAP_SESSION_REPORTING = 'session-reporting'
export const CAP_CONTEXT_QUERY = 'context-query'
export const CAP_MESSAGE_QUERY = 'message-query'
export const CAP_SESSION_COMMAND = 'session-command'
export const CAP_SESSION_ABORT = 'session-abort'
export const CAP_SUBAGENT_INVENTORY = 'subagent-inventory'
export const CAP_WORKSPACE_INVENTORY = 'workspace-inventory'
export const CAP_AGENT_TASK = 'agent-task'
export const CAP_TASK_QUERY = 'task-query'
export const CAP_PLAN_MODE = 'plan-mode'
export const CAP_SUBAGENT_TASK_QUERY = 'subagent-task-query'
export const CAP_SUBAGENT_TASK_COMMAND = 'subagent-task-command'
export const CAP_EXPORT_TRANSCRIPT = 'export-transcript'

export const MAX_SESSIONS_PROBE_PAGE = 500

export const INTERNAL_TOKEN_HEADER = 'x-builder-internal-token'

export class ContractNotFoundError extends Error {
    constructor(message = 'not found') {
        super(message)
        this.name = 'ContractNotFoundError'
    }
}

export class ContractUnsupportedError extends Error {
    constructor(message = 'data plane does not support this operation') {
        super(message)
        this.name = 'ContractUnsupportedError'
    }
}

export class ContractBusyError extends Error {
    readonly hint = 'wait_idle'

    constructor(message = 'session is busy') {
        super(message)
        this.name = 'ContractBusyError'
    }
}

export class ContractUnauthorizedError extends Error {
    constructor(message = 'unauthorized') {
        super(message)
        this.name = 'ContractUnauthorizedError'
    }
}

/** Minimal Cordis context the plugin programs against. */
export interface DshContext {
    agents: AgentRegistry
    sessions: SessionStore
    on(event: string, listener: (...args: unknown[]) => void): unknown
    effect?(fn: () => void | (() => void)): unknown
    get?(name: string): unknown
    logger?: PluginLogger
}

export interface PluginLogger {
    info?(message: string, extra?: unknown): void
    warn?(message: string, extra?: unknown): void
    error?(message: string, extra?: unknown): void
    debug?(message: string, extra?: unknown): void
}

export interface AgentHandle {
    agent: DshAgent
    dispose(): Promise<void>
}

export interface AgentRegistry {
    list(): DshAgent[]
    get(id: string): DshAgent | undefined
    roots?(): DshAgent[]
    create?(options: Record<string, unknown>): Promise<AgentHandle>
}

export interface SessionStore {
    list(): DshSession[]
    get(id: string): DshSession | undefined
}

export interface DshAgent {
    id: string
    status: string
    session: DshSession
    options?: { provider?: string; model?: string; maxTokens?: number }
    ctx?: { tools?: ToolRegistry }
    followup(message: unknown): unknown
    cancel(cause: unknown, options?: { keepInbox?: boolean }): void
    whenIdle?(): Promise<void>
}

export interface DshSession {
    id: string
    seq?: number
    events: readonly DshSessionEvent[]
    header?: {
        createdAt?: number | string
        cwd?: string
        parentSession?: string
        systemPrompt?: string
    }
    deriveMessages?(): unknown[]
}

export interface DshSessionEvent {
    type: string
    seq?: number
    time?: number
    data?: Record<string, unknown>
}

export interface ToolRegistry {
    register(definition: Record<string, unknown>): () => void
}

export interface PluginConfig {
    tenant?: string
    controlHttp?: string
    controlGrpc?: string
    internalToken?: string
    agentName?: string
    namespace?: string
    instanceId?: string
    contractHost?: string
    contractPort?: number
    publicBaseUrl?: string
    startHttp?: boolean
    startHttpRegister?: boolean
    startGrpc?: boolean
    heartbeatIntervalMs?: number
    transcriptDir?: string
}

export interface ResolvedConfig {
    tenant: string
    controlHttp: string
    controlGrpc: string
    internalToken: string
    agentName: string
    namespace: string
    instanceId: string
    contractHost: string
    contractPort: number
    publicBaseUrl: string
    startHttp: boolean
    startHttpRegister: boolean
    startGrpc: boolean
    heartbeatIntervalMs: number
    transcriptDir: string
}

export interface ContractProvider {
    info(): Record<string, unknown>
    sessions(): Array<Record<string, unknown>>
    sessionsTruncated?(): boolean
    sessionState(sessionId: string): Record<string, unknown>
    context(sessionId: string): Record<string, unknown>
    messages(sessionId: string, offset: number, limit: number): Record<string, unknown>
    subagents(): Array<Record<string, unknown>>
    workspaces(): Array<Record<string, unknown>>
    tasks(sessionId: string): Record<string, unknown>
    subagentTasks?(sessionId: string): Record<string, unknown> | Promise<Record<string, unknown>>
    cancelSubagentTask?(sessionId: string, taskId: string): void | Promise<void>
    planMode?(sessionId: string, body: Buffer): void | Promise<void>
    exportTranscript?(sessionId: string): Record<string, unknown>
    postMessage?(sessionId: string, body: Buffer): void | Promise<void>
    compress(sessionId: string): void | Promise<void>
    terminate(sessionId: string): void | Promise<void>
    abort(sessionId: string): void | Promise<void>
    sessionPhase(sessionId: string): string
}
