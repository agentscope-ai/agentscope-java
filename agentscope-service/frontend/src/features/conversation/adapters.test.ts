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

import { describe, expect, it } from 'vitest';
import {
  managedEventsToConversation,
  resultText,
  runtimeEventsToConversation,
  runtimeEventsToMessages,
  runtimeMessagesToConversation,
} from './adapters';

describe('conversation adapters', () => {
  it('closes partial streamed output when a terminal event is replayed after reconnecting', () => {
    for (const eventType of ['turn.completed', 'turn.failed', 'turn.cancelled', 'session.status_idle', 'session.interrupted']) {
      const messages = runtimeEventsToMessages([
        { seq: 1, eventType: 'assistant.delta', role: 'assistant', content: 'partial answer', frameworkMeta: { turnId: 'one' } },
        { seq: 2, eventType, frameworkMeta: { turnId: 'one' } },
      ]);
      expect(messages[0]).toMatchObject({ state: 'complete', blocks: [{ text: 'partial answer' }] });
    }
  });
  it('combines External deltas and replaces them with the final response once', () => {
    const events = [
      { seq: 1, eventType: 'assistant.delta', role: 'assistant', content: 'hel', frameworkMeta: { turnId: 'one' } },
      { seq: 2, eventType: 'assistant.delta', role: 'assistant', content: 'lo', frameworkMeta: { turnId: 'one' } },
    ];
    expect(runtimeEventsToMessages(events)[0]).toMatchObject({ state: 'streaming', blocks: [{ text: 'hello' }] });
    const messages = runtimeEventsToMessages([...events,
      { seq: 3, eventType: 'assistant.message', role: 'assistant', content: 'hello!', frameworkMeta: { turnId: 'one' } },
      { seq: 4, eventType: 'assistant.message', role: 'assistant', content: 'next', frameworkMeta: { turnId: 'two' } },
    ]);
    expect(messages).toHaveLength(2);
    expect(messages[0]).toMatchObject({ state: 'complete', blocks: [{ text: 'hello!' }] });
    expect(messages[1].blocks[0].text).toBe('next');
  });
  it('projects runtime tool messages without losing the call identity', () => {
    const [message] = runtimeMessagesToConversation([{
      seq: 7,
      role: 'assistant',
      toolName: 'shell',
      toolCallId: 'call-1',
      toolInput: { command: 'pwd' },
      toolOutput: '/workspace',
    }]);

    expect(message.role).toBe('assistant');
    expect(message.blocks[0]).toMatchObject({
      kind: 'tool',
      callId: 'call-1',
      toolName: 'shell',
      result: '/workspace',
    });
  });

  it('keeps the complete runtime event in the raw event payload', () => {
    const [event] = runtimeEventsToConversation([{
      seq: 3,
      eventType: 'tool_call',
      toolName: 'read_file',
      frameworkMeta: { provider: 'codex', nativeType: 'item.started' },
    }]);

    expect(event.category).toBe('tool');
    expect(event.payload).toMatchObject({
      seq: 3,
      eventType: 'tool_call',
      toolName: 'read_file',
      frameworkMeta: { provider: 'codex', nativeType: 'item.started' },
    });
  });

  it('derives conversation messages from the event log and pairs tool results', () => {
    const messages = runtimeEventsToMessages([
      { seq: 1, eventType: 'message', role: 'user', content: 'hello' },
      { seq: 2, eventType: 'tool_call', role: 'assistant', toolName: 'shell', toolInput: { command: 'pwd' }, frameworkMeta: { toolCallId: 'call-1' } },
      { seq: 3, eventType: 'tool_result', role: 'tool', toolName: 'shell', toolOutput: '/workspace', frameworkMeta: { toolCallId: 'call-1' } },
      { seq: 4, eventType: 'message', role: 'assistant', content: 'done' },
    ]);

    expect(messages).toHaveLength(3);
    expect(messages.map((message) => message.role)).toEqual(['user', 'assistant', 'assistant']);
    expect(messages[1].blocks[0]).toMatchObject({ callId: 'call-1', result: '/workspace' });
    expect(messages[2].blocks[0]).toMatchObject({ kind: 'text', text: 'done' });
  });

  it('preserves failed tool state and highlights it in the event timeline', () => {
    const events = [
      { seq: 1, eventType: 'tool_call', role: 'assistant', toolName: 'issue.child.create', frameworkMeta: { toolCallId: 'call-err', state: 'running' } },
      { seq: 2, eventType: 'tool_result', role: 'tool', toolName: 'issue.child.create', toolOutput: 'cannot scan NULL into *string', frameworkMeta: { toolCallId: 'call-err', state: 'error' } },
    ];
    const messages = runtimeEventsToMessages(events);
    const timeline = runtimeEventsToConversation(events);

    expect(messages).toHaveLength(1);
    expect(messages[0].blocks[0]).toMatchObject({ callId: 'call-err', toolState: 'error', result: 'cannot scan NULL into *string' });
    expect(timeline[1].category).toBe('error');
  });

  it('normalizes managed events while preserving their payload', () => {
    const [event] = managedEventsToConversation([{
      id: 'event-1',
      sessionId: 'session-1',
      seq: 2,
      type: 'agent.tool_use',
      payload: { toolCallId: 'call-2', name: 'Bash', input: { command: 'ls' } },
      createdAt: 1_700_000_000_000,
    }]);

    expect(event).toMatchObject({ category: 'tool', callId: 'call-2' });
    expect(event.payload).toMatchObject({ name: 'Bash' });
  });

  it('extracts common provider result text shapes', () => {
    expect(resultText({ result: { content: 'done' } })).toBe('done');
    expect(resultText({ status: 'running' })).toBe('');
  });
});

