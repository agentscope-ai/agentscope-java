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

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Environment, listEnvironments } from '../api/environments';
import {
  EventStreamHandle,
  getManagedSession,
  listEvents,
  ManagedSession,
  postToolConfirmation,
  postUserMessage,
  SessionEvent,
  streamEvents,
} from '../api/managedSessions';
import { ConversationSurface } from '@/features/conversation/ConversationSurface';
import { managedEventsToConversation } from '@/features/conversation/adapters';
import { mergeContiguousEvents } from '@/features/conversation/eventCursor';
import type { ConversationContentBlock } from '@/features/conversation/model';

type Role = 'user' | 'assistant' | 'system' | 'error';

interface Message {
  id: string;
  role: Role;
  blocks: ConversationContentBlock[];
  pending?: boolean;
  /** Turn finished: no more blocks are appended to this bubble. */
  closed?: boolean;
}

interface PendingConfirmation {
  toolUseId: string;
  toolName: string;
  input?: Record<string, unknown>;
}

const S: Record<string, React.CSSProperties> = {
  root: { display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0, background: '#f8fafc' },
  header: {
    display: 'flex', alignItems: 'center', gap: 10,
    padding: '10px 28px', borderBottom: '1px solid #e2e8f0', background: '#ffffff',
    fontSize: '0.82rem', color: '#64748b', flexShrink: 0, flexWrap: 'wrap',
  },
  sessionTag: {
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.78rem',
    background: '#f1f5f9', color: '#475569', padding: '2px 8px', borderRadius: 6,
  },
  iconBtn: {
    background: '#ffffff', border: '1px solid #e2e8f0', color: '#475569',
    padding: '5px 12px', borderRadius: 7, cursor: 'pointer', fontSize: '0.82rem', fontWeight: 500,
    textDecoration: 'none', display: 'inline-flex', alignItems: 'center',
  },
  thread: {
    flex: 1,
    overflowY: 'auto',
    padding: '28px 36px',
    display: 'flex',
    flexDirection: 'column',
    gap: 12,
    overscrollBehavior: 'auto',
  },
  empty: { color: '#94a3b8', fontSize: '0.95rem', textAlign: 'center', marginTop: 100 },
  confirmCard: {
    alignSelf: 'stretch', maxWidth: 520, margin: '0 auto',
    background: '#fffbeb', border: '1px solid #fde68a', borderRadius: 12,
    padding: '16px 20px', boxShadow: '0 2px 8px rgba(146,64,14,0.08)',
    flexShrink: 0,
  },
  composer: {
    borderTop: '1px solid #e2e8f0', padding: '18px 28px',
    display: 'flex', gap: 12, background: '#ffffff',
  },
  textarea: {
    flex: 1, padding: '12px 16px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 10,
    color: '#0f172a', fontSize: '0.95rem', resize: 'none',
    minHeight: 48, maxHeight: 200, lineHeight: 1.55,
  },
  send: {
    padding: '0 24px',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none',
    borderRadius: 10, cursor: 'pointer', fontSize: '0.95rem', fontWeight: 600,
    boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  sendDisabled: { background: '#e2e8f0', color: '#94a3b8', cursor: 'not-allowed', boxShadow: 'none' },
  allowBtn: {
    padding: '8px 16px', background: '#059669', color: '#fff', border: 'none',
    borderRadius: 8, cursor: 'pointer', fontWeight: 600, fontSize: '0.88rem',
  },
  denyBtn: {
    padding: '8px 16px', background: '#ffffff', color: '#dc2626',
    border: '1px solid #fca5a5', borderRadius: 8, cursor: 'pointer', fontWeight: 600, fontSize: '0.88rem',
  },
};

let counter = 0;
const nextId = () => `m${Date.now().toString(36)}-${counter++}`;

function payloadText(payload?: Record<string, unknown>): string {
  if (!payload) return '';
  const text = payload.text ?? payload.message ?? payload.content;
  return text != null ? String(text) : '';
}

function errorText(evt: SessionEvent): string {
  const payload = evt.payload as Record<string, unknown> | undefined;
  const raw = payload?.error;
  const err = (typeof raw === 'object' && raw != null ? raw : {}) as Record<string, unknown>;
  const code = err.code != null ? String(err.code) : '';
  const message = err.message != null ? String(err.message) : '';
  const label = code ? `[${code}]` : '[error]';
  return `${label} ${message || 'Session turn failed'}`.trim();
}

function eventsToMessages(events: SessionEvent[]): Message[] {
  const out: Message[] = [];
  // Index of the current assistant turn bubble; content appends into it until
  // a status/error event closes the turn.
  let open = -1;
  const closeOpen = () => {
    if (open >= 0 && out[open].role === 'assistant') {
      out[open].pending = false;
      out[open].closed = true;
    }
    open = -1;
  };
  const ensureOpen = (seedId: string): Message => {
    if (open >= 0 && !out[open].closed) {
      return out[open];
    }
    const msg: Message = { id: `${seedId}-turn`, role: 'assistant', blocks: [], pending: true };
    out.push(msg);
    open = out.length - 1;
    return msg;
  };
  for (const evt of events) {
    if (evt.type === 'user.message') {
      closeOpen();
      out.push({
        id: evt.id,
        role: 'user',
        blocks: [{ kind: 'text', id: evt.id, text: payloadText(evt.payload) }],
      });
    } else if (evt.type === 'agent.turn_stub' || evt.type === 'agent.message' || evt.type === 'agent.thinking') {
      ensureOpen(evt.id).blocks.push({
        kind: evt.type === 'agent.thinking' ? 'thinking' : 'text',
        id: evt.id,
        text: payloadText(evt.payload) || '[agent response]',
      });
    } else if (evt.type === 'agent.tool_use') {
      ensureOpen(evt.id).blocks.push({
        kind: 'tool',
        id: String(evt.payload?.id ?? evt.payload?.toolCallId ?? evt.payload?.toolUseId ?? evt.id),
        toolName: String(evt.payload?.name ?? evt.payload?.toolName ?? 'tool'),
        text: evt.payload?.input != null ? JSON.stringify(evt.payload.input) : undefined,
      });
    } else if (evt.type === 'agent.tool_result') {
      const toolUseId = String(
        evt.payload?.tool_use_id ?? evt.payload?.toolCallId ?? evt.payload?.id ?? '',
      );
      const output = evt.payload?.output != null
        ? String(evt.payload.output)
        : payloadText(evt.payload);
      if (!toolUseId) continue;
      for (const m of out) {
        const idx = m.blocks.findIndex(b => b.kind === 'tool' && b.id === toolUseId);
        if (idx >= 0) {
          m.blocks = m.blocks.map((b, i) => (i === idx ? { ...b, result: output, toolState: String(evt.payload?.state || 'complete').toLowerCase() } : b));
          break;
        }
      }
    } else if (evt.type === 'session.error') {
      closeOpen();
      out.push({
        id: evt.id,
        role: 'error',
        blocks: [{ kind: 'text', id: evt.id, text: errorText(evt) }],
      });
    } else if (evt.type.startsWith('session.status')) {
      // Status events do not end the turn bubble: the backend may emit
      // status_idle between model iterations of one user question, followed
      // by more tool calls and the final text. Only user.message / session.error
      // (or a later user.message) close the bubble.
      if (open >= 0 && out[open].role === 'assistant') {
        out[open].pending = false;
      }
    }
  }
  return out;
}

function extractConfirmation(evt: SessionEvent): PendingConfirmation | null {
  if (evt.type === 'session.requires_action') {
    const p = evt.payload ?? {};
    const toolUseId = p.toolUseId != null ? String(p.toolUseId) : '';
    if (!toolUseId) return null;
    return {
      toolUseId,
      toolName: String(p.toolName ?? 'tool'),
      input: typeof p.input === 'object' && p.input != null ? p.input as Record<string, unknown> : undefined,
    };
  }
  if (evt.type === 'session.status_idle' || evt.type === 'session.status_requires_action') {
    const stopReason = evt.payload?.stopReason;
    if (stopReason && typeof stopReason === 'object') {
      const sr = stopReason as Record<string, unknown>;
      if (sr.toolUseId) {
        return {
          toolUseId: String(sr.toolUseId),
          toolName: String(sr.toolName ?? 'tool'),
          input: typeof sr.input === 'object' && sr.input != null ? sr.input as Record<string, unknown> : undefined,
        };
      }
    }
    if (evt.payload?.toolUseId) {
      return {
        toolUseId: String(evt.payload.toolUseId),
        toolName: String(evt.payload.toolName ?? 'tool'),
        input: typeof evt.payload.input === 'object' && evt.payload.input != null
          ? evt.payload.input as Record<string, unknown> : undefined,
      };
    }
  }
  return null;
}

/**
 * Chat bound to an existing Managed session. Does not create sessions —
 * POST user.message is the only turn driver.
 *
 * @param embedded — when true, hide session-hub navigation (for Team detail side panel).
 * @param readOnly — when true, hide composer mutations (e.g. completed team).
 */
export default function ChatPanel({
  sessionId,
  agentId,
  embedded = false,
  readOnly = false,
}: {
  sessionId: string;
  agentId: string;
  embedded?: boolean;
  readOnly?: boolean;
}) {
  const navigate = useNavigate();
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [restoring, setRestoring] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [managedSession, setManagedSession] = useState<ManagedSession | null>(null);
  const [envNameById, setEnvNameById] = useState<Map<string, string>>(new Map());
  const [pendingConfirm, setPendingConfirm] = useState<PendingConfirmation | null>(null);
  const [timelineEvents, setTimelineEvents] = useState<SessionEvent[]>([]);
  const streamHandleRef = useRef<EventStreamHandle | null>(null);
  const pendingUserMsgIdRef = useRef<string | null>(null);
  /** Id of the current open assistant turn bubble; null when no turn is active. */
  const openMsgIdRef = useRef<string | null>(null);
  const seenEventIdsRef = useRef<Set<string>>(new Set());
  const lastSeqRef = useRef(0);
  const bufferedEventsRef = useRef<Map<number, SessionEvent>>(new Map());
  const gapRepairRef = useRef<Promise<void> | null>(null);
  const gapRepairTimerRef = useRef<number | null>(null);
  const activeSessionRef = useRef('');

  useEffect(() => {
    listEnvironments()
      .then((envs: Environment[]) => setEnvNameById(new Map(envs.map(e => [e.id, e.name]))))
      .catch(() => setEnvNameById(new Map()));
  }, []);

  const applyManagedEvent = useCallback((evt: SessionEvent) => {
    if (evt.id) {
      if (seenEventIdsRef.current.has(evt.id)) return;
      seenEventIdsRef.current.add(evt.id);
    }
    if (typeof evt.seq === 'number' && evt.seq > lastSeqRef.current) {
      lastSeqRef.current = evt.seq;
    }
    setTimelineEvents((current) => {
      const index = current.findIndex((item) => item.id === evt.id);
      if (index < 0) return [...current, evt];
      return current.map((item, itemIndex) => itemIndex === index ? evt : item);
    });

    const confirm = extractConfirmation(evt);
    if (confirm) setPendingConfirm(confirm);

    const closeOpen = (prev: Message[]): Message[] => {
      const id = openMsgIdRef.current;
      openMsgIdRef.current = null;
      if (!id) return prev;
      return prev.map(m => (m.id === id ? { ...m, pending: false, closed: true } : m));
    };
    const append = (prev: Message[], seedId: string, block: ConversationContentBlock): Message[] => {
      const cur = openMsgIdRef.current;
      if (cur) {
        const existing = prev.find(m => m.id === cur);
        if (existing && !existing.closed) {
          // Avoid duplicate blocks for the same event id (preview vs persisted).
          if (existing.blocks.some(
            b => b.kind === block.kind && (b.id === block.id || b.id === seedId),
          )) return prev;
          return prev.map(m =>
            m.id === cur ? { ...m, blocks: [...m.blocks, block], pending: true } : m);
        }
      }
      openMsgIdRef.current = `${seedId}-turn`;
      return [...prev, { id: `${seedId}-turn`, role: 'assistant', blocks: [block], pending: true }];
    };

    if (evt.type === 'event_start') {
      const targetType = String(evt.payload?.type ?? '');
      const eventId = String(evt.payload?.event_id ?? '');
      if (!eventId || !['agent.message', 'agent.thinking'].includes(targetType)) return;
      // Reserve the turn bubble so deltas stream into it.
      setMessages(prev => append(prev, eventId, { kind: targetType === 'agent.thinking' ? 'thinking' : 'text', id: eventId, text: '' }));
      return;
    }

    if (evt.type === 'event_delta') {
      const targetType = String(evt.payload?.type ?? '');
      const eventId = String(evt.payload?.event_id ?? '');
      const delta = evt.payload?.delta != null ? String(evt.payload.delta) : '';
      if (!eventId || !delta) return;
      if (targetType === 'agent.message' || targetType === 'agent.thinking') {
        const kind = targetType === 'agent.thinking' ? 'thinking' : 'text';
        setMessages(prev => {
          const cur = openMsgIdRef.current;
          if (cur) {
            const existing = prev.find(m => m.id === cur);
            if (existing && !existing.closed) {
              return prev.map(m => {
                if (m.id !== cur) return m;
                const idx = m.blocks.findIndex(b => b.kind === kind && b.id === eventId);
                if (idx >= 0) {
                  return {
                    ...m,
                    blocks: m.blocks.map((b, i) =>
                      i === idx ? { ...b, text: (b.text ?? '') + delta } : b),
                    pending: true,
                  };
                }
                return { ...m, blocks: [...m.blocks, { kind, id: eventId, text: delta }], pending: true };
              });
            }
          }
          return append(prev, eventId, { kind, id: eventId, text: delta });
        });
      } else if (targetType === 'agent.tool_use') {
        setMessages(prev => {
          if (prev.some(message => message.blocks.some(block => block.kind === 'tool' && block.id === eventId))) {
            return prev.map(message => ({ ...message, blocks: message.blocks.map(block =>
              block.kind === 'tool' && block.id === eventId ? { ...block, text: (block.text || '') + delta } : block) }));
          }
          return append(prev, eventId, { kind: 'tool', id: eventId, toolName: 'tool', text: delta });
        });
      }
      return;
    }

    if (evt.type === 'user.message') {
      const text = payloadText(evt.payload);
      if (!text) return;
      const localUser = pendingUserMsgIdRef.current;
      pendingUserMsgIdRef.current = null;
      setMessages(prev => {
        const next = closeOpen(prev);
        if (next.some(m => m.id === evt.id)) return next;
        if (localUser && next.some(m => m.id === localUser)) {
          return next.map(m =>
            m.id === localUser
              ? { ...m, id: evt.id, blocks: [{ kind: 'text', id: evt.id, text }] }
              : m);
        }
        return [...next, { id: evt.id, role: 'user', blocks: [{ kind: 'text', id: evt.id, text }] }];
      });
      return;
    }

    if (evt.type === 'agent.message' || evt.type === 'agent.turn_stub' || evt.type === 'agent.thinking') {
      const kind = evt.type === 'agent.thinking' ? 'thinking' : 'text';
      const text = payloadText(evt.payload) || '[agent response]';
      setMessages(prev => {
        // The final persisted event carries the full text: replace the streamed
        // preview block instead of appending a duplicate.
        const cur = openMsgIdRef.current;
        if (cur) {
          const existing = prev.find(m => m.id === cur);
          if (existing && !existing.closed) {
            const idx = existing.blocks.findIndex(b => b.kind === kind && b.id === evt.id);
            if (idx >= 0) {
              return prev.map(m =>
                m.id === cur
                  ? { ...m, blocks: m.blocks.map((b, i) => (i === idx ? { ...b, text } : b)) }
                  : m);
            }
          }
        }
        return append(prev, evt.id, { kind, id: evt.id, text });
      });
      return;
    }

    if (evt.type === 'agent.tool_use') {
      const toolId = String(
        evt.payload?.id ?? evt.payload?.toolCallId ?? evt.payload?.toolUseId ?? evt.id,
      );
      const toolName = String(evt.payload?.name ?? evt.payload?.toolName ?? 'tool');
      const input = evt.payload?.input != null ? JSON.stringify(evt.payload.input) : undefined;
      setMessages(prev => {
        // Adopt the preview block (id = the event id) and finalize its id to the
        // tool-call id so tool_result can match it later.
        const cur = openMsgIdRef.current;
        if (cur) {
          const existing = prev.find(m => m.id === cur);
          if (existing && !existing.closed) {
            const idx = existing.blocks.findIndex(
              b => b.kind === 'tool' && (b.id === toolId || b.id === evt.id),
            );
            if (idx >= 0) {
              return prev.map(m =>
                m.id === cur
                  ? {
                      ...m,
                      blocks: m.blocks.map((b, i) =>
                        i === idx ? { ...b, id: toolId, toolName, text: input ?? b.text } : b),
                      pending: false,
                    }
                  : m);
            }
          }
        }
        return append(prev, evt.id, { kind: 'tool', id: toolId, toolName, text: input });
      });
      return;
    }

    if (evt.type === 'agent.tool_result') {
      const toolUseId = String(
        evt.payload?.tool_use_id ?? evt.payload?.toolCallId ?? evt.payload?.id ?? '',
      );
      const output = evt.payload?.output != null
        ? String(evt.payload.output)
        : payloadText(evt.payload);
      if (!toolUseId) return;
      setMessages(prev => {
        let updated = false;
        const next = prev.map(m => {
          const idx = m.blocks.findIndex(b => b.kind === 'tool' && b.id === toolUseId);
          if (idx < 0) return m;
          updated = true;
          return { ...m, blocks: m.blocks.map((b, i) => (i === idx ? { ...b, result: output, toolState: String(evt.payload?.state || 'complete').toLowerCase() } : b)) };
        });
        return updated ? next : prev;
      });
      return;
    }

    if (evt.type.startsWith('session.status')) {
      // Keep the turn bubble open across status events (the backend may emit
      // status_idle between model iterations of one user question); only
      // user.message / session.error close it.
      setMessages(prev => {
        const id = openMsgIdRef.current;
        if (!id) return prev;
        return prev.map(m => (m.id === id ? { ...m, pending: false } : m));
      });
      return;
    }

    if (evt.type === 'session.error') {
      setMessages(prev => {
        const next = closeOpen(prev);
        if (next.some(m => m.id === evt.id)) return next;
        return [...next, {
          id: evt.id,
          role: 'error',
          blocks: [{ kind: 'text', id: evt.id, text: errorText(evt) }],
        }];
      });
    }
  }, []);

  const repairManagedGap = useCallback(function repairManagedGap() {
    if (gapRepairRef.current) return gapRepairRef.current;
    const repairingSession = sessionId;
    const repair = (async () => {
      let retry: boolean;
      try {
        const recovered = await listEvents(sessionId, { after: lastSeqRef.current });
        if (activeSessionRef.current !== repairingSession) return;
        const merged = mergeContiguousEvents(
          lastSeqRef.current,
          bufferedEventsRef.current,
          recovered,
          event => event.seq,
        );
        for (const event of merged.accepted) applyManagedEvent(event);
        lastSeqRef.current = Math.max(lastSeqRef.current, merged.cursor);
        retry = bufferedEventsRef.current.size > 0;
      } catch {
        retry = activeSessionRef.current === repairingSession;
      } finally {
        if (activeSessionRef.current === repairingSession) gapRepairRef.current = null;
      }
      if (retry && gapRepairTimerRef.current == null) {
        gapRepairTimerRef.current = window.setTimeout(() => {
          gapRepairTimerRef.current = null;
          void repairManagedGap();
        }, 1_000);
      }
    })();
    gapRepairRef.current = repair;
    return repair;
  }, [applyManagedEvent, sessionId]);

  const handleManagedEvent = useCallback((evt: SessionEvent) => {
    // Stream-only previews use seq=-1 and are intentionally best-effort. Every
    // persisted event must remain contiguous; repair from history before
    // applying an out-of-order live event.
    if (evt.seq <= 0) {
      applyManagedEvent(evt);
      return;
    }
    const merged = mergeContiguousEvents(
      lastSeqRef.current,
      bufferedEventsRef.current,
      [evt],
      event => event.seq,
    );
    for (const event of merged.accepted) applyManagedEvent(event);
    lastSeqRef.current = Math.max(lastSeqRef.current, merged.cursor);
    if (bufferedEventsRef.current.size > 0) void repairManagedGap();
  }, [applyManagedEvent, repairManagedGap]);

  useEffect(() => {
    let cancelled = false;
    activeSessionRef.current = sessionId;
    setMessages([]);
    setInput('');
    setRestoring(true);
    setLoadError(null);
    setPendingConfirm(null);
    setTimelineEvents([]);
    setManagedSession(null);
    seenEventIdsRef.current = new Set();
    lastSeqRef.current = 0;
    bufferedEventsRef.current.clear();
    gapRepairRef.current = null;
    if (gapRepairTimerRef.current != null) window.clearTimeout(gapRepairTimerRef.current);
    gapRepairTimerRef.current = null;
    openMsgIdRef.current = null;
    pendingUserMsgIdRef.current = null;
    streamHandleRef.current?.close();
    streamHandleRef.current = null;

    async function run() {
      try {
        const sess = await getManagedSession(sessionId);
        if (cancelled) return;
        setManagedSession(sess);
        const events = await listEvents(sessionId);
        if (cancelled) return;
        for (const e of events) {
          if (e.id) seenEventIdsRef.current.add(e.id);
          if (typeof e.seq === 'number' && e.seq > lastSeqRef.current) {
            lastSeqRef.current = e.seq;
          }
        }
        setMessages(eventsToMessages(events));
        setTimelineEvents(events);
        streamHandleRef.current = streamEvents(
          sessionId,
          evt => { if (!cancelled) handleManagedEvent(evt); },
          () => { /* stream ended */ },
          {
            after: lastSeqRef.current,
            eventDeltas: ['agent.message', 'agent.thinking', 'agent.tool_use'],
            // Resume from the last seen sequence on automatic reconnects.
            getAfter: () => lastSeqRef.current,
            retryMs: 2000,
          },
        );
      } catch (e: unknown) {
        if (!cancelled) {
          setLoadError(e instanceof Error ? e.message : 'Failed to open session');
        }
      } finally {
        if (!cancelled) setRestoring(false);
      }
    }
    void run();
    return () => {
      cancelled = true;
      if (activeSessionRef.current === sessionId) activeSessionRef.current = '';
      if (gapRepairTimerRef.current != null) window.clearTimeout(gapRepairTimerRef.current);
      gapRepairTimerRef.current = null;
      streamHandleRef.current?.close();
      streamHandleRef.current = null;
    };
  }, [sessionId, handleManagedEvent]);

  const canSend = useMemo(
    () =>
      !readOnly &&
      !busy &&
      !restoring &&
      !loadError &&
      !pendingConfirm &&
      input.trim().length > 0,
    [readOnly, busy, restoring, loadError, pendingConfirm, input],
  );

  const mountLabel = useMemo(() => {
    if (!managedSession) return null;
    const env = envNameById.get(managedSession.environmentId) || managedSession.environmentId || '—';
    const vaults = managedSession.vaultIds?.length ?? 0;
    const mems = managedSession.memoryStoreIds?.length ?? 0;
    return `env: ${env} · vaults: ${vaults} · memory: ${mems}`;
  }, [managedSession, envNameById]);

  async function handleSend() {
    if (!canSend) return;
    const text = input.trim();
    setInput('');
    setBusy(true);
    const userMsg: Message = {
      id: nextId(),
      role: 'user',
      blocks: [{ kind: 'text', id: nextId(), text }],
    };
    pendingUserMsgIdRef.current = userMsg.id;
    setMessages(prev => [...prev, userMsg]);

    try {
      const recorded = await postUserMessage(sessionId, text);
      // Treat the POST response exactly like a stream delivery. A concurrent
      // writer may have committed a lower sequence first, so never advance the
      // resume cursor directly to the returned user event.
      for (const event of recorded) handleManagedEvent(event);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'send failed';
      setMessages(prev => [...prev, {
        id: nextId(),
        role: 'system',
        blocks: [{ kind: 'text', id: nextId(), text: `[error] ${msg}` }],
      }]);
      pendingUserMsgIdRef.current = null;
    } finally {
      setBusy(false);
    }
  }

  async function handleConfirmation(allow: boolean) {
    if (readOnly || !pendingConfirm) return;
    setBusy(true);
    try {
      await postToolConfirmation(
        sessionId,
        pendingConfirm.toolUseId,
        allow,
        allow ? undefined : 'Denied by user',
      );
      setPendingConfirm(null);
      setMessages(prev => [...prev, {
        id: nextId(),
        role: 'system',
        blocks: [{
          kind: 'text',
          id: nextId(),
          text: allow ? `Tool "${pendingConfirm.toolName}" allowed.` : `Tool "${pendingConfirm.toolName}" denied.`,
        }],
      }]);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'confirmation failed';
      setMessages(prev => [...prev, {
        id: nextId(),
        role: 'system',
        blocks: [{ kind: 'text', id: nextId(), text: `[error] ${msg}` }],
      }]);
    } finally {
      setBusy(false);
    }
  }

  function handleNewChat() {
    if (busy) return;
    navigate(`/managed/sessions/new?agentId=${encodeURIComponent(agentId)}`);
  }

  const sessionLabel = sessionId.slice(0, 24);

  if (loadError) {
    return (
      <div style={S.root}>
        <div style={S.empty}>
          {loadError}
          {!embedded && (
            <div style={{ marginTop: 16, display: 'flex', gap: 12, justifyContent: 'center' }}>
              <Link to="/managed/sessions" style={{ ...S.iconBtn, color: '#6366f1' }}>Conversations</Link>
              <Link
                to={`/managed/sessions/new?agentId=${encodeURIComponent(agentId)}`}
                style={{ ...S.iconBtn, color: '#6366f1' }}
              >
                New session
              </Link>
            </div>
          )}
        </div>
      </div>
    );
  }

  return (
    <div style={S.root}>
      <div style={S.header}>
        <span>{embedded ? 'Team chat' : 'Managed session'}</span>
        <span style={S.sessionTag} title={sessionId}>
          {restoring ? 'resolving…' : sessionLabel}{sessionId.length > 24 ? '…' : ''}
        </span>
        {!embedded && mountLabel && (
          <Link
            to={`/managed/sessions/${encodeURIComponent(sessionId)}?tab=details`}
            style={{ ...S.iconBtn, maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
            title="View / edit mounts on Details"
          >
            {mountLabel}
          </Link>
        )}
        <span style={{ flex: 1 }} />
        {!embedded && (
          <>
            <Link
              to={`/managed/sessions/${encodeURIComponent(sessionId)}?tab=details`}
              style={S.iconBtn}
              title="Session details and event timeline"
            >
              📊 Details
            </Link>
            <Link to="/managed/sessions" style={S.iconBtn}>
              📋 All sessions
            </Link>
            <button type="button" style={S.iconBtn} onClick={handleNewChat} disabled={busy}>
              ✨ New session
            </button>
          </>
        )}
        {embedded && (
          <Link
            to={`/managed/sessions/${encodeURIComponent(sessionId)}`}
            style={S.iconBtn}
            title="Open full session page"
          >
            Full page
          </Link>
        )}
      </div>
      <ConversationSurface
        className="min-h-0 flex-1 rounded-none border-x-0 border-b-0 shadow-none"
        messages={messages.map((message) => ({
          id: message.id,
          role: message.role,
          blocks: message.blocks,
          state: message.role === 'error' ? 'error' : message.pending ? 'streaming' : 'complete',
        }))}
        events={managedEventsToConversation(timelineEvents)}
        source="managed event log"
        loading={restoring}
        emptyMessage="Session ready. Send a message to start the first turn."
        accessory={pendingConfirm && !readOnly ? (
          <div style={S.confirmCard}>
            <div style={{ fontWeight: 700, color: '#92400e', marginBottom: 8 }}>
              Allow tool call: {pendingConfirm.toolName}?
            </div>
            {pendingConfirm.input && (
              <pre style={{
                fontSize: '0.78rem', color: '#78350f', background: '#fef3c7',
                padding: '8px 10px', borderRadius: 6, overflow: 'auto', maxHeight: 120,
              }}>
                {JSON.stringify(pendingConfirm.input, null, 2)}
              </pre>
            )}
            <div style={{ display: 'flex', gap: 10, marginTop: 12 }}>
              <button type="button" style={S.allowBtn} onClick={() => handleConfirmation(true)} disabled={busy}>Allow</button>
              <button type="button" style={S.denyBtn} onClick={() => handleConfirmation(false)} disabled={busy}>Deny</button>
            </div>
          </div>
        ) : undefined}
        composer={{
          value: input,
          onChange: setInput,
          onSubmit: handleSend,
          disabled: readOnly || restoring || !!pendingConfirm,
          busy,
          placeholder: readOnly
            ? 'Read-only transcript — sending is disabled'
            : restoring
              ? 'Loading…'
              : pendingConfirm
                ? 'Confirm the tool call above…'
                : `Message ${agentId}…`,
        }}
      />
    </div>
  );
}
