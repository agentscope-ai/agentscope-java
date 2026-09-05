import { describe, expect, it } from 'vitest';
import {
  managedEventsToConversation,
  resultText,
  runtimeEventsToConversation,
  runtimeEventsToMessages,
  runtimeMessagesToConversation,
} from './adapters';

describe('conversation adapters', () => {
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

  it('keeps runtime framework metadata in the raw event payload', () => {
    const [event] = runtimeEventsToConversation([{
      seq: 3,
      eventType: 'tool_call',
      toolName: 'read_file',
      frameworkMeta: { provider: 'codex', nativeType: 'item.started' },
    }]);

    expect(event.category).toBe('tool');
    expect(event.payload).toMatchObject({
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
