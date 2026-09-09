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
import { startBridge } from '../src/plugin.ts'
import type { DshAgent, DshContext, DshSession } from '../src/types.ts'

test('startBridge serves health and info without registering when token is blank', async () => {
    const liveSession: DshSession = { id: 'root', events: [] }
    const agent: DshAgent = {
        id: 'root',
        status: 'idle',
        session: liveSession,
        followup() {
            /* noop */
        },
        cancel() {
            /* noop */
        },
    }
    const ctx: DshContext = {
        sessions: {
            list: () => [liveSession],
            get: (id) => (id === 'root' ? liveSession : undefined),
        },
        agents: {
            list: () => [agent],
            get: (id) => (id === 'root' ? agent : undefined),
            roots: () => [agent],
        },
        on() {
            return () => undefined
        },
    }
    const handle = await startBridge(ctx, {
        internalToken: '',
        startHttpRegister: true,
        startGrpc: false,
        contractHost: '127.0.0.1',
        contractPort: 0,
        agentName: 'deepseek-harness',
    })
    try {
        assert.ok(handle.port > 0)
        const health = await fetch(`http://127.0.0.1:${handle.port}/agentscope/health`)
        assert.equal(health.status, 200)
        const info = (await (await fetch(`http://127.0.0.1:${handle.port}/agentscope/info`)).json()) as {
            name: string
            runtime: string
            capabilities: string[]
        }
        assert.equal(info.name, 'deepseek-harness')
        assert.equal(info.runtime, 'deepseek-harness')
        assert.ok(info.capabilities.includes('session-reporting'))
    } finally {
        await handle.close()
    }
})
