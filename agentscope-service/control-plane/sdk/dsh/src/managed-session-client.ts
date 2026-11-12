/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import type { ServiceData, ServiceEvent, ServiceSnapshot } from './service-client.js'

interface Transport {
    request<T>(path: string, method?: string, body?: unknown, key?: string, revision?: number, signal?: AbortSignal): Promise<T>
    content(path: string): Promise<Uint8Array>
    upload(path: string, name: string, data: Uint8Array, key: string, contentType: string): Promise<ServiceData>
    stream(path: string, after?: string, signal?: AbortSignal): AsyncGenerator<ServiceEvent>
}
export interface ManagedInputReceipt {
    id: string; turn_id: string; input_type: string; status: 'accepted' | 'applied' | 'rejected'
    delivery_status: 'pending' | 'delivered'; rejection_reason?: string
}
export interface ManagedLifecycleReceipt {
    id: string; session_id: string; lifecycle: 'active' | 'archived' | 'deleted'
    status: 'stopping' | 'cleaning' | 'completed'; revision: number; failure_code?: string
}
export type ManagedEvent = SessionEvent
const root = '/v1/sessions'
const segment = (value: string) => {
    if (!value?.trim() || value === '.' || value === '..') throw new Error('Invalid path identifier')
    return encodeURIComponent(value)
}
const path = (session: string) => root + '/' + segment(session)
const turnPath = (session: string, turn: string) => path(session) + '/turns/' + segment(turn)
const scopePath = (session: string, turn?: string) => turn ? turnPath(session, turn) : path(session)
const retryKey = (value: string) => {
    if (!value?.trim() || value.length > 256) throw new Error('Idempotency-Key needs 1..256 characters')
    return value
}
const query = (values: Record<string, string | number | undefined>) => {
    const result = new URLSearchParams()
    for (const [key, value] of Object.entries(values)) if (value !== undefined && value !== '') result.set(key, String(value))
    return result.size ? '?' + result : ''
}
const limit = (value: number, maximum = 256) => {
    if (!Number.isInteger(value) || value < 1 || value > maximum) throw new Error('Invalid page limit')
    return value
}

