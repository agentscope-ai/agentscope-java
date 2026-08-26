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

import type { SessionMessageItem, SessionTurn } from '../api';

export type HistoryMessage = SessionMessageItem & {
  /** Stable order index when timestamps / seq are missing. */
  orderIndex: number;
};

export type TurnMessageGroup = {
  kind: 'turn';
  turn: SessionTurn;
  messages: HistoryMessage[];
};

export type BeforeTurnsGroup = {
  kind: 'before';
  messages: HistoryMessage[];
};

export type ConversationGroup = TurnMessageGroup | BeforeTurnsGroup;

export function extractHistoryMessages(data: unknown): HistoryMessage[] | null {
  if (!data || typeof data !== 'object') return null;
  const obj = data as Record<string, unknown>;
  const list = Array.isArray(obj.messages) ? obj.messages : Array.isArray(data) ? data : null;
  if (!list) return null;
  return list.map((m, i) => {
    if (m && typeof m === 'object') {
      const row = m as SessionMessageItem;
      return { ...row, orderIndex: i };
    }
    return { role: 'unknown', content: String(m), orderIndex: i, seq: i + 1 };
  });
}

function parseTime(v?: string): number | null {
  if (!v) return null;
  const t = Date.parse(v);
  return Number.isFinite(t) ? t : null;
}

function isUser(m: HistoryMessage): boolean {
  return (m.role || '').toLowerCase() === 'user';
}

/**
 * Split a chronological message list into user-led segments: each user message and
 * following assistant/tool/system messages stay in that segment until the next user.
 * Leading non-user messages (rare) form a preamble returned separately.
 */
export function splitUserSegments(messages: HistoryMessage[]): {
  preamble: HistoryMessage[];
  segments: HistoryMessage[][];
} {
  const preamble: HistoryMessage[] = [];
  const segments: HistoryMessage[][] = [];
  let current: HistoryMessage[] | null = null;
  for (const m of messages) {
    if (isUser(m)) {
      current = [m];
      segments.push(current);
    } else if (current) {
      current.push(m);
    } else {
      preamble.push(m);
    }
  }
  return { preamble, segments };
}

/**
 * Prefer end-alignment so history that predates turn recording lands in "before".
 */
export function groupByUserBoundaries(
  turns: SessionTurn[],
  messages: HistoryMessage[],
): ConversationGroup[] {
  const sortedMsgs = [...messages].sort((a, b) => a.orderIndex - b.orderIndex);
  const { preamble, segments } = splitUserSegments(sortedMsgs);
  const sortedTurns = [...turns].sort((a, b) => a.turnIndex - b.turnIndex);
  const buckets = new Map<number, HistoryMessage[]>();
  for (const t of sortedTurns) buckets.set(t.turnIndex, []);

  const before: HistoryMessage[] = [...preamble];
  const groups: ConversationGroup[] = [];

  if (sortedTurns.length === 0) {
    return sortedMsgs.length ? [{ kind: 'before', messages: sortedMsgs }] : [];
  }

  if (segments.length >= sortedTurns.length) {
    const offset = segments.length - sortedTurns.length;
    for (let i = 0; i < offset; i++) {
      before.push(...segments[i]);
    }
    for (let i = 0; i < sortedTurns.length; i++) {
      buckets.get(sortedTurns[i].turnIndex)!.push(...segments[offset + i]);
    }
  } else {
    for (let i = 0; i < segments.length; i++) {
      buckets.get(sortedTurns[i].turnIndex)!.push(...segments[i]);
    }
  }

  if (before.length > 0) {
    groups.push({ kind: 'before', messages: before });
  }
  for (const turn of sortedTurns) {
    groups.push({
      kind: 'turn',
      turn,
      messages: buckets.get(turn.turnIndex) || [],
    });
  }
  return groups;
}

export function groupByTimeWindows(
  turns: SessionTurn[],
  messages: HistoryMessage[],
): ConversationGroup[] {
  const sortedMsgs = [...messages].sort((a, b) => {
    const ta = parseTime(a.occurredAt);
    const tb = parseTime(b.occurredAt);
    if (ta != null && tb != null && ta !== tb) return ta - tb;
    return a.orderIndex - b.orderIndex;
  });
  const sortedTurns = [...turns].sort((a, b) => a.turnIndex - b.turnIndex);
  const buckets = new Map<number, HistoryMessage[]>();
  for (const t of sortedTurns) buckets.set(t.turnIndex, []);

  const before: HistoryMessage[] = [];
  for (const msg of sortedMsgs) {
    const mt = parseTime(msg.occurredAt);
    if (mt == null) {
      before.push(msg);
      continue;
    }
    let assigned: SessionTurn | null = null;
    for (const turn of sortedTurns) {
      const start = parseTime(turn.startedAt);
      if (start == null) continue;
      const end = parseTime(turn.endedAt) ?? Number.POSITIVE_INFINITY;
      if (mt >= start && mt <= end) {
        assigned = turn;
        break;
      }
    }
    if (assigned) {
      buckets.get(assigned.turnIndex)!.push(msg);
    } else {
      before.push(msg);
    }
  }

  const groups: ConversationGroup[] = [];
  if (before.length > 0) {
    groups.push({ kind: 'before', messages: before });
  }
  for (const turn of sortedTurns) {
    groups.push({
      kind: 'turn',
      turn,
      messages: buckets.get(turn.turnIndex) || [],
    });
  }
  return groups;
}

/**
 * Attribute messages to turns.
 *
 * Prefer time windows when messages carry occurredAt. Otherwise (common today:
 * transcript rows may lack timestamps on older data) use user-boundary alignment.
 */
export function groupMessagesByTurns(
  turns: SessionTurn[],
  messages: HistoryMessage[],
): ConversationGroup[] {
  if (!turns.length) {
    return messages.length
      ? [{ kind: 'before', messages: [...messages].sort((a, b) => a.orderIndex - b.orderIndex) }]
      : [];
  }

  const withTime = messages.filter((m) => parseTime(m.occurredAt) != null).length;
  // Require a majority of timestamps before trusting time windows; otherwise
  // fall back to user-boundary alignment.
  if (messages.length > 0 && withTime * 2 >= messages.length) {
    return groupByTimeWindows(turns, messages);
  }
  return groupByUserBoundaries(turns, messages);
}

export function messagePreviewText(m: HistoryMessage | SessionMessageItem, max = 96): string {
  if (m.toolName) {
    const kind = (m.role || '').toLowerCase() === 'tool' || m.toolOutput != null ? 'result' : 'call';
    const body =
      kind === 'result'
        ? String(m.toolOutput || m.content || '')
        : formatToolInput(m.toolInput) || String(m.content || '');
    const text = `${m.toolName}: ${body}`.replace(/\s+/g, ' ').trim();
    if (!text || text === `${m.toolName}:`) return m.toolName;
    if (text.length <= max) return text;
    return `${text.slice(0, max)}…`;
  }
  const text = String(m.content || '').replace(/\s+/g, ' ').trim();
  if (!text) return '—';
  if (text.length <= max) return text;
  return `${text.slice(0, max)}…`;
}

export function formatToolInput(input: unknown): string {
  if (input == null) return '';
  if (typeof input === 'string') return input;
  try {
    return JSON.stringify(input, null, 2);
  } catch {
    return String(input);
  }
}
