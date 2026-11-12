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
import assert from 'node:assert/strict'
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { test } from 'node:test'
import { ServiceClient } from '../src/service-client.ts'

async function withServer(handler: (request: IncomingMessage, response: ServerResponse) => void, work: (client: ServiceClient) => Promise<void>) {
    const server = createServer(handler)
    await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
    const address = server.address()
    assert.ok(address && typeof address !== 'string')
    try { await work(new ServiceClient(`http://127.0.0.1:${address.port}`, { apiKey: 'original-key', tenant: 'tenant', namespace: 'namespace' })) }
    finally { server.closeAllConnections(); await new Promise<void>(resolve => server.close(() => resolve())) }
}
function identity(request: IncomingMessage) {
    assert.equal(request.headers['x-api-key'], 'original-key')
    assert.equal(request.headers['x-managed-agents-tenant'], 'tenant')
    assert.equal(request.headers['x-managed-agents-namespace'], 'namespace')
    assert.equal(request.headers['x-managed-agents-internal-token'], undefined)
}

test('Native webhooks retain keys, filters, identities and bounded delivery pages', { timeout: 10_000 }, async () => {
    const calls: { path: string; method: string; body?: unknown; key?: string | string[] }[] = []
    await withServer((request, response) => {
        identity(request)
        const chunks: Buffer[] = []
        request.on('data', chunk => chunks.push(chunk))
        request.on('end', () => {
            const text = Buffer.concat(chunks).toString()
            calls.push({ path: request.url!, method: request.method!, body: text ? JSON.parse(text) : undefined, key: request.headers['idempotency-key'] })
            response.end('{}')
        })
    }, async transport => {
        const client = transport.managedSessions()
        await client.webhook('session/one', 'https://receiver.example/events', 'hook-key', ['tool.operation.completed'])
        assert.equal(calls.at(-1)?.path, '/v1/sessions/session%2Fone/webhooks')
        assert.equal(calls.at(-1)?.key, 'hook-key')
        assert.deepEqual(calls.at(-1)?.body, { url: 'https://receiver.example/events', event_types: ['tool.operation.completed'] })
        await client.webhooks('session', 'a'.repeat(64), 25)
        assert.equal(new URL(calls.at(-1)!.path, 'https://fixture').searchParams.get('limit'), '25')
        await client.webhookDeliveries('session', 'hook/one')
        assert.equal(calls.at(-1)?.path, '/v1/sessions/session/webhooks/hook%2Fone/deliveries?limit=100')
        await client.retryWebhook('session', 'hook')
        assert.equal(calls.at(-1)?.path, '/v1/sessions/session/webhooks/hook/retry')
        assert.equal(calls.at(-1)?.method, 'POST')
        await client.deleteWebhook('session', 'hook')
        assert.equal(calls.at(-1)?.method, 'DELETE')
        assert.throws(() => client.webhooks('session', '', 101), /page limit/)
        assert.throws(() => client.webhook('session', 'url', ''), /Idempotency-Key/)
    })
})

test('Managed admission and controls use typed Inputs and explicit lifecycle retry keys', { timeout: 10_000 }, async () => {
    const calls: { path: string; body: unknown; key?: string | string[] }[] = []
    await withServer((request, response) => {
        identity(request)
        const chunks: Buffer[] = []
        request.on('data', chunk => chunks.push(chunk))
        request.on('end', () => {
            const text = Buffer.concat(chunks).toString()
            calls.push({ path: request.url!, body: text ? JSON.parse(text) : undefined, key: request.headers['idempotency-key'] })
            response.end(JSON.stringify({ turn_id: 'turn', session_id: 'session', status: 'accepted', data: [{ id: 'event', managed_agents: { turn_id: 'turn', intent: 'new_turn', receipt: { id: 'turn', status: 'accepted' } } }] }))
        })
    }, async transport => {
        const client = transport.managedSessions()
        await client.createSession({ agent: 'agent', environment_id: 'environment' }, 'create')
        assert.equal(calls[0].path, '/v1/sessions')
        assert.deepEqual(calls[0].body, { agent: 'agent', environment_id: 'environment' })
        await client.submit('session/one', { message: 'hello' }, 'turn-key')
        assert.equal(calls[1].path, '/v1/sessions/session%2Fone/events')
        assert.deepEqual(calls[1].body, { events: [{ type: 'user.message', content: [{ type: 'text', text: 'hello' }], managed_agents: { intent: 'new_turn' } }] })
        await assert.rejects(client.input('session', 'turn', 'reply', { allow: true }, 'reply-key', 'action'), /unsupported_capability/)
        await client.steer('session', 'turn', 'redirect', 'steer')
        assert.deepEqual((calls[2].body as { events: Array<{managed_agents: unknown}> }).events[0].managed_agents, { intent: 'steer', turn_id: 'turn' })
        assert.equal((await client.inputReceipt('session', 'turn', 'input')).status, 'accepted')
        await assert.rejects(client.inputReceipt('session', 'other-turn', 'input'), /another Turn/)
        await client.lifecycle('session', 'archive', 'archive-key')
        assert.equal(calls.at(-1)?.key, 'archive-key')
        assert.equal((await client.lifecycleReceipt('session', 'command')).session_id, 'session')
        await assert.rejects(client.lifecycleReceipt('other-session', 'command'), /another Session/)
        assert.throws(() => client.lifecycle('session', 'delete', ''), /Idempotency-Key/)
        assert.throws(() => client.getSession('..'), /path identifier/)
        await transport.getSession('external')
        assert.equal(calls.at(-1)?.path, '/api/v1/agent-sessions/external')
    })
})