/** Native Managed protocol. Acquire through ServiceClient.managedSessions(). */
export class ManagedSessionClient {
    constructor(private readonly transport: Transport) {}
    createSession(definition: ServiceData, key: string) { return this.transport.request<ServiceData>(root, 'POST', definition, retryKey(key)) }
    getSession(session: string) { return this.transport.request<ServiceData>(path(session)) }
    updateSession(session: string, values: { title?: string | null; metadata?: Record<string, string> }) { return this.transport.request<ServiceData>(path(session), 'POST', values) }
    sessions(options: { after?: string; limit?: number; lifecycle?: 'active' | 'archived' | 'all'; agent?: string } = {}) {
        return this.transport.request<ServiceData>(root + query({ after: options.after, limit: limit(options.limit ?? 100), lifecycle: options.lifecycle, agent_id: options.agent }))
    }
    sendEvents(session: string, events: SessionInputEvent[], key = crypto.randomUUID()) {
        return this.transport.request<{ data: SessionEvent[] }>(path(session) + '/events', 'POST', { events }, retryKey(key))
    }
    async submit(session: string, input: ServiceData, key = crypto.randomUUID()) {
        return eventReceipt(await this.sendEvents(session, [{ type: 'user.message', content: inputContent(input), managed_agents: { intent: 'new_turn' } }], key))
    }
    submitTurn(session: string, input: ServiceData, key = crypto.randomUUID()) { return this.submit(session, input, key) }
    getTurn(session: string, turn: string, signal?: AbortSignal) { return this.transport.request<ServiceData>(turnPath(session, turn), 'GET', undefined, undefined, undefined, signal) }
    turns(session: string, options: { after?: string; limit?: number; order?: 'asc' | 'desc'; status?: string; inputEventId?: string } = {}) {
        return this.transport.request<ServiceData>(path(session) + '/turns' + query({ after: options.after, order: options.order, status: options.status, input_event_id: options.inputEventId, limit: limit(options.limit ?? 100) }))
    }
    async input(session: string, turn: string, type: string, payload: ServiceData, key = crypto.randomUUID(), actionId?: string) {
        if (!['steer', 'inject', 'cancel'].includes(type) || actionId) throw new Error('unsupported_capability: Input type')
        const event: ServiceData = { type: type === 'cancel' ? 'user.interrupt' : 'user.message', managed_agents: { intent: type, turn_id: turn } }
        if (type !== 'cancel') event.content = inputContent(payload)
        return eventReceipt(await this.sendEvents(session, [event as unknown as SessionInputEvent], key)) as unknown as ManagedInputReceipt
    }
    steer(session: string, turn: string, message: string, key = crypto.randomUUID()) { return this.input(session, turn, 'steer', { message }, key) }
    inject(session: string, turn: string, message: string, key = crypto.randomUUID()) { return this.input(session, turn, 'inject', { message }, key) }
    cancelTurn(session: string, turn: string, key = crypto.randomUUID()) { return this.input(session, turn, 'cancel', {}, key) }
    output(session: string, turn: string, after = '', pageLimit = 100, signal?: AbortSignal) {
        return this.transport.request<TurnOutput>(turnPath(session, turn) + '/output' + query({ after, limit: limit(pageLimit) }), 'GET', undefined, undefined, undefined, signal)
    }
    async finalOutput(session: string, turn: string, options: { signal?: AbortSignal; timeoutMs?: number } = {}): Promise<SessionEvent[]> {
        const timeout = options.timeoutMs ?? 120000
        if (!Number.isFinite(timeout) || timeout <= 0) throw new Error('Output timeout must be positive')
        const controller = new AbortController()
        const interrupt = () => controller.abort(new Error('Output wait interrupted; execution continues'))
        const timer = setTimeout(interrupt, timeout)
        options.signal?.addEventListener('abort', interrupt, { once: true })
        if (options.signal?.aborted) interrupt()
        const pause = () => new Promise<void>((resolve, reject) => {
            controller.signal.throwIfAborted()
            const abort = () => { clearTimeout(wait); reject(controller.signal.reason) }
            const wait = setTimeout(() => { controller.signal.removeEventListener('abort', abort); resolve() }, 250)
            controller.signal.addEventListener('abort', abort, { once: true })
        })
        const read = async <T>(operation: () => Promise<T>): Promise<T> => {
            while (true) {
                controller.signal.throwIfAborted()
                try { return await operation() } catch (error) {
                    if ((error as { status?: number })?.status !== 503) throw error
                    await pause()
                }
            }
        }
        try {
            while (!['completed', 'failed', 'cancelled'].includes(String((await read(() => this.getTurn(session, turn, controller.signal))).status))) await pause()
            const result: SessionEvent[] = [], cursors = new Set<string>()
            let after = ''
            while (true) {
                const page = await read(() => this.output(session, turn, after, 100, controller.signal))
                if (!Array.isArray(page.data) || typeof page.output_available !== 'boolean' || (!page.output_available && (page.data.length > 0 || page.has_more)) || typeof page.has_more !== 'boolean' ||
                    (page.has_more ? !page.next_cursor || cursors.has(page.next_cursor) : page.next_cursor != null)) throw new Error('Invalid final output page')
                result.push(...page.data)
                if (!page.has_more) return result
                after = page.next_cursor!; cursors.add(after)
            }
        } finally { clearTimeout(timer); options.signal?.removeEventListener('abort', interrupt) }
    }
    inputs(session: string, turn: string, after = '', pageLimit = 100, filters: { status?: string; eventId?: string } = {}) {
        return this.transport.request<{ data: ManagedInputReceipt[]; next_cursor: string | null; has_more: boolean }>(turnPath(session, turn) + '/inputs' + query({ after, limit: limit(pageLimit), status: filters.status, event_id: filters.eventId }))
    }
    async inputReceipt(session: string, turn: string, input: string) {
        const receipt = await this.transport.request<ManagedInputReceipt>(turnPath(session, turn) + '/inputs/' + segment(input))
        if (receipt.turn_id !== turn) throw new Error('Input receipt belongs to another Turn')
        return receipt
    }
    checkpoints(session: string, options: { after?: string; through?: string; limit?: number } = {}) {
        return this.transport.request<ServiceData>(path(session) + '/checkpoints' + query({ ...options, limit: limit(options.limit ?? 20, 100) }))
    }
    checkpoint(session: string, checkpoint: string) { return this.transport.request<ServiceData>(path(session) + '/checkpoints/' + segment(checkpoint)) }
    restoreCheckpoint(session: string, checkpoint: string, key: string) {
        segment(checkpoint)
        return this.transport.request<ServiceData>(path(session) + '/checkpoints/restore', 'POST', { checkpoint_id: checkpoint }, retryKey(key))
    }
    forkCheckpoint(session: string, target: string, checkpoint: string, key: string) {
        segment(target); segment(checkpoint)
        if (session === target) throw new Error('Fork target must differ')
        return this.transport.request<ServiceData>(path(session) + '/fork', 'POST', { checkpoint_id: checkpoint, target_session_id: target }, retryKey(key))
    }
    async checkpointOperation(session: string, operation: string) {
        const receipt = await this.transport.request<ServiceData>(path(session) + '/checkpoint-operations/' + segment(operation))
        if (receipt.source_session_id !== session) throw new Error('Checkpoint operation belongs to another Session')
        return receipt
    }
    cancelCheckpointOperation(session: string, operation: string) {
        return this.transport.request<ServiceData>(path(session) + '/checkpoint-operations/' + segment(operation) + '/cancel', 'POST')
    }
    capabilities(session: string) { return this.transport.request<ServiceData>(path(session) + '/capabilities') }
    webhook(session: string, url: string, key: string, eventTypes?: string[]) {
        return this.transport.request<ServiceData>(path(session) + '/webhooks', 'POST', { url, ...(eventTypes ? { event_types: eventTypes } : {}) }, retryKey(key))
    }
    webhooks(session: string, after = '', pageLimit = 100) {
        return this.transport.request<ServiceData>(path(session) + '/webhooks' + query({ after, limit: limit(pageLimit, 100) }))
    }
    webhookDeliveries(session: string, webhook: string, after = '', pageLimit = 100) {
        return this.transport.request<ServiceData>(path(session) + '/webhooks/' + segment(webhook) + '/deliveries' + query({ after, limit: limit(pageLimit, 100) }))
    }
    deleteWebhook(session: string, webhook: string) { return this.transport.request<ServiceData>(path(session) + '/webhooks/' + segment(webhook), 'DELETE') }
    retryWebhook(session: string, webhook: string) { return this.transport.request<ServiceData>(path(session) + '/webhooks/' + segment(webhook) + '/retry', 'POST', {}) }
    turnCapabilities(session: string, turn: string) { return this.transport.request<ServiceData>(turnPath(session, turn) + '/capabilities') }
    snapshot(session: string, turn = '', pageLimit = 100) {
        return this.transport.request<ServiceSnapshot>(scopePath(session, turn) + '/snapshot' + query({ limit: limit(pageLimit) }))
    }
    resources(session: string, snapshotId: string, kind: string, after = '', pageLimit = 100) {
        return this.transport.request<ServiceData>(path(session) + '/resources/' + segment(kind) + query({ snapshot_id: snapshotId, after, limit: limit(pageLimit) }))
    }
    events(session: string, page = '', pageLimit = 100, order = 'asc') {
        return this.transport.request<{ data: ManagedEvent[]; next_page: string | null }>(path(session) + '/events' + query({ page, order, limit: limit(pageLimit) }))
    }
    history(session: string, options: { page?: string; limit?: number; order?: 'asc' | 'desc'; types?: string[]; createdAt?: Partial<Record<'gt' | 'gte' | 'lt' | 'lte', string>> } = {}) {
        const parameters = new URLSearchParams({ limit: String(limit(options.limit ?? 100)), order: options.order ?? 'asc' })
        if (options.page) parameters.set('page', options.page)
        for (const type of options.types ?? []) parameters.append('types[]', type)
        for (const [operator, at] of Object.entries(options.createdAt ?? {})) if (at) parameters.set('created_at[' + operator + ']', at)
        return this.transport.request<{ data: SessionEvent[]; next_page: string | null }>(path(session) + '/events?' + parameters)
    }
    async *stream(session: string, after = '', signal?: AbortSignal): AsyncGenerator<ManagedEvent> {
        if (after) throw new Error('unsupported_capability: SSE resume is not supported')
        for await (const event of this.transport.stream(path(session) + '/events/stream', '', signal)) {
            if ((event as unknown as SessionEvent).managed_agents?.session_id !== session) throw new Error('Event belongs to another Session')
            yield event as unknown as ManagedEvent
        }
    }
    usage(session: string, turn = '') { return this.transport.request<ServiceData>(scopePath(session, turn) + '/usage') }
    budget(session: string) { return this.transport.request<ServiceData>(path(session) + '/budget') }
    setBudget(session: string, limits: ServiceData, revision?: number) {
        if (revision !== undefined && (!Number.isSafeInteger(revision) || revision < 1)) throw new Error('Budget revision must be positive')
        return this.transport.request<ServiceData>(path(session) + '/budget', 'PUT', limits, undefined, revision)
    }
    toolTasks(session: string, turn: string, after = '', pageLimit = 100) {
        return this.transport.request<ServiceData>(path(session) + '/tool-tasks' + query({ turn, after, limit: limit(pageLimit) }))
    }
    lifecycle(session: string, kind: 'archive' | 'restore' | 'delete', key: string) {
        if (!['archive', 'restore', 'delete'].includes(kind)) throw new Error('Unsupported lifecycle kind')
        return this.transport.request<ServiceData>(path(session) + (kind === 'delete' ? '' : '/' + kind), kind === 'delete' ? 'DELETE' : 'POST', undefined, retryKey(key))
    }
    async lifecycleReceipt(session: string, command: string) {
        const receipt = await this.transport.request<ManagedLifecycleReceipt>(path(session) + '/lifecycle/' + segment(command))
        if (receipt.session_id !== session) throw new Error('Lifecycle receipt belongs to another Session')
        return receipt
    }
    uploadFile(session: string, name: string, data: Uint8Array, key: string, contentType = 'application/octet-stream') {
        return this.transport.upload(path(session) + '/files', name, data, retryKey(key), contentType)
    }
    files(session: string, offset = 0, pageLimit = 100) { return this.transport.request<ServiceData>(path(session) + '/files' + query({ offset, limit: limit(pageLimit, 100) })) }
    fileMetadata(session: string, file: string) { return this.transport.request<ServiceData>(path(session) + '/files/' + segment(file)) }
    fileContent(session: string, file: string) { return this.transport.content(path(session) + '/files/' + segment(file) + '/content') }
    publishArtifact(session: string, artifact: ServiceData, key: string) { return this.transport.request<ServiceData>(path(session) + '/artifacts', 'POST', artifact, retryKey(key)) }
    artifacts(session: string, options: { turn?: string; after?: string; snapshotId?: string; limit?: number } = {}) {
        return this.transport.request<ServiceData>(path(session) + '/artifacts' + query({ turn: options.turn, after: options.after, snapshot_id: options.snapshotId, limit: limit(options.limit ?? 100) }))
    }
    artifactMetadata(session: string, artifact: string, snapshotId = '') { return this.transport.request<ServiceData>(path(session) + '/artifacts/' + segment(artifact) + query({ snapshot_id: snapshotId })) }
    artifactContent(session: string, artifact: string) { return this.transport.content(path(session) + '/artifacts/' + segment(artifact) + '/content') }
    deleteArtifact(session: string, artifact: string) { return this.transport.request<void>(path(session) + '/artifacts/' + segment(artifact), 'DELETE') }
}