it('keeps thinking before tools and pairs model spans without inventing reasoning', () => {
  const messages = runtimeEventsToMessages([
    { seq: 1, eventType: 'span.model_request_start', occurredAt: '2026-09-08T00:00:00Z' },
    { seq: 2, eventType: 'agent.thinking', role: 'assistant', content: 'Check the source first.' },
    { seq: 3, eventType: 'span.model_request_end', occurredAt: '2026-09-08T00:00:03.500Z' },
    { seq: 4, eventType: 'agent.tool_use', toolName: 'read_file', frameworkMeta: { toolCallId: 'one' } },
    { seq: 5, eventType: 'agent.tool_result', toolOutput: '', frameworkMeta: { toolCallId: 'one', state: 'SUCCESS' } },
    { seq: 6, eventType: 'agent.message', role: 'assistant', content: 'Done' },
  ]);
  expect(messages.map(m => m.blocks[0].kind)).toEqual(['model', 'thinking', 'tool', 'text']);
  expect(messages[0].blocks[0]).toMatchObject({ toolState: 'complete', durationMs: 3500, resultSeq: 3 });
  expect(messages[0].blocks[0].text).toBeUndefined();
  expect(messages[1].blocks[0].text).toBe('Check the source first.');
  expect(messages[2].blocks[0]).toMatchObject({ result: '', toolState: 'success' });
});

it('does not pair reused call IDs across attempts or claim missing results are running', () => {
  const messages = runtimeEventsToMessages([
    { seq: 1, eventType: 'agent.tool_use', toolName: 'shell', frameworkMeta: { toolCallId: 'same', attemptId: 'a' } },
    { seq: 2, eventType: 'agent.tool_result', toolName: 'shell', toolOutput: 'new result', frameworkMeta: { toolCallId: 'same', attemptId: 'b' } },
    { seq: 3, eventType: 'session.status_idle' },
  ]);
  expect(messages).toHaveLength(2);
  expect(messages[0].blocks[0]).toMatchObject({ toolState: 'unavailable' });
  expect(messages[0].blocks[0].result).toBeUndefined();
  expect(messages[1].blocks[0].result).toBe('new result');
});
