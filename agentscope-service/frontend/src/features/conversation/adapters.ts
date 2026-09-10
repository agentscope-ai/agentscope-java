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

/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import type { SessionEvent as ManagedSessionEvent } from '@/api/managedSessions';
import type {
  SessionEventItem,
  SessionMessageItem,
  SessionTurn,
} from '@/features/operate/api';
import type {
  ConversationContentBlock,
  ConversationEvent,
  ConversationEventCategory,
  ConversationMessage,
  ConversationRole,
} from './model';

function roleOf(value?: string): ConversationRole {
  switch ((value || '').toLowerCase()) {
    case 'user':
      return 'user';
    case 'assistant':
    case 'agent':
      return 'assistant';
    case 'system':
      return 'system';
    case 'tool':
      return 'tool';
    case 'error':
      return 'error';
    default:
      return 'system';
  }
}

function eventCategory(type: string, role?: string): ConversationEventCategory {
  const value = type.toLowerCase();
  const normalizedRole = role?.toLowerCase();
  if (value.includes('thinking') || value.includes('reasoning') || value.includes('model')) return 'model';
  if (value.includes('error') || value.includes('failed')) return 'error';
  if (value.includes('tool') || normalizedRole === 'tool') return 'tool';
  if (value.includes('turn') || value.includes('step')) return 'turn';
  if (value.includes('message') || normalizedRole === 'user' || normalizedRole === 'assistant') return 'message';
  if (value.includes('model') || value.includes('thinking') || value.includes('chunk')) return 'model';
  if (value.includes('session') || value.includes('status') || value.includes('compact')) return 'lifecycle';
  return 'other';
}