export interface SessionEvent { id: string; type: string; processed_at: string | null; content?: Array<{ type: 'text'; text: string }>; managed_agents?: Record<string, unknown> }
export interface TurnOutput { data: SessionEvent[]; output_available: boolean; turn_status: string; next_cursor: string | null; has_more: boolean }
function inputContent(input: ServiceData): TextInputBlock[] {
    if (Object.keys(input).some(key => !['message', 'input'].includes(key)) || ('message' in input) === ('input' in input)) throw new Error('Provide exactly one of message or input; unsupported input fields')
    if (typeof input.message === 'string') return [{ type: 'text', text: input.message }]
    const messages = input.input as Array<{ role: string; content: unknown }>
    if (!Array.isArray(messages) || messages.length !== 1 || messages[0].role !== 'user' || Object.keys(messages[0]).some(key => !['role', 'content'].includes(key))) throw new Error('A single user message is required')
    const blocks = messages[0].content as TextInputBlock[]
    if (!Array.isArray(blocks) || !blocks.length || blocks.some(block => block.type !== 'text' || typeof block.text !== 'string' || Object.keys(block).some(key => !['type', 'text'].includes(key)))) throw new Error('Only text content is supported')
    return blocks
}
function eventReceipt(response: { data: SessionEvent[] }): ServiceData {
    const event = response.data[0], ext = event.managed_agents!
    return { ...(ext.receipt as ServiceData), event_id: event.id, turn_id: ext.turn_id, ...(ext.input_id ? { input_type: ext.intent } : {}) }
}

export interface TextInputBlock { type: 'text'; text: string }
export type SessionInputEvent =
    | { type: 'user.message'; content: TextInputBlock[]; managed_agents?: { intent: 'new_turn'; turn_id?: never } | { intent: 'steer' | 'inject'; turn_id: string } }
    | { type: 'user.interrupt'; managed_agents?: { intent: 'cancel'; turn_id: string } }
