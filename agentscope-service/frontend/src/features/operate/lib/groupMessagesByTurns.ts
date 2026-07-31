import type { SessionTurn } from '../api';

export type HistoryMessage = {
  seq?: number;
  role?: string;
  content?: string;
  toolName?: string;
  occurredAt?: string;
  /** Stable order index when timestamps are missing. */
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
      const row = m as HistoryMessage;
      return { ...row, orderIndex: i };
    }
    return { content: String(m), orderIndex: i };
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
 * Split transcript into turn-like segments: each user message opens a segment;
 * following assistant/tool/system messages stay in that segment until the next user.
 * Leading non-user messages (rare) form a preamble returned separately.
 */
export function splitUserSegments(messages: HistoryMessage[]): {
  preamble: HistoryMessage[];
  segments: HistoryMessage[][];
} {
  const preamble: HistoryMessage[] = [];
  const segments: HistoryMessage[][] = [];
  for (const m of messages) {
    if (isUser(m)) {
      segments.push([m]);
      continue;
    }
    if (segments.length === 0) {
      preamble.push(m);
    } else {
      segments[segments.length - 1].push(m);
    }
  }
  return { preamble, segments };
}

/**
 * When message timestamps are missing, align user-segments to turns by order.
 * Prefer end-alignment so history that predates turn recording lands in "before".
 */
function groupByUserBoundaries(
  turns: SessionTurn[],
  messages: HistoryMessage[],
): ConversationGroup[] {
  const sortedTurns = [...turns].sort((a, b) => a.turnIndex - b.turnIndex);
  const sortedMsgs = [...messages].sort((a, b) => a.orderIndex - b.orderIndex);
  const { preamble, segments } = splitUserSegments(sortedMsgs);

  const buckets = new Map<number, HistoryMessage[]>();
  for (const t of sortedTurns) {
    buckets.set(t.turnIndex, []);
  }
  const before: HistoryMessage[] = [...preamble];

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

  const groups: ConversationGroup[] = [];
  if (before.length > 0) {
    groups.push({ kind: 'before', messages: before });
  }
  for (let i = sortedTurns.length - 1; i >= 0; i--) {
    const turn = sortedTurns[i];
    groups.push({
      kind: 'turn',
      turn,
      messages: buckets.get(turn.turnIndex) || [],
    });
  }
  return groups;
}

function groupByTimeWindows(
  turns: SessionTurn[],
  messages: HistoryMessage[],
): ConversationGroup[] {
  const sortedTurns = [...turns].sort((a, b) => a.turnIndex - b.turnIndex);
  const sortedMsgs = [...messages].sort((a, b) => {
    const ta = parseTime(a.occurredAt);
    const tb = parseTime(b.occurredAt);
    if (ta != null && tb != null && ta !== tb) return ta - tb;
    if (ta != null && tb == null) return -1;
    if (ta == null && tb != null) return 1;
    return a.orderIndex - b.orderIndex;
  });

  const buckets = new Map<number, HistoryMessage[]>();
  for (const t of sortedTurns) {
    buckets.set(t.turnIndex, []);
  }
  const before: HistoryMessage[] = [];

  const bounds = sortedTurns.map((t) => ({
    turn: t,
    start: parseTime(t.startedAt) ?? 0,
    end:
      t.status === 'running' || !t.endedAt
        ? Number.POSITIVE_INFINITY
        : (parseTime(t.endedAt) ?? Number.POSITIVE_INFINITY),
  }));

  for (const msg of sortedMsgs) {
    const ts = parseTime(msg.occurredAt);
    if (ts == null) {
      // Should not happen when caller uses this path; stash on last turn.
      buckets.get(sortedTurns[sortedTurns.length - 1].turnIndex)!.push(msg);
      continue;
    }
    if (ts < bounds[0].start) {
      before.push(msg);
      continue;
    }
    const hit = bounds.find((b) => ts >= b.start && ts < b.end);
    if (hit) {
      buckets.get(hit.turn.turnIndex)!.push(msg);
      continue;
    }
    let gapPrev: number | null = null;
    for (let i = 0; i < bounds.length; i++) {
      const b = bounds[i];
      const nextStart =
        i + 1 < bounds.length ? bounds[i + 1].start : Number.POSITIVE_INFINITY;
      if (Number.isFinite(b.end) && ts >= b.end && ts < nextStart) {
        gapPrev = b.turn.turnIndex;
        break;
      }
    }
    if (gapPrev != null) {
      buckets.get(gapPrev)!.push(msg);
      continue;
    }
    buckets.get(sortedTurns[sortedTurns.length - 1].turnIndex)!.push(msg);
  }

  const groups: ConversationGroup[] = [];
  if (before.length > 0) {
    groups.push({ kind: 'before', messages: before });
  }
  for (let i = sortedTurns.length - 1; i >= 0; i--) {
    const turn = sortedTurns[i];
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
 * harness history omits timestamps) split on user-message boundaries and
 * end-align segments to recorded turns — one user request → one turn.
 */
export function groupMessagesByTurns(
  turns: SessionTurn[],
  messages: HistoryMessage[],
): ConversationGroup[] {
  if (turns.length === 0) {
    return messages.length
      ? [{ kind: 'before', messages: [...messages].sort((a, b) => a.orderIndex - b.orderIndex) }]
      : [];
  }

  const withTime = messages.filter((m) => parseTime(m.occurredAt) != null).length;
  // Require a majority of timestamps before trusting time windows; otherwise
  // a few stamped rows would leave the rest dumped into one turn.
  if (messages.length > 0 && withTime * 2 >= messages.length) {
    return groupByTimeWindows(turns, messages);
  }
  return groupByUserBoundaries(turns, messages);
}
