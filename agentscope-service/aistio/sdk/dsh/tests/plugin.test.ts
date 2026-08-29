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
