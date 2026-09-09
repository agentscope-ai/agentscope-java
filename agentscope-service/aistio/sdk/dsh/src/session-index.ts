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

import { createHash } from 'node:crypto'
import {
    CAP_CONTEXT_QUERY,
    CAP_EXPORT_TRANSCRIPT,
    CAP_MESSAGE_QUERY,
    CAP_PLAN_MODE,
    CAP_SESSION_ABORT,
    CAP_SESSION_COMMAND,
    CAP_SESSION_REPORTING,
    CAP_SUBAGENT_INVENTORY,
    CAP_SUBAGENT_TASK_COMMAND,
    CAP_SUBAGENT_TASK_QUERY,
    CAP_TASK_QUERY,
    CAP_WORKSPACE_INVENTORY,
    ContractNotFoundError,
    FRAMEWORK,
    PHASE_ACTIVE,
    PHASE_ARCHIVED,
    PHASE_COMPRESSING,
    PHASE_IDLE,
    PHASE_TERMINATED,
    type DshAgent,
    type DshContext,
    type DshSession,
    type DshSessionEvent,
    type SessionPhase,
} from './types.js'

const HASH_LEN = 16

export interface TrackedSession {
    id: string
    nativeId: string
    phase: SessionPhase
    busy: boolean
    startedAt: number
    lastActiveAt: number
    promptTokens: number
    completionTokens: number
    messageCount: number
    effectiveMessageCount: number
    compacted: boolean
    model: string
    maxTokens: number
    contextUsedTokens: number
}

/**
 * Observes live DSH sessions and maps them onto the aistio contract snapshot.
 */
export class SessionIndex {
    private readonly records = new Map<string, TrackedSession>()
    private readonly aliases = new Map<string, string>()
    private readonly listeners: Array<() => void> = []

    constructor(private readonly ctx: DshContext) {}

    attach(): void {
        this.replayExisting()
        this.listeners.push(
            bind(this.ctx, 'session/event', (session, event) => {
                this.onEvent(asSession(session), asEvent(event))
            }),
        )
        this.listeners.push(
            bind(this.ctx, 'session/created', (session) => {
                const live = asSession(session)
                if (live) {
                    this.ingest(live)
                }
            }),
        )
        this.listeners.push(
            bind(this.ctx, 'session/disposed', (session) => {
                const live = asSession(session)
                if (live) {
                    this.remove(live.id)
                }
            }),
        )
        this.listeners.push(
            bind(this.ctx, 'agent/status', (payload) => {
                this.onAgentStatus(payload)
            }),
        )
    }

    detach(): void {
        for (const unbind of this.listeners) {
            unbind()
        }
        this.listeners.length = 0
    }

    alias(controlPlaneSessionId: string, nativeId: string): void {
        this.aliases.set(controlPlaneSessionId, nativeId)
        const native = this.ensure(nativeId)
        if (controlPlaneSessionId !== nativeId) {
            this.records.set(controlPlaneSessionId, { ...native, id: controlPlaneSessionId, nativeId })
        }
    }

    unalias(controlPlaneSessionId: string): void {
        this.aliases.delete(controlPlaneSessionId)
        if (controlPlaneSessionId !== this.records.get(controlPlaneSessionId)?.nativeId) {
            this.records.delete(controlPlaneSessionId)
        }
    }

    resolveNativeId(sessionId: string): string {
        return this.aliases.get(sessionId) ?? this.records.get(sessionId)?.nativeId ?? sessionId
    }

    require(sessionId: string): TrackedSession {
        const record = this.records.get(sessionId) ?? this.records.get(this.resolveNativeId(sessionId))
        if (!record) {
            throw new ContractNotFoundError(`session not found: ${sessionId}`)
        }
        return record
    }

    requireSession(sessionId: string): DshSession {
        const nativeId = this.resolveNativeId(sessionId)
        const session = this.ctx.sessions.get(nativeId)
        if (!session) {
            throw new ContractNotFoundError(`session not found: ${sessionId}`)
        }
        return session
    }

