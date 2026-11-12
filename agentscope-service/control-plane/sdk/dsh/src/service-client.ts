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

import { ManagedSessionClient } from './managed-session-client.js'

export type ServiceData = Record<string, unknown>
export interface ServiceEvent { schema_version: number; id: string; cursor: string; type: string; data: ServiceData }
export interface ServicePage { data: ServiceEvent[]; snapshot_id: string; as_of: string; next_cursor: string | null; has_more: boolean }
export interface ServiceSnapshot { snapshot_id: string; as_of: string; resources: Record<string, ServicePage>; usage: ServiceData }
export interface ServiceCredentials { apiKey?: string; apiToken?: string; tenant?: string; namespace?: string; timeoutMs?: number }
export class ServiceAPIError extends Error {
    constructor(readonly status: number, readonly detail: unknown) { super(`Service API ${status}: ${JSON.stringify(detail)}`) }
}

/** Agent Service client carrying only the original user or Application identity. */
export class ServiceClient {
    private readonly base: string
    private readonly timeout: number
    constructor(baseUrl: string, private readonly credentials: ServiceCredentials = {}) {
        if (credentials.apiKey && credentials.apiToken) throw new Error('Provide apiKey or apiToken, not both')
        this.base = baseUrl.replace(/\/$/, '')
        this.timeout = credentials.timeoutMs ?? 30_000
        if (!Number.isFinite(this.timeout) || this.timeout < 1) throw new Error('timeoutMs must be positive')
    }
    private headers(): Headers {
        const value = new Headers()
        if (this.credentials.apiKey) value.set('X-API-Key', this.credentials.apiKey)
        if (this.credentials.apiToken) value.set('Authorization', 'Bearer ' + this.credentials.apiToken)
        if (this.credentials.tenant) value.set('X-Managed-Agents-Tenant', this.credentials.tenant)
        if (this.credentials.namespace) value.set('X-Managed-Agents-Namespace', this.credentials.namespace)
        return value
    }
    private session(id: string) { return '/api/v1/agent-sessions/' + encodeURIComponent(id) }
    managedSessions() {
        return new ManagedSessionClient({
            request: <T>(path: string, method?: string, body?: unknown, key?: string, revision?: number, signal?: AbortSignal) => this.request<T>(path, method, body, key, revision, signal),
            content: async path => readBytes(await this.fetch(path), 100 << 20),
            upload: (path, name, data, key, type) => this.upload(path, name, data, key, type),
            stream: (path, after, signal) => this.eventStream(path, after, signal),
        })
    }
    private turn(session: string, id: string) { return this.session(session) + '/turns/' + encodeURIComponent(id) }
    private async fetch(path: string, method = 'GET', body?: unknown, key?: string, signal?: AbortSignal, revision?: number) {
        const headers = this.headers()
        if (path.startsWith('/v1/sessions')) headers.set('Managed-Agents-Extensions', 'turn-inputs-v1')
        if (body !== undefined) headers.set('Content-Type', 'application/json')
        if (key) headers.set('Idempotency-Key', key)
        if (revision !== undefined) headers.set('If-Match', String(revision))
        const deadline = AbortSignal.timeout(this.timeout)
        const response = await fetch(this.base + path, { method, headers, redirect: 'error', body: body === undefined ? undefined : JSON.stringify(body), signal: combineSignal(deadline, signal) })
        if (!response.ok) {
            const text = new TextDecoder().decode(await readBytes(response, 1 << 20))
            let detail: unknown = text
            try { detail = JSON.parse(text) } catch { /* Plain HTTP error. */ }
            throw new ServiceAPIError(response.status, detail)
        }
        return response
    }
    private async request<T>(path: string, method = 'GET', body?: unknown, key?: string, revision?: number, signal?: AbortSignal): Promise<T> {
        const response = await this.fetch(path, method, body, key, signal, revision)
        const text = new TextDecoder().decode(await readBytes(response, 16 << 20))
        return (text ? JSON.parse(text) : undefined) as T
    }
    createSession(target: ServiceData, idempotencyKey: string, options: ServiceData = {}) { return this.request<ServiceData>('/api/v1/agent-sessions', 'POST', { ...options, target }, idempotencyKey) }
    getSession(session: string) { return this.request<ServiceData>(this.session(session)) }
    /** Native history requires an enabled human trace credential. */
    trace(session: string, after = 0, through?: number, limit = 100) {
        if (!Number.isSafeInteger(after) || after < 0 || !Number.isInteger(limit) || limit < 1 || limit > 100
            || through !== undefined && (!Number.isSafeInteger(through) || through < after)) throw new Error('Invalid trace window')
        const query = new URLSearchParams({ after: String(after), limit: String(limit) })
        if (through !== undefined) query.set('through', String(through))
        return this.request<ServiceData>(this.session(session) + '/trace?' + query)
    }
    checkpoints(session: string, after = '', through = '', limit = 20) {
        if (!Number.isInteger(limit) || limit < 1 || limit > 100) throw new Error('Invalid checkpoint page limit')
        const query = new URLSearchParams({ limit: String(limit) })
        if (after) query.set('after', after)
        if (through) query.set('through', through)
        return this.request<ServiceData>(this.session(session) + '/checkpoints?' + query)
    }
    restoreCheckpoint(session: string, checkpoint: string, idempotencyKey: string) {
        return this.request<ServiceData>(this.session(session) + '/checkpoints/restore', 'POST', { checkpoint_id: checkpoint }, idempotencyKey)
    }
    fork(session: string, target: string, checkpoint: string, idempotencyKey: string) {
        return this.request<ServiceData>(this.session(session) + '/fork', 'POST', { target_session_id: target, checkpoint_id: checkpoint }, idempotencyKey)
    }
    async checkpointOperation(session: string, operation: string) {
        const receipt = await this.request<ServiceData>(this.session(session) + '/checkpoint-operations/' + encodeURIComponent(operation))
        if (receipt.source_session_id !== session || receipt.operation_id !== operation) throw new Error('Checkpoint operation identity differs')
        return receipt
    }
    cancelCheckpointOperation(session: string, operation: string) {
        return this.request<ServiceData>(this.session(session) + '/checkpoint-operations/' + encodeURIComponent(operation) + '/cancel', 'POST')
    }
    budget(session: string) { return this.request<ServiceData>(this.session(session) + '/budget') }
    /** The current revision prevents overwriting another caller's desired limits. */
    setBudget(session: string, limits: ServiceData, revision?: number) {
        if (revision !== undefined && (!Number.isSafeInteger(revision) || revision < 1)) throw new Error('Budget revision must be a positive integer')
        return this.request<ServiceData>(this.session(session) + '/budget', 'PUT', limits, undefined, revision)
    }
    sessions(after = '', limit = 50, status = 'active') { return this.request<ServiceData>('/api/v1/agent-sessions?' + new URLSearchParams({ after, limit: String(limit), status })) }
    submit(session: string, input: ServiceData, idempotencyKey: string) { return this.request<ServiceData>(this.session(session) + '/turns', 'POST', input, idempotencyKey) }
    getTurn(session: string, turn: string) { return this.request<ServiceData>(this.turn(session, turn)) }
    turns(session: string, after = '', limit = 50, order = 'asc') { return this.request<ServiceData>(this.session(session) + '/turns?' + new URLSearchParams({ after, limit: String(limit), order })) }
    async capabilities(session: string) {
        const value = await this.request<{ capabilities: Record<string, boolean> }>(this.session(session) + '/capabilities')
        if (!value?.capabilities || ['files', 'file_input', 'artifacts'].some(name => typeof value.capabilities[name] !== 'boolean')) throw new Error('Unsupported Session capabilities schema; use matching Service and client versions')
        return value
    }
    turnCapabilities(session: string, turn: string) { return this.request<ServiceData>(this.turn(session, turn) + '/capabilities') }
    async snapshot(session: string, turn = '', limit = 100) {
        const value = await this.request<ServiceSnapshot>((turn ? this.turn(session, turn) : this.session(session)) + '/snapshot?limit=' + limit)
        if (!value?.snapshot_id || typeof value.as_of !== 'string' || !value.resources) throw new Error('Unsupported Session snapshot schema; use matching Service and client versions')
        return value
    }
    resources(session: string, kind: string, options: { turn?: string; after?: string; snapshotId?: string; limit?: number } = {}) {
        const query = new URLSearchParams({ limit: String(options.limit ?? 100) })
        if (options.after) query.set('after', options.after)
        if (options.snapshotId) query.set('snapshot_id', options.snapshotId)
        return this.request<ServicePage>((options.turn ? this.turn(session, options.turn) : this.session(session)) + '/resources/' + encodeURIComponent(kind) + '?' + query)
    }
    events(session: string, after = '', limit = 100, turn = '') { return this.request<{ data: ServiceEvent[]; next_cursor: string; has_more: boolean }>((turn ? this.turn(session, turn) : this.session(session)) + '/events?' + new URLSearchParams({ after, limit: String(limit) })) }
    command(session: string, turn: string, kind: 'actions' | 'steer' | 'cancel' | 'resume' | 'tool-recovery' | 'workspace-recovery', payload: ServiceData, idempotencyKey: string) {
        if (kind === 'actions' && !Array.isArray(payload.answers)) throw new Error('actions requires an answers list')
        return this.request<ServiceData>(this.turn(session, turn) + '/' + kind, 'POST', payload, idempotencyKey)
    }
    inject(session: string, input: ServiceData, idempotencyKey: string) { return this.request<ServiceData>(this.session(session) + '/inputs/inject', 'POST', input, idempotencyKey) }
    async commandStatus(session: string, turn: string, command: string) {
        const value = await this.request<ServiceData>(this.session(session) + '/commands/' + encodeURIComponent(command))
        if (value.turn_id !== turn) throw new Error('Command does not belong to this Turn')
        return value
    }
    async usage(session: string, turn = '') { return (await this.request<{ data: ServiceData }>((turn ? this.turn(session, turn) : this.session(session)) + '/usage')).data }
    publishArtifact(session: string, artifact: ServiceData, idempotencyKey: string) { return this.request<ServiceData>(this.session(session) + '/artifacts', 'POST', artifact, idempotencyKey) }
    deleteArtifact(session: string, artifact: string) { return this.request<ServiceData>(this.session(session) + '/artifacts/' + encodeURIComponent(artifact), 'DELETE') }
    async artifactContent(session: string, artifact: string, turn = '') { return readBytes(await this.fetch((turn ? this.turn(session, turn) : this.session(session)) + '/artifacts/' + encodeURIComponent(artifact) + '/content'), 100 << 20) }
    files(session: string, offset = 0, limit = 100) { return this.request<ServiceData>(this.session(session) + '/files?' + new URLSearchParams({ offset: String(offset), limit: String(limit) })) }
    fileMetadata(session: string, file: string) { return this.request<ServiceData>(this.session(session) + '/files/' + encodeURIComponent(file)) }
    async fileContent(session: string, file: string) { return readBytes(await this.fetch(this.session(session) + '/files/' + encodeURIComponent(file) + '/content'), 100 << 20) }
    async uploadFile(session: string, name: string, data: Uint8Array, idempotencyKey: string, contentType = 'application/octet-stream') {
        return this.upload(this.session(session) + '/files', name, data, idempotencyKey, contentType)
    }
    private async upload(path: string, name: string, data: Uint8Array, idempotencyKey: string, contentType: string) {
        if (data.byteLength > 16 << 20) throw new Error('Upload exceeds the 16 MiB client limit')
        const headers = this.headers()
        headers.set('Content-Type', contentType); headers.set('X-File-Name', encodeURIComponent(name)); headers.set('Idempotency-Key', idempotencyKey)
        const response = await fetch(this.base + path, { method: 'POST', headers, body: new Uint8Array(data), redirect: 'error', signal: AbortSignal.timeout(this.timeout) })
        const text = new TextDecoder().decode(await readBytes(response, response.ok ? 16 << 20 : 1 << 20))
        if (!response.ok) throw new ServiceAPIError(response.status, text)
        return JSON.parse(text) as ServiceData
    }
    artifacts(session: string, turn = '', after = '', snapshotId = '', limit = 100) { return this.resources(session, 'artifacts', { turn, after, snapshotId, limit }) }
    artifactMetadata(session: string, artifact: string, turn = '', snapshotId = '') { return this.request<ServiceData>((turn ? this.turn(session, turn) : this.session(session)) + '/artifacts/' + encodeURIComponent(artifact) + (snapshotId ? '?' + new URLSearchParams({ snapshot_id: snapshotId }) : '')) }
    lifecycle(session: string, kind: 'archive' | 'restore' | 'delete') { return this.request<ServiceData>(this.session(session) + (kind === 'delete' ? '' : '/' + kind), kind === 'delete' ? 'DELETE' : 'POST', kind === 'delete' ? undefined : {}) }
    webhook(session: string, url: string, idempotencyKey: string, eventTypes?: string[]) { return this.request<ServiceData>(this.session(session) + '/webhooks', 'POST', { url, ...(eventTypes ? { event_types: eventTypes } : {}) }, idempotencyKey) }
    webhooks(session: string, after?: string, limit = 100) { return this.request<ServiceData>(this.session(session) + '/webhooks?' + new URLSearchParams({ limit: String(limit), ...(after ? { after } : {}) })) }
    webhookDeliveries(session: string, webhook: string, after?: string, limit = 100) { return this.request<ServiceData>(this.session(session) + '/webhooks/' + encodeURIComponent(webhook) + '/deliveries?' + new URLSearchParams({ limit: String(limit), ...(after ? { after } : {}) })) }
    deleteWebhook(session: string, webhook: string) { return this.request<ServiceData>(this.session(session) + '/webhooks/' + encodeURIComponent(webhook), 'DELETE') }
    retryWebhook(session: string, webhook: string) { return this.request<ServiceData>(this.session(session) + '/webhooks/' + encodeURIComponent(webhook) + '/retry', 'POST', {}) }
    async *stream(session: string, after = '', turn = '', signal?: AbortSignal): AsyncGenerator<ServiceEvent> {
        yield* this.eventStream((turn ? this.turn(session, turn) : this.session(session)) + '/events/stream', after, signal)
    }
    private async *eventStream(path: string, after = '', signal?: AbortSignal): AsyncGenerator<ServiceEvent> {
        const headers = this.headers(); headers.set('Accept', 'text/event-stream')
        if (path.startsWith('/v1/sessions')) headers.set('Managed-Agents-Extensions', 'turn-inputs-v1')
        if (after) headers.set('Last-Event-ID', after)
        const deadline = AbortSignal.timeout(this.timeout)
        const response = await fetch(this.base + path, { headers, redirect: 'error', signal: combineSignal(deadline, signal) })
        if (!response.ok) throw new ServiceAPIError(response.status, { message: response.statusText })
        if (!response.body) throw new Error('Service stream has no body')
        const reader = response.body.getReader(), decoder = new TextDecoder()
        let pending = '', data: string[] = [], eventSize = 0
        try {
            while (true) {
                const chunk = await reader.read(); if (chunk.done) break
                pending += decoder.decode(chunk.value, { stream: true })
                if (pending.length > 1 << 20) throw new Error('Service event exceeds the 1 MiB client limit')
                let newline: number
                while ((newline = pending.indexOf('\n')) >= 0) {
                    const line = pending.slice(0, newline).replace(/\r$/, ''); pending = pending.slice(newline + 1)
                    if (!line) {
                        if (data.length) {
                            const event = JSON.parse(data.join('\n')) as ServiceEvent
                            if (typeof event.id !== 'string' || (path.startsWith('/v1/sessions') ? typeof event.type !== 'string' : event.schema_version !== 1 || typeof event.cursor !== 'string' || !event.data)) throw new Error('Unsupported Service event schema; use matching Service and client versions')
                            yield event
                        }
                        data = []; eventSize = 0
                    } else if (line.startsWith('data:')) {
                        const value = line.slice(5).replace(/^ /, ''); eventSize += value.length
                        if (eventSize > 1 << 20) throw new Error('Service event exceeds the 1 MiB client limit')
                        data.push(value)
                    }
                }
            }
        } finally { await reader.cancel().catch(() => undefined); reader.releaseLock() }
    }