function stringify(value: unknown): string {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function turnForMessage(message: SessionMessageItem, turns: SessionTurn[]): number | undefined {
  if (!message.occurredAt || turns.length === 0) return undefined;
  const time = Date.parse(message.occurredAt);
  if (!Number.isFinite(time)) return undefined;
  const turn = turns.find((candidate) => {
    const start = Date.parse(candidate.startedAt);
    const end = candidate.endedAt ? Date.parse(candidate.endedAt) : Number.POSITIVE_INFINITY;
    return Number.isFinite(start) && time >= start && time <= end;
  });
  return turn?.turnIndex;
}

function eventCallId(event: SessionEventItem): string | undefined {
  if (!event.frameworkMeta || typeof event.frameworkMeta !== 'object') return undefined;
  const metadata = event.frameworkMeta as Record<string, unknown>;
  const value = metadata.toolCallId ?? metadata.toolUseId ?? metadata.tool_use_id ?? metadata.callId;
  return value == null || value === '' ? undefined : String(value);
}

function eventToolState(event: SessionEventItem): string | undefined {
  if (!event.frameworkMeta || typeof event.frameworkMeta !== 'object') return undefined;
  const value = (event.frameworkMeta as Record<string, unknown>).state;
  return value == null || value === '' ? undefined : String(value).toLowerCase();
}

function isToolFailure(event: SessionEventItem): boolean {
  return ['error', 'failed', 'denied', 'interrupted'].includes(eventToolState(event) || '');
}

export function runtimeMessagesToConversation(
  messages: SessionMessageItem[],
  turns: SessionTurn[] = [],
): ConversationMessage[] {
  return messages.map((message, index) => {
    const callId = message.toolCallId || `message-${message.seq ?? index}`;
    const blocks: ConversationContentBlock[] = [];
    if (message.toolName || message.toolInput != null || message.toolOutput) {
      blocks.push({
        kind: 'tool',
        id: callId,
        callId,
        toolName: message.toolName || 'tool',
        text: message.toolInput == null ? undefined : stringify(message.toolInput),
        result: message.toolOutput || undefined,
      });
      if (message.content && message.content !== message.toolOutput) {
        blocks.push({ kind: 'text', id: `${callId}-text`, text: message.content });
      }
    } else {
      blocks.push({ kind: 'text', id: callId, text: message.content || '' });
    }
    return {
      id: `runtime-message-${message.seq ?? index}`,
      seq: message.seq,
      role: roleOf(message.role),
      blocks,
      occurredAt: message.occurredAt,
      turnIndex: turnForMessage(message, turns),
      state: 'complete',
      truncated: message.truncated,
      originalSize: message.originalSize,
      raw: message,
    };
  });
}

export function runtimeEventsToConversation(events: SessionEventItem[]): ConversationEvent[] {
  const projected = events.map((event, index) => {
    const usage = ((event.frameworkMeta || {}) as Record<string, unknown>).usage as Record<string, unknown> | undefined;
    return ({
    id: `runtime-event-${event.id ?? event.seq ?? index}`,
    seq: event.seq,
    type: event.eventType || 'event',
    category: isToolFailure(event) ? 'error' : eventCategory(event.eventType || '', event.role),
    occurredAt: event.occurredAt,
    role: event.role ? roleOf(event.role) : undefined,
    summary: event.content || event.toolOutput || event.toolName || undefined,
    callId: eventCallId(event),
    durationMs: event.durationMs,
    tokensIn: event.tokensIn ?? (typeof usage?.inputTokens === 'number' ? usage.inputTokens : undefined),
    tokensOut: event.tokensOut ?? (typeof usage?.outputTokens === 'number' ? usage.outputTokens : undefined),
    // Keep the complete durable event available to the diagnostics view. The
    // normalized fields above are presentation indexes, not a lossy replacement.
    payload: event,
  }); });
  const starts = new Map<string, SessionEventItem>();
  for (const [index, event] of events.entries()) {
    const meta = (event.frameworkMeta || {}) as Record<string, unknown>;
    const key = [meta.turnId || '', meta.attemptId || '', meta.dispatchGeneration || '', meta.spanId || meta.requestId || ''].join(':');
    if (event.eventType === 'span.model_request_start') starts.set(key, event);
    if (event.eventType === 'span.model_request_end') {
      projected[index].durationMs ??= elapsed(starts.get(key)?.occurredAt, event.occurredAt);
      starts.delete(key);
    }
  }
  return projected;
}

/**
 * Derive the readable transcript from the durable event log. This is the
 * primary runtime conversation path; message-query is only a legacy fallback.
 */
export function runtimeEventsToMessages(events: SessionEventItem[]): ConversationMessage[] {
  const messages: ConversationMessage[] = [];
  const streamedMessages = new Map<string, ConversationMessage>();
  const toolBlocks = new Map<string, ConversationContentBlock>();
  const starts = new Map<string, { block: ConversationContentBlock; at?: string }>();
  const callTimes = new Map<string, string | undefined>();
  for (const [index, event] of events.entries()) {
    const type = event.eventType || 'event';
    const category = eventCategory(type, event.role);
    const meta = (event.frameworkMeta || {}) as Record<string, unknown>;
    const scope = [meta.turnId || '', meta.attemptId || '', meta.dispatchGeneration || ''].join(':');
    if (/^turn\.(completed|failed|cancelled)$/.test(type)) {
      const message = streamedMessages.get(scope);
      if (message) message.state = 'complete';
    }
    if (/^session\.(status_idle|status_terminated|interrupted|error)$/.test(type)) {
      for (const message of streamedMessages.values()) message.state = 'complete';
      for (const { block } of starts.values()) block.toolState = 'unavailable';
      for (const block of toolBlocks.values()) if (block.result === undefined) block.toolState = 'unavailable';
    }
    if (!['message', 'tool', 'error', 'model'].includes(category)) continue;
    const id = `runtime-event-message-${event.id ?? event.seq ?? index}`;
    const role = roleOf(event.role || (category === 'tool' ? 'assistant' : category === 'error' ? 'error' : 'system'));
    if (meta.turnId && (type === 'assistant.delta' || type === 'assistant.message')) {
      const existing = streamedMessages.get(scope);
      if (existing) {
        existing.blocks[0].text = type === 'assistant.delta'
          ? (existing.blocks[0].text || '') + (event.content || '')
          : event.content || '';
        existing.state = type === 'assistant.delta' ? 'streaming' : 'complete';
      } else {
        const message: ConversationMessage = {
          id, seq: event.seq, role: 'assistant', occurredAt: event.occurredAt,
          blocks: [{ kind: 'text', id: `${id}-text`, text: event.content || '' }],
          state: type === 'assistant.delta' ? 'streaming' : 'complete', raw: event,
        };
        streamedMessages.set(scope, message);
        messages.push(message);
      }
      continue;
    }
    if (category === 'model') {
      const thinking = /thinking|reasoning/.test(type);
      const key = `${scope}:${meta.spanId || meta.requestId || 'model'}`;
      if (type.endsWith('_end')) {
        const start = starts.get(key);
        if (start) {
          start.block.toolState = 'complete';
          start.block.durationMs = event.durationMs ?? elapsed(start.at, event.occurredAt);
          start.block.resultSeq = event.seq;
          starts.delete(key);
          continue;
        }
      }
      if (!thinking && !type.includes('model_request')) continue;
      const block: ConversationContentBlock = {
        kind: thinking ? 'thinking' : 'model', id, text: event.content,
        eventSeq: event.seq, toolState: type.endsWith('_start') ? 'running' : 'complete',
        durationMs: event.durationMs,
      };
      if (type.endsWith('_start')) starts.set(key, { block, at: event.occurredAt });
      messages.push({ id, seq: event.seq, role: 'assistant', blocks: [block], occurredAt: event.occurredAt, truncated: meta.truncated === true, originalSize: typeof meta.originalSize === 'number' ? meta.originalSize : undefined, raw: event });
      continue;
    }
    if (category === 'tool') {
      const callId = eventCallId(event) || `event-${event.seq ?? index}`;
      const key = `${scope}:${callId}`;
      const existing = toolBlocks.get(key);
      const isResult = type.toLowerCase().includes('result');
      if (existing && isResult) {
        existing.result = event.toolOutput ?? event.content ?? '';
        existing.toolState = eventToolState(event) || 'complete';
        existing.durationMs = event.durationMs ?? elapsed(callTimes.get(key), event.occurredAt);
        existing.resultSeq = event.seq;
        continue;
      }
      const block: ConversationContentBlock = {
        kind: 'tool',
        id: `${id}-tool`,
        callId,
        toolName: event.toolName || 'tool',
        toolState: eventToolState(event) || (isResult ? 'complete' : 'pending'),
        eventSeq: event.seq,
        durationMs: event.durationMs,
        text: event.toolInput == null ? undefined : stringify(event.toolInput),
        result: event.toolOutput ?? (isResult ? event.content ?? '' : undefined),
      };
      toolBlocks.set(key, block);
      callTimes.set(key, event.occurredAt);
      messages.push({
        id,
        seq: event.seq,
        role,
        blocks: [block],
        occurredAt: event.occurredAt,
        state: 'complete',
        raw: event,
      });
      continue;
    }
    messages.push({
      id,
      seq: event.seq,
      role,
      blocks: [{ kind: 'text', id: `${id}-text`, text: event.content || '' }],
      occurredAt: event.occurredAt,
      state: category === 'error' ? 'error' : 'complete',
      raw: event,
    });
  }
  // An unmatched historical call is not necessarily still running (pagination,
  // cancellation or an older runtime may not have recorded its result).
  const last = events[events.length - 1]?.eventType || '';
  if (/idle|terminated|interrupted|error|completed|failed/.test(last)) {
    for (const { block } of starts.values()) block.toolState = 'unavailable';
    for (const block of toolBlocks.values()) if (block.result === undefined) block.toolState = 'unavailable';
  }
  return messages;
}

function elapsed(start?: string, end?: string): number | undefined {
  if (!start || !end) return undefined;
  const duration = Date.parse(end) - Date.parse(start);
  return Number.isFinite(duration) && duration >= 0 ? duration : undefined;
}

export function managedEventsToConversation(events: ManagedSessionEvent[]): ConversationEvent[] {
  return events.map((event) => {
    const payload = event.payload || {};
    const callId = payload.toolCallId ?? payload.toolUseId ?? payload.tool_use_id ?? payload.id;
    const summary = payload.text ?? payload.message ?? payload.content ?? payload.output;
    return {
      id: `managed-event-${event.id}`,
      seq: event.seq,
      type: event.type,
      category: eventCategory(event.type),
      occurredAt: new Date(event.createdAt).toISOString(),
      role: event.type.startsWith('user.')
        ? 'user'
        : event.type.startsWith('agent.')
          ? 'assistant'
          : event.type.includes('error')
            ? 'error'
            : undefined,
      summary: summary == null ? undefined : stringify(summary),
      callId: callId == null ? undefined : String(callId),
      payload,
    };
  });
}

export function invocationResultEvent(
  result: Record<string, unknown>,
  index: number,
): ConversationEvent {
  const status = typeof result.status === 'string' ? result.status : 'accepted';
  return {
    id: `invocation-${String(result.invocationId || index)}`,
    type: `invocation.${status}`,
    category: status === 'failed' || status === 'error' ? 'error' : 'lifecycle',
    occurredAt: new Date().toISOString(),
    summary: `Invocation ${status}`,
    payload: result,
  };
}

export function resultText(result: Record<string, unknown>): string {
  for (const key of ['output', 'final', 'response', 'message', 'content', 'result']) {
    const value = result[key];
    if (typeof value === 'string' && value.trim()) return value;
    if (value && typeof value === 'object') {
      const nested = value as Record<string, unknown>;
      for (const nestedKey of ['text', 'content', 'output']) {
        if (typeof nested[nestedKey] === 'string' && String(nested[nestedKey]).trim()) {
          return String(nested[nestedKey]);
        }
      }
    }
  }
  return '';
}