    requireAgent(sessionId: string): DshAgent {
        const nativeId = this.resolveNativeId(sessionId)
        const agent =
            this.ctx.agents.get(nativeId) ??
            this.ctx.agents.list().find((item) => item.session?.id === nativeId)
        if (!agent) {
            throw new ContractNotFoundError(`agent not found for session: ${sessionId}`)
        }
        return agent
    }

    pickRootAgent(): DshAgent | undefined {
        const roots = this.ctx.agents.roots?.() ?? []
        if (roots.length > 0) {
            return roots[0]
        }
        return this.ctx.agents.list()[0]
    }

    setPhase(sessionId: string, phase: SessionPhase): void {
        const record = this.require(sessionId)
        record.phase = phase
        record.busy = phase === PHASE_ACTIVE
        record.lastActiveAt = Date.now()
    }

    snapshots(): Array<Record<string, unknown>> {
        this.syncFromStore()
        const seen = new Set<string>()
        const out: Array<Record<string, unknown>> = []
        for (const record of this.records.values()) {
            if (seen.has(record.id)) {
                continue
            }
            seen.add(record.id)
            out.push(this.toSnapshot(record))
        }
        return out
    }

    sessionState(sessionId: string): Record<string, unknown> {
        this.syncFromStore()
        const record = this.require(sessionId)
        const snapshot = this.toSnapshot(record)
        snapshot.sessionId = sessionId
        snapshot.contextPressure = {
            usedTokens: record.contextUsedTokens,
            maxTokens: record.maxTokens,
            ratio:
                record.maxTokens > 0 && record.contextUsedTokens > 0
                    ? record.contextUsedTokens / record.maxTokens
                    : 0,
        }
        return snapshot
    }

    context(sessionId: string): Record<string, unknown> {
        const record = this.require(sessionId)
        const session = this.requireSession(sessionId)
        const messages = deriveContextMessages(session)
        const tools = this.listToolInfo(sessionId)
        const contextHash = hashContext(messages, tools)
        return {
            sessionId,
            capturedAt: iso(Date.now()),
            contextHash,
            systemPrompt: extractSystemPrompt(session, messages),
            messages,
            tools,
            isCompacted: record.compacted,
            totalTokens: record.promptTokens + record.completionTokens,
            maxTokens: record.maxTokens,
            framework: FRAMEWORK,
            model: record.model,
        }
    }

    messages(sessionId: string, offset: number, limit: number): Record<string, unknown> {
        const session = this.requireSession(sessionId)
        const items = historyMessages(session)
        const slice = items.slice(offset, offset + limit)
        return {
            sessionId,
            offset,
            limit,
            total: items.length,
            messages: slice,
        }
    }

    subagents(): Array<Record<string, unknown>> {
        const roots = new Set((this.ctx.agents.roots?.() ?? []).map((agent) => agent.id))
        return this.ctx.agents
            .list()
            .filter((agent) =>
                roots.size === 0
                    ? Boolean(agent.session?.header?.parentSession)
                    : !roots.has(agent.id),
            )
            .map((agent) => {
                const tools = this.listToolInfo(agent.session?.id ?? agent.id)
                const cwd = agent.session?.header?.cwd ?? ''
                return {
                    name: agent.id,
                    description: agent.session?.header?.parentSession
                        ? `child of ${agent.session.header.parentSession}`
                        : 'subagent',
                    tools: tools.map((tool) => String(tool.name ?? '')).filter(Boolean),
                    workspaceMode: cwd ? 'shared' : 'isolated',
                    url: '',
                }
            })
    }

    tasks(sessionId: string): Record<string, unknown> {
        this.require(sessionId)
        const session = this.requireSession(sessionId)
        let latest: unknown[] = []
        for (const event of session.events ?? []) {
            if (event.type === 'todo/write') {
                const todos = (event.data as { todos?: unknown } | undefined)?.todos
                if (Array.isArray(todos)) {
                    latest = todos
                }
            }
        }
        const tasks = latest.map((item, index) => toTaskJson(item, index))
        return { tasks }
    }