test('Checkpoint writes retain original identity, retry keys and operation scope', { timeout: 10_000 }, async () => {
    const calls: { path: string; body: unknown; key?: string | string[] }[] = []
    await withServer((request, response) => {
        identity(request)
        const chunks: Buffer[] = []
        request.on('data', chunk => chunks.push(chunk))
        request.on('end', () => {
            const body = Buffer.concat(chunks).toString()
            calls.push({ path: request.url!, body: body ? JSON.parse(body) : undefined, key: request.headers['idempotency-key'] })
            response.end('{"source_session_id":"session","status":"validating"}')
        })
    }, async transport => {
        const client = transport.managedSessions()
        await client.restoreCheckpoint('session/one', 'checkpoint', 'restore-key')
        assert.equal(calls.at(-1)?.path, '/v1/sessions/session%2Fone/checkpoints/restore')
        assert.equal(calls.at(-1)?.key, 'restore-key')
        assert.deepEqual(calls.at(-1)?.body, { checkpoint_id: 'checkpoint' })
        await client.forkCheckpoint('session', 'target', 'checkpoint', 'fork-key')
        assert.deepEqual(calls.at(-1)?.body, { checkpoint_id: 'checkpoint', target_session_id: 'target' })
        assert.equal(calls.at(-1)?.key, 'fork-key')
        assert.equal((await client.checkpointOperation('session', 'operation')).status, 'validating')
        await assert.rejects(client.checkpointOperation('other', 'operation'), /another Session/)
        assert.throws(() => client.forkCheckpoint('same', 'same', 'checkpoint', 'key'), /must differ/)
        assert.throws(() => client.restoreCheckpoint('session', 'checkpoint', ''), /Idempotency-Key/)
        await client.cancelCheckpointOperation('session', 'operation')
        assert.equal(calls.at(-1)?.path, '/v1/sessions/session/checkpoint-operations/operation/cancel')
    })
})

test('Managed binary files and pinned pages share the original bounded transport', { timeout: 10_000 }, async () => {
    const bytes = Buffer.from([0, 1, 255, 13, 10])
    let url = ''
    await withServer((request, response) => {
        identity(request); url = request.url!
        if (request.method === 'POST') {
            assert.equal(request.headers['x-file-name'], '%E6%96%87%E4%BB%B6%20name.bin')
            assert.equal(request.headers['idempotency-key'], 'file-key')
            const chunks: Buffer[] = []
            request.on('data', chunk => chunks.push(chunk))
            request.on('end', () => { assert.deepEqual(Buffer.concat(chunks), bytes); response.end('{"file_id":"file"}') })
        } else if (url.endsWith('/content')) response.end(bytes)
        else response.end('{}')
    }, async transport => {
        const client = transport.managedSessions()
        assert.equal((await client.uploadFile('session', '文件 name.bin', bytes, 'file-key')).file_id, 'file')
        assert.equal(url, '/v1/sessions/session/files')
        assert.deepEqual(Buffer.from(await client.fileContent('session', 'file')), bytes)
        await client.resources('session', 'snapshot', 'artifacts', 'opaque+/=', 256)
        let query = new URL(url, 'http://fixture').searchParams
        assert.equal(query.get('snapshot_id'), 'snapshot'); assert.equal(query.get('after'), 'opaque+/=')
        await client.artifacts('session', { turn: 'turn', snapshotId: 'snapshot' })
        query = new URL(url, 'http://fixture').searchParams
        assert.equal(query.get('turn'), 'turn')
        assert.equal(query.get('snapshot_id'), 'snapshot')
        await assert.rejects(client.uploadFile('session', 'large', new Uint8Array((16 << 20) + 1), 'key'), /16 MiB/)
    })
})

