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

import assert from 'node:assert/strict'
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import { test } from 'node:test'
import { createLogger } from '../src/logger.ts'
import { HttpSelfRegistration } from '../src/registration.ts'

async function mockControlPlane(
    handler: (req: IncomingMessage, body: string) => { status: number; json?: unknown },
): Promise<{ url: string; calls: string[]; close: () => Promise<void> }> {
    const calls: string[] = []
    const server: Server = createServer((req, res) => {
        const chunks: Buffer[] = []
        req.on('data', (chunk: Buffer) => chunks.push(chunk))
        req.on('end', () => {
            const body = Buffer.concat(chunks).toString('utf8')
            calls.push(`${req.method} ${req.url}`)
            const result = handler(req, body)
            const payload = Buffer.from(JSON.stringify(result.json ?? { ok: true }))
            res.writeHead(result.status, {
                'Content-Type': 'application/json',
                'Content-Length': String(payload.length),
            })
            res.end(payload)
        })
    })
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
    const address = server.address()
    const port = typeof address === 'object' && address ? address.port : 0
    return {
        url: `http://127.0.0.1:${port}`,
        calls,
        close: () =>
            new Promise((resolve, reject) => {
                server.close((error) => (error ? reject(error) : resolve()))
            }),
    }
}

test('registers then heartbeats, and re-registers after 404', async () => {
    let registered = true
    const requestBodies = new Map<string, string[]>()
    const cp = await mockControlPlane((req, body) => {
        const key = `${req.method} ${req.url}`
        requestBodies.set(key, [...(requestBodies.get(key) ?? []), body])
        if (req.method === 'POST' && req.url === '/api/v1/dataplanes/register') {
            registered = true
            return {
                status: 200,
                json: {
                    instanceId: '7f118ee8-329e-46dc-b07c-1e122421b8ab',
                    instanceKey: 'inst-1',
                    generation: 7,
                    heartbeatInterval: 1,
                },
            }
        }
        if (req.method === 'POST' && req.url?.endsWith('/heartbeat')) {
            if (!registered) {
                return { status: 404, json: { error: 'missing' } }
            }
            return { status: 200 }
        }
        if (req.method === 'DELETE') {
            return { status: 200 }
        }
        return { status: 404 }
    })
    const registration = new HttpSelfRegistration({
        tenant: 'default',
        controlHttp: cp.url,
        internalToken: 'token-token-token-token-token-32',
        agentName: 'deepseek-harness',
        namespace: 'default',
        instanceId: 'inst-1',
        baseUrl: 'http://127.0.0.1:18091',
        runtime: 'deepseek-harness',
        framework: 'deepseek-harness',
        contractLevel: 3,
        capabilities: ['session-reporting'],
        heartbeatIntervalMs: 20,
        log: createLogger(),
    })
    try {
        registration.start()
        await waitFor(() => cp.calls.includes('POST /api/v1/dataplanes/register'))
        registered = false
        await waitFor(() => cp.calls.filter((call) => call.includes('/heartbeat')).length >= 1)
        await waitFor(
            () => cp.calls.filter((call) => call === 'POST /api/v1/dataplanes/register').length >= 2,
        )
        assert.ok(
            cp.calls.includes(
                'POST /api/v1/dataplanes/7f118ee8-329e-46dc-b07c-1e122421b8ab/heartbeat',
            ),
        )
        assert.deepEqual(
            JSON.parse(
                requestBodies.get(
                    'POST /api/v1/dataplanes/7f118ee8-329e-46dc-b07c-1e122421b8ab/heartbeat',
                )?.[0] ?? '{}',
            ),
            { generation: 7 },
        )
    } finally {
        await registration.close()
        await cp.close()
    }
    assert.ok(cp.calls[0] === 'POST /api/v1/dataplanes/register')
})

test('does not heartbeat when registration response lacks durable identity', async () => {
    const cp = await mockControlPlane((req) => {
        if (req.method === 'POST' && req.url === '/api/v1/dataplanes/register') {
            return { status: 200, json: { heartbeatInterval: 1 } }
        }
        return { status: 500 }
    })
    const registration = new HttpSelfRegistration({
        tenant: 'tenant-a',
        controlHttp: cp.url,
        internalToken: 'token',
        agentName: 'agent',
        namespace: 'default',
        instanceId: 'instance-key',
        baseUrl: 'http://127.0.0.1:18091',
        runtime: 'dsh',
        framework: 'dsh',
        contractLevel: 3,
        capabilities: [],
        heartbeatIntervalMs: 20,
        log: createLogger(),
    })
    try {
        registration.start()
        await waitFor(
            () => cp.calls.filter((call) => call === 'POST /api/v1/dataplanes/register').length >= 2,
        )
        assert.equal(cp.calls.some((call) => call.includes('/heartbeat')), false)
    } finally {
        await registration.close()
        await cp.close()
    }
})

function waitFor(predicate: () => boolean, timeoutMs = 2000): Promise<void> {
    const start = Date.now()
    return new Promise((resolve, reject) => {
        const tick = (): void => {
            if (predicate()) {
                resolve()
                return
            }
            if (Date.now() - start > timeoutMs) {
                reject(new Error('timeout'))
                return
            }
            setTimeout(tick, 10)
        }
        tick()
    })
}