    exportTranscript(sessionId: string): Record<string, unknown> {
        const page = this.messages(sessionId, 0, Number.MAX_SAFE_INTEGER)
        return {
            sessionId,
            format: 'json',
            messages: page.messages,
        }
    }

    async subagentTasks(sessionId: string): Promise<Record<string, unknown>> {
        const agent = this.requireAgent(sessionId)
        const subagents = this.ctx.get?.('subagents') as
            | { listChildren?(parentId: string, signal?: AbortSignal): Promise<unknown[]> }
            | undefined
        if (!subagents?.listChildren) {
            throw new ContractNotFoundError(`subagent tasks unavailable for ${sessionId}`)
        }
        try {
            const children = (await subagents.listChildren(agent.id)) ?? []
            return {
                tasks: children.map((child, index) => toSubagentTaskJson(child, index)),
            }
        } catch {
            return { tasks: [] }
        }
    }

    cancelSubagentTask(sessionId: string, taskId: string): void {
        this.require(sessionId)
        const child =
            this.ctx.agents.get(taskId) ??
            this.ctx.agents.list().find((item) => item.id === taskId)
        if (!child) {
            throw new ContractNotFoundError(`subagent task not found: ${taskId}`)
        }
        child.cancel({ kind: 'hook', reason: 'aistio subagent-task cancel' })
    }

    planMode(sessionId: string, body: Buffer): void {
        const agent = this.requireAgent(sessionId)
        const active = parsePlanActive(body)
        const planMode = this.ctx.get?.('planMode') as
            | { set?(target: DshAgent, active: boolean): unknown }
            | undefined
        if (!planMode?.set) {
            throw new ContractNotFoundError(`plan-mode is not available for ${sessionId}`)
        }
        planMode.set(agent, active)
    }

    hasPlanMode(): boolean {
        return typeof (this.ctx.get?.('planMode') as { set?: unknown } | undefined)?.set === 'function'
    }

    hasSubagents(): boolean {
        return typeof (this.ctx.get?.('subagents') as { listChildren?: unknown } | undefined)
            ?.listChildren === 'function'
    }

    workspaces(): Array<Record<string, unknown>> {
        const seen = new Set<string>()
        const out: Array<Record<string, unknown>> = []
        for (const session of this.ctx.sessions.list()) {
            const cwd = session.header?.cwd
            if (!cwd || seen.has(cwd)) {
                continue
            }
            seen.add(cwd)
            out.push({ name: cwd, path: cwd })
        }
        return out
    }

    capabilities(extra: string[] = []): string[] {
        const caps = new Set<string>([
            CAP_SESSION_REPORTING,
            CAP_CONTEXT_QUERY,
            CAP_MESSAGE_QUERY,
            CAP_SESSION_ABORT,
            CAP_SESSION_COMMAND,
            CAP_SUBAGENT_INVENTORY,
            CAP_WORKSPACE_INVENTORY,
            CAP_TASK_QUERY,
            CAP_EXPORT_TRANSCRIPT,
            ...extra,
        ])
        if (this.hasPlanMode()) {
            caps.add(CAP_PLAN_MODE)
        }
        if (this.hasSubagents()) {
            caps.add(CAP_SUBAGENT_TASK_QUERY)
            caps.add(CAP_SUBAGENT_TASK_COMMAND)
        }
        return [...caps].sort()
    }

    private replayExisting(): void {
        for (const session of this.ctx.sessions.list()) {
            this.ingest(session)
        }
    }

    private syncFromStore(): void {
        for (const session of this.ctx.sessions.list()) {
            if (!this.records.has(session.id)) {
                this.ingest(session)
            }
        }
    }

    ingest(session: DshSession): TrackedSession {
        const record = this.ensure(session.id)
        record.startedAt = toMillis(session.header?.createdAt) || record.startedAt
        for (const event of session.events ?? []) {
            this.applyEvent(record, event)
        }
        return record
    }

