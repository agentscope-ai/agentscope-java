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
  return events.map((event, index) => ({
    id: `runtime-event-${event.id ?? event.seq ?? index}`,
    seq: event.seq,
    type: event.eventType || 'event',
    category: eventCategory(event.eventType || '', event.role),
    occurredAt: event.occurredAt,
    role: event.role ? roleOf(event.role) : undefined,
    summary: event.content || event.toolOutput || event.toolName || undefined,
    callId: eventCallId(event),
    durationMs: event.durationMs,
    tokensIn: event.tokensIn,
    tokensOut: event.tokensOut,
    payload: {
      content: event.content,
      toolName: event.toolName,
      toolInput: event.toolInput,
      toolOutput: event.toolOutput,
      frameworkMeta: event.frameworkMeta,
    },
  }));
}

/**
 * Derive the readable transcript from the durable event log. This is the
 * primary runtime conversation path; message-query is only a legacy fallback.
 */
export function runtimeEventsToMessages(events: SessionEventItem[]): ConversationMessage[] {
  const messages: ConversationMessage[] = [];
  const toolBlocks = new Map<string, ConversationContentBlock>();
  for (const [index, event] of events.entries()) {
    const type = event.eventType || 'event';
    const category = eventCategory(type, event.role);
    if (category !== 'message' && category !== 'tool' && category !== 'error') continue;
    const id = `runtime-event-message-${event.id ?? event.seq ?? index}`;
    const role = roleOf(event.role || (category === 'tool' ? 'assistant' : category === 'error' ? 'error' : 'system'));
    if (category === 'tool') {
      const callId = eventCallId(event) || `event-${event.seq ?? index}`;
      const existing = toolBlocks.get(callId);
      const isResult = type.toLowerCase().includes('result');
      if (existing && isResult) {
        existing.result = event.toolOutput || event.content || '';
        continue;
      }
      const block: ConversationContentBlock = {
        kind: 'tool',
        id: `${id}-tool`,
        callId,
        toolName: event.toolName || 'tool',
        text: event.toolInput == null ? undefined : stringify(event.toolInput),
        result: event.toolOutput || (isResult ? event.content : undefined),
      };
      toolBlocks.set(callId, block);
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
  return messages;
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