test('Managed SSE replays Session cursors and rejects a foreign Session event', { timeout: 10_000 }, async () => {
    let owner = 'session'
    await withServer((request, response) => {
        identity(request)
        assert.equal(request.url, '/v1/sessions/session/events/stream')
        assert.equal(request.headers['last-event-id'], undefined)
        response.writeHead(200, { 'Content-Type': 'text/event-stream' })
        response.end(': keepalive\n\ndata: ' + JSON.stringify({ id: 'event', type: 'managed_agents.turn.completed', processed_at: '2026-10-19T00:00:00Z', managed_agents: { session_id: owner } }) + '\n\n')
    }, async transport => {
        const client = transport.managedSessions()
        const events = []
        for await (const event of client.stream('session')) events.push(event)
        assert.equal(events[0].id, 'event')
        owner = 'other-session'
        await assert.rejects(async () => { for await (const event of client.stream('session')) assert.fail(event.id) }, /another Session/)
    })
})

test('Managed checkpoint handles and pinned pages use the native query protocol', async () => {
    const urls: string[] = []
    await withServer((request, response) => {
        identity(request)
        urls.push(request.url!)
        response.end('{"data":[],"checkpoint_id":"checkpoint","as_of":"head","next_cursor":null,"has_more":false}')
    }, async transport => {
        const client = transport.managedSessions()
        await client.checkpoints('session', { after: 'page', through: 'head', limit: 1 })
        await client.checkpoint('session', 'checkpoint/one')
        assert.deepEqual(urls, ['/v1/sessions/session/checkpoints?after=page&through=head&limit=1', '/v1/sessions/session/checkpoints/checkpoint%2Fone'])
        assert.throws(() => client.checkpoints('session', { limit: 101 }), /page limit/)
    })
})

test('Turn/Input filters use wire names and final output retries only reads and detects cursor cycles', { timeout: 10_000 }, async () => {
    const calls: URL[] = []; let unavailable = true; let cycle = false
    await withServer((request, response) => {
        assert.equal(request.method, 'GET'); const url = new URL(request.url!, 'http://fixture'); calls.push(url)
        if (url.pathname.endsWith('/output')) {
            if (unavailable) { unavailable = false; response.writeHead(503).end('{}'); return }
            const more = cycle || !url.searchParams.has('after')
            response.end(JSON.stringify({ data: [{ id: 'answer' }], output_available: true, next_cursor: more ? 'cursor' : null, has_more: more }))
        } else response.end('{"status":"completed"}')
    }, async transport => {
        const client = transport.managedSessions()
        await client.turns('s', { inputEventId: 'evt', status: 'completed' })
        assert.equal(calls.at(-1)!.searchParams.get('input_event_id'), 'evt'); assert.equal(calls.at(-1)!.searchParams.has('inputEventId'), false)
        await client.inputs('s', 't', '', 20, { eventId: 'input', status: 'applied' })
        assert.equal(calls.at(-1)!.searchParams.get('event_id'), 'input'); assert.equal(calls.at(-1)!.searchParams.get('status'), 'applied')
        assert.equal((await client.finalOutput('s', 't')).length, 2)
        const outputCalls = calls.filter(url => url.pathname.endsWith('/output'))
        assert.equal(outputCalls[0].href, outputCalls[1].href)
        cycle = true
        await assert.rejects(client.finalOutput('s', 't'), /Invalid final output page/)
        await assert.rejects(client.submit('s', { message: 'text', input: [] }), /exactly one/)
        await assert.rejects(client.submit('s', { message: 'text', durable: false }), /unsupported input fields/)
        const before = calls.length, controller = new AbortController(); controller.abort()
        await assert.rejects(client.finalOutput('s', 't', { signal: controller.signal }), /interrupted/)
        assert.equal(calls.length, before)
    })
})

test('Final output timeout aborts an in-flight read without submitting a command', { timeout: 10_000 }, async () => {
    await withServer((request) => { assert.equal(request.method, 'GET') }, async transport => {
        const start = Date.now()
        await assert.rejects(transport.managedSessions().finalOutput('s', 't', { timeoutMs: 50 }))
        assert.ok(Date.now() - start < 1000)
    })
})


test('terminal Turn without output completes with an empty result', async () => {
    await withServer((request, response) => response.end(request.url!.includes('/output') ? '{"data":[],"output_available":false,"has_more":false,"next_cursor":null}' : '{"status":"failed"}'), async transport => {
        assert.deepEqual(await transport.managedSessions().finalOutput('s', 't'), [])
    })
})
