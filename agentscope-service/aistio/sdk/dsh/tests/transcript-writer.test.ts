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
import { mkdtemp, readdir, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { createLogger } from '../src/logger.ts'
import { TranscriptWriter } from '../src/transcript-writer.ts'

test('transcript writer appends message and tool JSONL under the wrapper layout', async () => {
    const root = await mkdtemp(join(tmpdir(), 'dsh-transcript-'))
    const writer = new TranscriptWriter(root, 'default', 'deepseek-harness', 'inst-1', createLogger())
    const session = { id: 'sess-a', events: [] }
    await writer.onEvent(session, {
        type: 'user/message',
        seq: 1,
        time: Date.parse('2026-08-01T12:00:00Z'),
        data: { text: 'hello' },
    })
    await writer.onEvent(session, {
        type: 'tool/call',
        seq: 2,
        time: Date.parse('2026-08-01T12:00:01Z'),
        data: { callId: 'call-1', name: 'bash', arguments: '{"cmd":"ls"}' },
    })
    await writer.onEvent(session, {
        type: 'tool/result',
        seq: 3,
        time: Date.parse('2026-08-01T12:00:02Z'),
        data: { callId: 'call-1', name: 'bash', text: 'ok' },
    })
    const dir = join(root, 'default', 'deepseek-harness', 'sess-a', 'events')
    const files = (await readdir(dir)).filter((name) => name.endsWith('.jsonl'))
    assert.equal(files.length, 1)
    const body = await readFile(join(dir, files[0]!), 'utf8')
    const lines = body.trim().split('\n').map((line) => JSON.parse(line) as { type: string; toolCallId?: string })
    assert.equal(lines[0]?.type, 'message')
    assert.equal(lines[1]?.type, 'tool_use')
    assert.equal(lines[2]?.type, 'tool_result')
    assert.equal(lines[1]?.toolCallId, 'call-1')
    assert.equal(lines[2]?.toolCallId, 'call-1')
})
