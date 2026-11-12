/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
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

async function withServer(handler: (request: IncomingMessage, response: ServerResponse) => void, work: (base: string) => Promise<void>) {
    const server = createServer(handler)
    await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
    const address = server.address()
    assert.ok(address && typeof address !== 'string')
    try { await work(`http://127.0.0.1:${address.port}`) }
    finally { server.closeAllConnections(); await new Promise<void>(resolve => server.close(() => resolve())) }
}

test('public file client carries original credentials, binary bytes and encoded names', async () => {
    const data = Buffer.from([0, 1, 255, 13, 10])
    await withServer((request, response) => {
        assert.equal(request.headers['x-api-key'], 'original-key')
        assert.equal(request.headers['x-managed-agents-internal-token'], undefined)
        if (request.method === 'POST') {
            assert.equal(request.headers['x-file-name'], '%E6%96%87%E4%BB%B6%20name.bin')
            assert.equal(request.headers['idempotency-key'], 'file-key')
            const chunks: Buffer[] = []
            request.on('data', chunk => chunks.push(chunk))
            request.on('end', () => {
                assert.deepEqual(Buffer.concat(chunks), data)
                response.end('{"id":"file-id"}')
            })
        } else if (request.url?.endsWith('/content')) response.end(data)
        else response.end('{"items":[{"id":"file-id"}]}')
    }, async base => {
        const client = new ServiceClient(base, { apiKey: 'original-key' })
        assert.equal((await client.uploadFile('session', '文件 name.bin', data, 'file-key')).id, 'file-id')
        assert.deepEqual(Buffer.from(await client.fileContent('session', 'file-id')), data)
        assert.equal((await client.files('session')).items instanceof Array, true)
        await assert.rejects(client.uploadFile('session', 'huge', new Uint8Array((16 << 20) + 1), 'key'), /16 MiB/)
    })
})

test('Checkpoint operations use public routes, exact request fields and source-bound receipts', async () => {
    let lastPath = ''
    let lastBody: Record<string, string> = {}
    await withServer((request, response) => {
        assert.equal(request.headers['x-api-key'], 'original-key')
        assert.equal(request.headers['x-managed-agents-internal-token'], undefined)
        const chunks: Buffer[] = []
        request.on('data', chunk => chunks.push(chunk))
        request.on('end', () => {
            lastPath = request.url!
            const text = Buffer.concat(chunks).toString()
            lastBody = text ? JSON.parse(text) : {}
            response.end('{"source_session_id":"session","operation_id":"operation"}')
        })
    }, async base => {
        const client = new ServiceClient(base, { apiKey: 'original-key' })
        await client.restoreCheckpoint('session', 'checkpoint_saved', 'restore-key')
        assert.equal(lastPath, '/api/v1/agent-sessions/session/checkpoints/restore')
        assert.deepEqual(lastBody, { checkpoint_id: 'checkpoint_saved' })
        await client.fork('session', 'target', 'checkpoint_saved', 'fork-key')
        assert.deepEqual(lastBody, { target_session_id: 'target', checkpoint_id: 'checkpoint_saved' })
        await client.checkpointOperation('session', 'operation')
        await assert.rejects(client.checkpointOperation('other', 'operation'), /identity differs/)
        await assert.rejects(client.checkpointOperation('session', 'other'), /identity differs/)
        await client.cancelCheckpointOperation('session', 'operation')
        assert.equal(lastPath, '/api/v1/agent-sessions/session/checkpoint-operations/operation/cancel')
    })
})

test('public client never forwards credentials through redirects and rejects foreign Turn receipts', async () => {
    let redirected = 0
    await withServer((request, response) => {
        if (request.url === '/forwarded') { redirected++; response.end('{}') }
        else if (request.url?.includes('/commands/')) response.end('{"turn_id":"another-turn"}')
        else { response.writeHead(302, { Location: '/forwarded' }); response.end() }
    }, async base => {
        const client = new ServiceClient(base, { apiKey: 'original-key' })
        await assert.rejects(client.getSession('session'))
        assert.equal(redirected, 0)
        await assert.rejects(client.commandStatus('session', 'turn', 'command'), /does not belong/)
    })
})

test('public export pins its watermark across windows and rejects an incomplete record', async () => {
    const queries: URLSearchParams[] = []
    let incomplete = false
    await withServer((request, response) => {
        assert.equal(request.headers['x-api-key'], 'original-key')
        assert.equal(request.headers['x-managed-agents-internal-token'], undefined)
        const query = new URL(request.url!, 'http://fixture').searchParams
        queries.push(query)
        const first = !query.get('after')
        const event = { schema_version: 1, session_id: 'session', id: first ? 'a' : 'b', cursor: first ? 'cursor-a' : 'watermark', type: 'turn.completed', data: {} }
        response.writeHead(200, { 'Content-Type': 'application/x-ndjson', 'X-Export-Through': 'watermark', 'X-Next-Cursor': event.cursor, 'X-Has-More': first ? 'true' : 'false', 'X-Export-Count': '1' })
        response.end(JSON.stringify(event) + (incomplete ? '' : '\n'))
    }, async base => {
        const client = new ServiceClient(base, { apiKey: 'original-key' })
        const events = []
        for await (const event of client.exportEvents('session', { limit: 1 })) events.push(event.id)
        assert.deepEqual(events, ['a', 'b'])
        assert.equal(queries[1].get('through'), 'watermark')
        assert.equal(queries[1].get('after'), 'cursor-a')
        incomplete = true
        await assert.rejects(async () => { for await (const event of client.exportEvents('session', { limit: 1 })) assert.fail(event.id) }, /Incomplete/)
    })
})


test('budget updates preserve user credentials and conditional revisions', async () => {
    await withServer((request, response) => {
        assert.equal(request.headers['x-api-key'], 'original-key')
        assert.equal(request.headers['x-managed-agents-internal-token'], undefined)
        assert.equal(request.url, '/api/v1/agent-sessions/session/budget')
        if (request.method === 'PUT') {
            assert.equal(request.headers['if-match'], '2')
            const chunks: Buffer[] = []
            request.on('data', chunk => chunks.push(chunk))
            request.on('end', () => {
                assert.deepEqual(JSON.parse(Buffer.concat(chunks).toString()), { max_model_calls: 20 })
                response.writeHead(202); response.end('{"revision":3}')
            })
        } else response.end('{"revision":2}')
    }, async base => {
        const client = new ServiceClient(base, { apiKey: 'original-key' })
        assert.equal((await client.budget('session')).revision, 2)
        assert.equal((await client.setBudget('session', { max_model_calls: 20 }, 2)).revision, 3)
        assert.throws(() => client.setBudget('session', {}, 0), /positive/)
    })
})
