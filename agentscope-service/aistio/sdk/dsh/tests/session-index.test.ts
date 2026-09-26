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
import { SessionIndex } from '../src/session-index.ts'
import type { DshAgent, DshContext, DshSession, DshSessionEvent } from '../src/types.ts'

function session(id: string, events: DshSessionEvent[] = []): DshSession {
    return {
        id,
        events,
        header: { createdAt: Date.parse('2026-01-01T00:00:00Z') },
        deriveMessages: () =>
            events
                .filter((event) => event.type === 'user/message' || event.type === 'assistant/message')
                .map((event) => ({
                    role: event.type === 'user/message' ? 'user' : 'assistant',
                    content: [{ type: 'text', text: String((event.data as { text?: string })?.text ?? '') }],
                })),
    }
}

function context(sessions: DshSession[], agents: DshAgent[] = []): DshContext {
    const listeners = new Map<string, Array<(...args: unknown[]) => void>>()
    return {
        sessions: {
            list: () => sessions,
            get: (id) => sessions.find((item) => item.id === id),
        },
        agents: {
            list: () => agents,
            get: (id) => agents.find((item) => item.id === id),
            roots: () => agents.filter((agent) => !agent.session.header?.parentSession),
        },
        on(event, listener) {
            const bucket = listeners.get(event) ?? []
            bucket.push(listener)
            listeners.set(event, bucket)
            return () => {
                listeners.set(
                    event,
                    (listeners.get(event) ?? []).filter((item) => item !== listener),
                )
            }
        },
    }
}

test('replays existing session events into snapshots', () => {
    const live = session('sess-1', [
        { type: 'turn/start', data: { turn: 1 } },
        { type: 'user/message', data: { text: 'hi' } },
        {
            type: 'assistant/message',
            data: { text: 'hello', usage: { inputTokens: 10, outputTokens: 4 } },
        },
        { type: 'turn/end', data: { turn: 1 } },
    ])
    const index = new SessionIndex(context([live]))
    index.attach()
    const [snapshot] = index.snapshots()
    assert.equal(snapshot?.id, 'sess-1')
    assert.equal(snapshot?.phase, 'idle')
    assert.equal(snapshot?.busy, false)
    assert.equal(snapshot?.messageCount, 2)
    const usage = snapshot?.tokenUsage as { promptTokens: number; completionTokens: number }
    assert.equal(usage.promptTokens, 10)
    assert.equal(usage.completionTokens, 4)
})

test('open turn stays active', () => {
    const live = session('sess-1', [{ type: 'turn/start', data: { turn: 1 } }])
    const index = new SessionIndex(context([live]))
    index.attach()
    const [snapshot] = index.snapshots()
    assert.equal(snapshot?.phase, 'active')
    assert.equal(snapshot?.busy, true)
})

test('alias lookup uses the native session', () => {
    const live = session('native-1', [{ type: 'user/message', data: { text: 'hi' } }])
    const index = new SessionIndex(context([live]))
    index.attach()
    index.alias('cp-session', 'native-1')
    const state = index.sessionState('cp-session')
    assert.equal(state.id, 'cp-session')
    const page = index.messages('cp-session', 0, 10)
    assert.equal(page.total, 1)
})

test('context includes systemPrompt and tasks come from todo/write', () => {
    const live = session('sess-1', [
        { type: 'user/message', data: { text: 'hi' } },
        {
            type: 'todo/write',
            data: {
                todos: [
                    { id: 't1', content: 'ship search', status: 'in_progress' },
                    { id: 't2', content: 'done item', status: 'completed' },
                ],
            },
        },
    ])
    live.header = { ...live.header, systemPrompt: 'You are a test agent.' }
    const index = new SessionIndex(context([live]))
    index.attach()
    const ctx = index.context('sess-1')
    assert.equal(ctx.systemPrompt, 'You are a test agent.')
    const tasks = index.tasks('sess-1').tasks as Array<{ id: string; state: string }>
    assert.equal(tasks.length, 2)
    assert.equal(tasks[0]?.id, 't1')
    assert.equal(tasks[0]?.state, 'in_progress')
})