    private onEvent(session: DshSession | undefined, event: DshSessionEvent | undefined): void {
        if (!session || !event) {
            return
        }
        try {
            const record = this.ensure(session.id)
            this.applyEvent(record, event)
        } catch {
            // bypass
        }
    }

    private onAgentStatus(payload: unknown): void {
        try {
            const body = (payload ?? {}) as { agent?: DshAgent; status?: string }
            const sessionId = body.agent?.session?.id
            if (!sessionId) {
                return
            }
            const record = this.ensure(sessionId)
            const running = body.status === 'running'
            record.busy = running
            if (record.phase !== PHASE_TERMINATED && record.phase !== PHASE_ARCHIVED) {
                record.phase = running ? PHASE_ACTIVE : PHASE_IDLE
            }
            record.lastActiveAt = Date.now()
            if (body.agent?.options?.model) {
                record.model = body.agent.options.model
            }
            if (body.agent?.options?.maxTokens) {
                record.maxTokens = body.agent.options.maxTokens
            }
        } catch {
            // bypass
        }
    }

    private applyEvent(record: TrackedSession, event: DshSessionEvent): void {
        record.lastActiveAt = toMillis(event.time) || Date.now()
        switch (event.type) {
            case 'turn/start':
                record.phase = PHASE_ACTIVE
                record.busy = true
                break
            case 'turn/end':
                record.busy = false
                if (record.phase !== PHASE_TERMINATED) {
                    record.phase = PHASE_IDLE
                }
                break
            case 'user/message':
            case 'assistant/message':
                record.messageCount += 1
                record.effectiveMessageCount += 1
                this.addUsage(record, event)
                if (event.type === 'assistant/message') {
                    const model = nestedString(event.data, ['message', 'source', 'model'])
                    if (model) {
                        record.model = model
                    }
                }
                break
            case 'compaction/start':
                record.phase = PHASE_COMPRESSING
                break
            case 'compaction/end':
            case 'compaction/summary':
                record.compacted = true
                record.phase = record.busy ? PHASE_ACTIVE : PHASE_IDLE
                break
            default:
                break
        }
    }

    private addUsage(record: TrackedSession, event: DshSessionEvent): void {
        const usage =
            (event.data?.usage as Record<string, number> | undefined) ??
            ((event.data?.message as Record<string, unknown> | undefined)?.usage as
                | Record<string, number>
                | undefined)
        if (!usage) {
            return
        }
        record.promptTokens += usage.inputTokens ?? usage.promptTokens ?? 0
        record.completionTokens += usage.outputTokens ?? usage.completionTokens ?? 0
        const window = usage.inputTokens ?? usage.promptTokens
        if (typeof window === 'number' && window > 0) {
            record.contextUsedTokens = window
        }
    }

    private ensure(id: string): TrackedSession {
        let record = this.records.get(id)
        if (!record) {
            const now = Date.now()
            record = {
                id,
                nativeId: id,
                phase: PHASE_IDLE,
                busy: false,
                startedAt: now,
                lastActiveAt: now,
                promptTokens: 0,
                completionTokens: 0,
                messageCount: 0,
                effectiveMessageCount: 0,
                compacted: false,
                model: '',
                maxTokens: 0,
                contextUsedTokens: 0,
            }
            this.records.set(id, record)
        }
        return record
    }

    private remove(id: string): void {
        this.records.delete(id)
        for (const [alias, native] of this.aliases) {
            if (native === id || alias === id) {
                this.aliases.delete(alias)
                this.records.delete(alias)
            }
        }
    }

