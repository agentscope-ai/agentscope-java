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

import { appendFile, mkdir } from 'node:fs/promises'
import { join } from 'node:path'
import type { Logger } from './logger.js'
import type { DshSession, DshSessionEvent } from './types.js'

/**
 * Writes wrapper-transcript JSONL under
 * `{tenant}/{agent}/{session}/events/{seqStart}-{seqStart}-{writerId}.jsonl`.
 */
export class TranscriptWriter {
    private readonly written = new Set<string>()
    private readonly seq = new Map<string, number>()

    constructor(
        private readonly root: string,
        private readonly tenant: string,
        private readonly agentName: string,
        private readonly writerId: string,
        private readonly log: Logger,
    ) {}

    async onEvent(session: DshSession | undefined, event: DshSessionEvent | undefined): Promise<void> {
        if (!session || !event) {
            return
        }
        const line = toLine(session.id, event)
        if (!line) {
            return
        }
        const key = `${session.id}:${event.seq ?? event.type}:${event.time ?? ''}`
        if (this.written.has(key)) {
            return
        }
        this.written.add(key)
        try {
            await this.append(session.id, event.seq ?? 0, line)
        } catch (error) {
            this.log.debug('transcript append failed', error)
        }
    }

    private async append(sessionId: string, eventSeq: number, line: string): Promise<void> {
        const dir = join(this.root, this.tenant, this.agentName, sessionId, 'events')
        await mkdir(dir, { recursive: true })
        let start = this.seq.get(sessionId)
        if (start == null) {
            start = Math.max(1, eventSeq || 1)
            this.seq.set(sessionId, start)
        }
        const file = join(dir, `${start}-${start}-${sanitize(this.writerId)}.jsonl`)
        await appendFile(file, `${line}\n`, 'utf8')
    }
}

function sanitize(value: string): string {
    return value.replace(/[^A-Za-z0-9._-]+/g, '_') || 'dsh'
}

function toLine(sessionId: string, event: DshSessionEvent): string | undefined {
    const timestamp = iso(event.time)
    const seq = event.seq ?? 0
    const id = `${sessionId}:${seq || event.type}`
    switch (event.type) {
        case 'user/message':
        case 'assistant/message': {
            const message = (event.data?.message ?? event.data) as Record<string, unknown> | undefined
            return JSON.stringify({
                type: 'message',
                id,
                timestamp,
                role: event.type === 'user/message' ? 'user' : 'assistant',
                content: textOf(message),
            })
        }
        case 'tool/call': {
            const callId = String(event.data?.callId ?? event.data?.id ?? id)
            return JSON.stringify({
                type: 'tool_use',
                id,
                timestamp,
                toolCallId: callId,
                name: String(event.data?.name ?? ''),
                input: parseJsonish(event.data?.arguments),
                truncated: false,
            })
        }
        case 'tool/result': {
            const message = (event.data?.message ?? event.data) as Record<string, unknown> | undefined
            const callId = String(
                message?.toolCallId ?? event.data?.callId ?? event.data?.id ?? id,
            )
            return JSON.stringify({
                type: 'tool_result',
                id,
                timestamp,
                toolCallId: callId,
                name: String(event.data?.name ?? message?.name ?? ''),
                output: textOf(message),
                truncated: false,
            })
        }
        default:
            return undefined
    }
}

function parseJsonish(value: unknown): unknown {
    if (typeof value !== 'string') {
        return value ?? {}
    }
    try {
        return JSON.parse(value)
    } catch {
        return { raw: value }
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

function iso(value: unknown): string {
    if (typeof value === 'number' && Number.isFinite(value)) {
        const ms = value < 1e12 ? value * 1000 : value
        return new Date(ms).toISOString()
    }
    if (typeof value === 'string' && value) {
        const parsed = Date.parse(value)
        if (Number.isFinite(parsed)) {
            return new Date(parsed).toISOString()
        }
    }
    return new Date().toISOString()
}
