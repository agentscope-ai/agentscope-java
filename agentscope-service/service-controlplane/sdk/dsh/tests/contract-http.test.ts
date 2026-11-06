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
import { test } from 'node:test'
import { ContractHttpServer } from '../src/contract-http.ts'
import {
    ContractBusyError,
    ContractNotFoundError,
    ContractUnsupportedError,
    type ContractProvider,
} from '../src/types.ts'

function provider(overrides: Partial<ContractProvider> = {}): ContractProvider {
    return {
        info: () => ({ name: 'deepseek-harness', contractLevel: 3, capabilities: ['session-reporting'] }),
        sessions: () => [{ id: 's1', phase: 'idle' }],
        sessionState: (id) => {
            if (id !== 's1') {
                throw new ContractNotFoundError(`session not found: ${id}`)
            }
            return { id, phase: 'idle' }
        },
        context: () => ({ sessionId: 's1', messages: [] }),
        messages: (_id, offset, limit) => ({ sessionId: 's1', offset, limit, total: 0, messages: [] }),
        subagents: () => [],
        workspaces: () => [],
        tasks: () => ({ tasks: [] }),
        compress: () => {
            throw new ContractUnsupportedError('compress unsupported')
        },
        terminate: () => undefined,
        abort: () => undefined,
        sessionPhase: () => 'idle',
        ...overrides,
    }
}

async function withServer(
    impl: ContractProvider,
    fn: (base: string) => Promise<void>,
    token = '',
): Promise<void> {
    const server = new ContractHttpServer('127.0.0.1', 0, impl, token)
    await server.start()
    try {
        await fn(`http://127.0.0.1:${server.getPort()}`)
    } finally {
        server.close()
    }
}

test('health and info', async () => {
    await withServer(provider(), async (base) => {
        const health = await fetch(`${base}/agentscope/health`)
        assert.equal(health.status, 200)
        assert.deepEqual(await health.json(), { status: 'ok' })
        const info = await fetch(`${base}/agentscope/info`)
        const body = (await info.json()) as { name: string }
        assert.equal(body.name, 'deepseek-harness')
    })
})

test('sessions list and missing session is 404', async () => {
    await withServer(provider(), async (base) => {
        const list = await fetch(`${base}/agentscope/sessions`)
        const body = (await list.json()) as { sessions: Array<{ id: string }>; truncated: boolean }
        assert.equal(body.sessions[0]?.id, 's1')
        assert.equal(body.truncated, false)
        const missing = await fetch(`${base}/agentscope/sessions/nope/state`)
        assert.equal(missing.status, 404)
    })
})

test('unsupported command is 501', async () => {
    await withServer(provider(), async (base) => {
        const response = await fetch(`${base}/agentscope/sessions/s1/compress`, { method: 'POST' })
        assert.equal(response.status, 501)
    })
})

test('write endpoints require the internal token when configured', async () => {
    await withServer(
        provider(),
        async (base) => {
            const denied = await fetch(`${base}/agentscope/sessions/s1/abort`, { method: 'POST' })
            assert.equal(denied.status, 401)
            const health = await fetch(`${base}/agentscope/health`)
            assert.equal(health.status, 200)
            const ok = await fetch(`${base}/agentscope/sessions/s1/abort`, {
                method: 'POST',
                headers: { 'X-Builder-Internal-Token': 'secret-token' },
            })
            assert.equal(ok.status, 200)
        },
        'secret-token',
    )
})

test('busy compress is 409 wait_idle', async () => {
    await withServer(
        provider({
            compress() {
                throw new ContractBusyError('session is busy')
            },
        }),
        async (base) => {
            const response = await fetch(`${base}/agentscope/sessions/s1/compress`, { method: 'POST' })
            assert.equal(response.status, 409)
            const body = (await response.json()) as { hint: string; code: string }
            assert.equal(body.hint, 'wait_idle')
            assert.equal(body.code, 'busy')
        },
    )
})

test('inbound message returns 202', async () => {
    let received = ''
    await withServer(
        provider({
            async postMessage(_id, body) {
                received = body.toString('utf8')
            },
        }),
        async (base) => {
            const response = await fetch(`${base}/agentscope/sessions/s1/messages`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ content: 'hello from console' }),
            })
            assert.equal(response.status, 202)
            assert.match(received, /hello from console/)
        },
    )
})