    private toSnapshot(record: TrackedSession): Record<string, unknown> {
        const total = record.promptTokens + record.completionTokens
        const snapshot: Record<string, unknown> = {
            id: record.id,
            phase: record.phase,
            busy: record.busy,
            startedAt: iso(record.startedAt),
            lastActiveAt: iso(record.lastActiveAt),
            messageCount: record.messageCount,
            tokenUsage: {
                promptTokens: record.promptTokens,
                completionTokens: record.completionTokens,
                totalTokens: total,
                ...(record.maxTokens > 0 ? { maxTokens: record.maxTokens } : {}),
            },
            contextPressure:
                record.maxTokens > 0 && record.contextUsedTokens > 0
                    ? record.contextUsedTokens / record.maxTokens
                    : 0,
            framework: FRAMEWORK,
            effectiveMessageCount: record.effectiveMessageCount,
        }
        if (record.model) {
            snapshot.model = record.model
        }
        if (record.compacted) {
            snapshot.isCompacted = true
        }
        return snapshot
    }

    private listToolInfo(sessionId: string): Array<Record<string, unknown>> {
        try {
            const agent = this.requireAgent(sessionId)
            const tools = agent.ctx?.tools as { list?: () => Array<{ name: string; description?: string }> } | undefined
            const listed = tools?.list?.() ?? []
            return listed.map((tool) => ({
                name: tool.name,
                description: tool.description ?? '',
            }))
        } catch {
            return []
        }
    }
}

function bind(ctx: DshContext, event: string, listener: (...args: unknown[]) => void): () => void {
    const result = ctx.on(event, listener)
    if (typeof result === 'function') {
        return result as () => void
    }
    return () => undefined
}

function asSession(value: unknown): DshSession | undefined {
    if (value && typeof value === 'object' && 'id' in value) {
        return value as DshSession
    }
    return undefined
}

function asEvent(value: unknown): DshSessionEvent | undefined {
    if (value && typeof value === 'object' && 'type' in value) {
        return value as DshSessionEvent
    }
    return undefined
}

function iso(epochMs: number): string {
    return new Date(epochMs).toISOString()
}

function toMillis(value: unknown): number {
    if (typeof value === 'number' && Number.isFinite(value)) {
        return value < 1e12 ? value * 1000 : value
    }
    if (typeof value === 'string' && value) {
        const parsed = Date.parse(value)
        return Number.isFinite(parsed) ? parsed : 0
    }
    return 0
}

function nestedString(root: Record<string, unknown> | undefined, path: string[]): string {
    let cursor: unknown = root
    for (const key of path) {
        if (!cursor || typeof cursor !== 'object') {
            return ''
        }
        cursor = (cursor as Record<string, unknown>)[key]
    }
    return typeof cursor === 'string' ? cursor : ''
}

function deriveContextMessages(session: DshSession): Array<Record<string, unknown>> {
    try {
        const derived = session.deriveMessages?.() ?? []
        if (derived.length > 0) {
            return derived.map((message) => projectMessage(message))
        }
    } catch {
        // fall through to event projection
    }
    return historyMessages(session).map(({ role, content, ...rest }) => ({
        role,
        content,
        ...('isCompaction' in rest ? { isCompaction: rest.isCompaction } : {}),
    }))
}

function historyMessages(session: DshSession): Array<Record<string, unknown>> {
    const out: Array<Record<string, unknown>> = []
    for (const event of session.events ?? []) {
        if (event.type === 'user/message' || event.type === 'assistant/message') {
            const message = (event.data?.message ?? event.data) as Record<string, unknown> | undefined
            out.push({
                seq: event.seq ?? out.length + 1,
                role: event.type === 'user/message' ? 'user' : 'assistant',
                content: textOf(message),
                occurredAt: event.time ? iso(toMillis(event.time)) : undefined,
            })
        } else if (event.type === 'tool/call') {
            out.push({
                seq: event.seq ?? out.length + 1,
                role: 'assistant',
                content: String(event.data?.arguments ?? ''),
                toolName: event.data?.name,
                occurredAt: event.time ? iso(toMillis(event.time)) : undefined,
            })
        } else if (event.type === 'tool/result') {
            const message = event.data?.message as Record<string, unknown> | undefined
            out.push({
                seq: event.seq ?? out.length + 1,
                role: 'tool',
                content: textOf(message ?? event.data),
                occurredAt: event.time ? iso(toMillis(event.time)) : undefined,
            })
        } else if (event.type === 'compaction/summary') {
            out.push({
                seq: event.seq ?? out.length + 1,
                role: 'system',
                content: String(event.data?.summary ?? event.data?.text ?? ''),
                isCompaction: true,
                occurredAt: event.time ? iso(toMillis(event.time)) : undefined,
            })
        }
    }
    return out
}