    /** Use snapshot.as_of as through when an export must survive client restarts. */
    async *exportEvents(session: string, options: { after?: string; through?: string; turn?: string; limit?: number; signal?: AbortSignal } = {}): AsyncGenerator<ServiceEvent> {
        const limit = options.limit ?? 1000
        if (!Number.isInteger(limit) || limit < 1 || limit > 1000) throw new Error('limit must be 1..1000')
        let after = options.after ?? '', through = options.through ?? ''
        while (true) {
            const query = new URLSearchParams({ after, through, limit: String(limit) })
            const response = await this.fetch((options.turn ? this.turn(session, options.turn) : this.session(session)) + '/export?' + query, 'GET', undefined, undefined, options.signal)
            const end = response.headers.get('X-Export-Through'), next = response.headers.get('X-Next-Cursor'), more = response.headers.get('X-Has-More'), rawCount = response.headers.get('X-Export-Count')
            if (response.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase() !== 'application/x-ndjson' || !end || !next || !['true', 'false'].includes(more ?? '') || !rawCount || !/^\d+$/.test(rawCount) || Number(rawCount) > limit || (through && end !== through) || !response.body) {
                await response.body?.cancel(); throw new Error('Unsupported Service export schema')
            }
            through = end
            const reader = response.body.getReader(), decoder = new TextDecoder('utf-8', { fatal: true }), encoder = new TextEncoder()
            let pending = '', count = 0, last = after
            try {
                while (true) {
                    const chunk = await reader.read()
                    pending += decoder.decode(chunk.value, { stream: !chunk.done })
                    let newline: number
                    while ((newline = pending.indexOf('\n')) >= 0) {
                        const line = pending.slice(0, newline); pending = pending.slice(newline + 1)
                        if (encoder.encode(line).byteLength > 1 << 20) throw new Error('Service export event exceeds the 1 MiB client limit')
                        const event = JSON.parse(line) as ServiceEvent & { session_id?: string }
                        if (event.schema_version !== 1 || event.session_id !== session || typeof event.id !== 'string' || typeof event.cursor !== 'string' || typeof event.type !== 'string' || !event.data || typeof event.data !== 'object' || Array.isArray(event.data)) throw new Error('Unsupported Service export event schema')
                        if (++count > Number(rawCount)) throw new Error('Service export count mismatch')
                        last = event.cursor
                        yield event
                    }
                    if (encoder.encode(pending).byteLength > 1 << 20) throw new Error('Service export event exceeds the 1 MiB client limit')
                    if (chunk.done) break
                }
                if (pending || count !== Number(rawCount) || (more === 'true' && (!count || next !== last || next === after))) throw new Error('Incomplete Service export window')
            } finally { await reader.cancel().catch(() => undefined); reader.releaseLock() }
            if (more === 'false') {
                if (next !== through) throw new Error('Incomplete Service export watermark')
                return
            }
            after = next
        }
    }
}
async function readBytes(response: Response, limit: number): Promise<Uint8Array> {
    if (Number(response.headers.get('Content-Length')) > limit) { await response.body?.cancel(); throw new Error('Service response exceeds the client limit') }
    const reader = response.body?.getReader()
    if (!reader) return new Uint8Array()
    const chunks: Uint8Array[] = []; let size = 0
    try {
        while (true) {
            const chunk = await reader.read(); if (chunk.done) break
            size += chunk.value.byteLength
            if (size > limit) throw new Error('Service response exceeds the client limit')
            chunks.push(chunk.value)
        }
        const result = new Uint8Array(size); let offset = 0
        for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.byteLength }
        return result
    } finally { await reader.cancel().catch(() => undefined); reader.releaseLock() }
}

function combineSignal(deadline: AbortSignal, signal?: AbortSignal): AbortSignal {
    if (!signal) return deadline
    const controller = new AbortController()
    const forward = () => {
        controller.abort(signal.aborted ? signal.reason : deadline.reason)
        signal.removeEventListener('abort', forward); deadline.removeEventListener('abort', forward)
    }
    if (signal.aborted || deadline.aborted) forward()
    else { signal.addEventListener('abort', forward, { once: true }); deadline.addEventListener('abort', forward, { once: true }) }
    return controller.signal
}