function projectMessage(message: unknown): Record<string, unknown> {
    const value = (message ?? {}) as Record<string, unknown>
    const role = typeof value.role === 'string' ? value.role : 'user'
    return {
        role,
        content: textOf(value),
        ...(role === 'system' ? { isCompaction: false } : {}),
    }
}

function textOf(message: Record<string, unknown> | undefined): string {
    if (!message) {
        return ''
    }
    if (typeof message.content === 'string') {
        return message.content
    }
    if (Array.isArray(message.content)) {
        return message.content
            .map((block) => {
                if (block && typeof block === 'object' && 'text' in block) {
                    return String((block as { text?: unknown }).text ?? '')
                }
                return ''
            })
            .filter(Boolean)
            .join('\n')
    }
    if (typeof message.text === 'string') {
        return message.text
    }
    return ''
}

function hashContext(
    messages: Array<Record<string, unknown>>,
    tools: Array<Record<string, unknown>>,
): string {
    const canonical = JSON.stringify({ messages, tools })
    return createHash('sha256').update(canonical).digest('hex').slice(0, HASH_LEN)
}

function extractSystemPrompt(session: DshSession, messages: Array<Record<string, unknown>>): string {
    const header = session.header?.systemPrompt
    if (typeof header === 'string' && header.trim() !== '') {
        return header
    }
    for (const event of session.events ?? []) {
        if (event.type === 'system/prompt' || event.type === 'session/system') {
            const text = String(
                (event.data as { text?: unknown; prompt?: unknown } | undefined)?.text ??
                    (event.data as { prompt?: unknown } | undefined)?.prompt ??
                    '',
            )
            if (text) {
                return text
            }
        }
    }
    const firstSystem = messages.find((message) => message.role === 'system')
    return typeof firstSystem?.content === 'string' ? firstSystem.content : ''
}

function toTaskJson(item: unknown, index: number): Record<string, unknown> {
    const value = (item ?? {}) as Record<string, unknown>
    const content = String(value.content ?? value.subject ?? value.text ?? '')
    const status = String(value.status ?? value.state ?? 'pending').toLowerCase()
    const state =
        status === 'completed' || status === 'complete'
            ? 'completed'
            : status === 'in_progress' || status === 'in-progress' || status === 'working'
              ? 'in_progress'
              : status === 'failed'
                ? 'failed'
                : 'pending'
    return {
        id: String(value.id ?? `todo-${index + 1}`),
        subject: content,
        state,
        owner: String(value.owner ?? ''),
        blockedBy: Array.isArray(value.blockedBy) ? value.blockedBy : [],
        updatedAt: typeof value.updatedAt === 'string' ? value.updatedAt : undefined,
        frameworkMeta: { source: 'todo/write' },
    }
}

function toSubagentTaskJson(child: unknown, index: number): Record<string, unknown> {
    const value = (child ?? {}) as Record<string, unknown>
    const id = String(value.id ?? value.agentId ?? value.childId ?? `subagent-${index + 1}`)
    return {
        id,
        status: String(value.status ?? value.state ?? 'running'),
        subject: String(value.subject ?? value.label ?? value.name ?? id),
        label: String(value.label ?? value.name ?? id),
    }
}

function parsePlanActive(body: Buffer): boolean {
    if (!body || body.length === 0) {
        return true
    }
    const root = JSON.parse(body.toString('utf8')) as { active?: boolean; mode?: string }
    if (typeof root.active === 'boolean') {
        return root.active
    }
    const mode = String(root.mode ?? '').toLowerCase()
    return mode !== 'exit' && mode !== 'off' && mode !== 'false'
}
