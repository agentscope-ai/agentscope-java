import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ensureDefaultEnvironment } from '../api/environments';
import {
  createManagedSession,
  EventStreamHandle,
  getManagedSession,
  listEvents,
  postToolConfirmation,
  postUserMessage,
  SessionEvent,
  streamEvents,
} from '../api/managedSessions';
import ToolCallBlock from './ToolCallBlock';

type Role = 'user' | 'assistant' | 'system';

interface ToolEntry {
  id: string;
  name: string;
  input?: string;
  result?: string;
}

interface Message {
  id: string;
  role: Role;
  text: string;
  tools: ToolEntry[];
  pending?: boolean;
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
  },
  thread: { flex: 1, overflowY: 'auto', padding: '28px 36px', display: 'flex', flexDirection: 'column', gap: 18 },
  empty: { color: '#94a3b8', fontSize: '0.95rem', textAlign: 'center', marginTop: 100 },
  bubble: {
    maxWidth: '78%', padding: '14px 18px', borderRadius: 14,
    fontSize: '0.95rem', lineHeight: 1.6, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
  },
  user: {
    alignSelf: 'flex-end',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff',
    boxShadow: '0 2px 6px rgba(99,102,241,0.25)',
  },
  assistant: {
    alignSelf: 'flex-start', background: '#ffffff', color: '#0f172a',
    border: '1px solid #e2e8f0',
    boxShadow: '0 1px 2px rgba(15,23,42,0.04)',
  },
  system: {
    alignSelf: 'center', background: 'transparent', color: '#94a3b8',
    fontSize: '0.85rem', fontStyle: 'italic',
  },
  confirmCard: {
    alignSelf: 'stretch', maxWidth: 520, margin: '0 auto',
    background: '#fffbeb', border: '1px solid #fde68a', borderRadius: 12,
    padding: '16px 20px', boxShadow: '0 2px 8px rgba(146,64,14,0.08)',
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

const MANAGED_STORAGE_PREFIX = 'claw_managed_session:';
const managedStorageKey = (agentId: string) => `${MANAGED_STORAGE_PREFIX}${agentId}`;

function payloadText(payload?: Record<string, unknown>): string {
  if (!payload) return '';
  const text = payload.text ?? payload.message ?? payload.content;
  return text != null ? String(text) : '';
}

function eventsToMessages(events: SessionEvent[]): Message[] {
  const out: Message[] = [];
  for (const evt of events) {
    if (evt.type === 'user.message') {
      out.push({ id: evt.id, role: 'user', text: payloadText(evt.payload), tools: [] });
    } else if (evt.type === 'agent.turn_stub' || evt.type.startsWith('agent.message')) {
      out.push({ id: evt.id, role: 'assistant', text: payloadText(evt.payload) || '[agent response]', tools: [] });
    } else if (evt.type === 'agent.tool_use') {
      const tool: ToolEntry = {
        id: String(evt.payload?.id ?? evt.payload?.toolCallId ?? evt.payload?.toolUseId ?? evt.id),
        name: String(evt.payload?.name ?? evt.payload?.toolName ?? 'tool'),
        input: evt.payload?.input != null ? JSON.stringify(evt.payload.input) : undefined,
      };
      const last = out[out.length - 1];
      if (last?.role === 'assistant') {
        last.tools = [...last.tools, tool];
      } else {
        out.push({ id: `${evt.id}-host`, role: 'assistant', text: '', tools: [tool] });
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
 * Managed-session chat panel. The legacy `/api/agents/{id}/chat/*` path was removed in the
 * four-plane split — this panel talks exclusively to the managed session API (`/api/sessions/*`).
 */
export default function ChatPanel({ agentId }: { agentId: string }) {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [restoring, setRestoring] = useState(true);
  const [managedSessionId, setManagedSessionId] = useState<string | null>(null);
  const [pendingConfirm, setPendingConfirm] = useState<PendingConfirmation | null>(null);
  const threadRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const streamHandleRef = useRef<EventStreamHandle | null>(null);
  const replyMsgIdRef = useRef<string | null>(null);

  const persistManagedSession = useCallback((id: string | null) => {
    if (id) {
      try { localStorage.setItem(managedStorageKey(agentId), id); } catch { /* ignore */ }
    } else {
      try { localStorage.removeItem(managedStorageKey(agentId)); } catch { /* ignore */ }
    }
  }, [agentId]);

  const handleManagedEvent = useCallback((evt: SessionEvent) => {
    const confirm = extractConfirmation(evt);
    if (confirm) setPendingConfirm(confirm);

    if (evt.type === 'user.message') {
      const text = payloadText(evt.payload);
      if (text) {
        setMessages(prev => {
          if (prev.some(m => m.id === evt.id)) return prev;
          return [...prev, { id: evt.id, role: 'user', text, tools: [] }];
        });
      }
    } else if (evt.type === 'agent.turn_stub' || evt.type.startsWith('agent.message') || evt.type === 'agent.token') {
      const text = payloadText(evt.payload);
      const replyId = replyMsgIdRef.current;
      if (replyId) {
        setMessages(prev => prev.map(m => m.id === replyId
          ? { ...m, text: m.text + (text || ''), pending: false }
          : m));
      } else {
        setMessages(prev => [...prev, { id: evt.id, role: 'assistant', text: text || '[agent response]', tools: [] }]);
      }
      replyMsgIdRef.current = null;
    } else if (evt.type === 'agent.tool_use') {
      const tool: ToolEntry = {
        id: String(evt.payload?.id ?? evt.payload?.toolCallId ?? evt.payload?.toolUseId ?? evt.id),
        name: String(evt.payload?.name ?? evt.payload?.toolName ?? 'tool'),
        input: evt.payload?.input != null ? JSON.stringify(evt.payload.input) : undefined,
      };
      const replyId = replyMsgIdRef.current;
      setMessages(prev => prev.map(m => {
        if (replyId && m.id !== replyId) return m;
        if (!replyId && m.role !== 'assistant') return m;
        return { ...m, tools: [...m.tools, tool], pending: false };
      }));
    } else if (evt.type === 'session.status_idle' && !confirm) {
      const replyId = replyMsgIdRef.current;
      if (replyId) {
        setMessages(prev => prev.map(m => m.id === replyId ? { ...m, pending: false } : m));
        replyMsgIdRef.current = null;
      }
    }
  }, []);

  // Managed session restore + SSE
  useEffect(() => {
    let cancelled = false;
    setMessages([]);
    setInput('');
    setRestoring(true);
    setPendingConfirm(null);
    streamHandleRef.current?.close();
    streamHandleRef.current = null;

    async function run() {
      const urlManagedId = searchParams.get('managed');
      const stored = (() => { try { return localStorage.getItem(managedStorageKey(agentId)); } catch { return null; } })();
      let sessionId = urlManagedId || stored;
      try {
        if (!sessionId) {
          const env = await ensureDefaultEnvironment();
          const session = await createManagedSession({ agent: agentId, environmentId: env.id });
          sessionId = session.id;
        } else if (urlManagedId) {
          // Deep-linked from the inbox — verify it still exists before adopting it.
          await getManagedSession(sessionId);
        }
        persistManagedSession(sessionId);
        if (cancelled) return;
        setManagedSessionId(sessionId);
        try {
          const events = await listEvents(sessionId);
          if (!cancelled) setMessages(eventsToMessages(events));
        } catch { /* empty */ }
        if (cancelled) return;
        streamHandleRef.current = streamEvents(
          sessionId,
          evt => { if (!cancelled) handleManagedEvent(evt); },
          () => { /* stream ended */ },
        );
      } catch (e: unknown) {
        if (!cancelled) {
          const msg = e instanceof Error ? e.message : 'failed to open managed session';
          setMessages([{ id: nextId(), role: 'system', text: `[error] ${msg}`, tools: [] }]);
        }
        return;
      }
      if (!cancelled) setRestoring(false);
    }
    run();
    return () => {
      cancelled = true;
      streamHandleRef.current?.close();
      streamHandleRef.current = null;
    };
  }, [agentId, handleManagedEvent, persistManagedSession]);

  useEffect(() => {
    threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight });
  }, [messages, pendingConfirm]);

  const canSend = useMemo(
    () => !busy && !restoring && !pendingConfirm && input.trim().length > 0 && !!managedSessionId,
    [busy, restoring, pendingConfirm, input, managedSessionId],
  );

  async function handleSend() {
    if (!canSend || !managedSessionId) return;
    const text = input.trim();
    setInput('');
    setBusy(true);
    const userMsg: Message = { id: nextId(), role: 'user', text, tools: [] };
    const replyMsg: Message = { id: nextId(), role: 'assistant', text: '', tools: [], pending: true };
    replyMsgIdRef.current = replyMsg.id;
    setMessages(prev => [...prev, userMsg, replyMsg]);

    try {
      await postUserMessage(managedSessionId, text);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'send failed';
      setMessages(prev => prev.map(m => m.id === replyMsg.id
        ? { ...m, pending: false, text: `[error] ${msg}` }
        : m));
      replyMsgIdRef.current = null;
    } finally {
      setBusy(false);
      inputRef.current?.focus();
    }
  }

  async function handleConfirmation(allow: boolean) {
    if (!managedSessionId || !pendingConfirm) return;
    setBusy(true);
    try {
      await postToolConfirmation(
        managedSessionId,
        pendingConfirm.toolUseId,
        allow,
        allow ? undefined : 'Denied by user',
      );
      setPendingConfirm(null);
      setMessages(prev => [...prev, {
        id: nextId(),
        role: 'system',
        text: allow ? `Tool "${pendingConfirm.toolName}" allowed.` : `Tool "${pendingConfirm.toolName}" denied.`,
        tools: [],
      }]);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'confirmation failed';
      setMessages(prev => [...prev, { id: nextId(), role: 'system', text: `[error] ${msg}`, tools: [] }]);
    } finally {
      setBusy(false);
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }

  async function handleNewChat() {
    if (busy) return;
    if (messages.length > 0 && !confirm('Start a new chat?')) return;

    streamHandleRef.current?.close();
    setRestoring(true);
    setMessages([]);
    setPendingConfirm(null);
    persistManagedSession(null);
    try {
      const env = await ensureDefaultEnvironment();
      const session = await createManagedSession({ agent: agentId, environmentId: env.id });
      setManagedSessionId(session.id);
      persistManagedSession(session.id);
      streamHandleRef.current = streamEvents(session.id, handleManagedEvent);
      setMessages([{ id: nextId(), role: 'system', text: 'New managed session started.', tools: [] }]);
    } catch (e: unknown) {
      setMessages([{ id: nextId(), role: 'system', text: `[error] ${e instanceof Error ? e.message : 'failed'}`, tools: [] }]);
    } finally {
      setRestoring(false);
    }
  }

  const sessionLabel = managedSessionId ? managedSessionId.slice(0, 24) : 'creating…';

  return (
    <div style={S.root}>
      <div style={S.header}>
        <span>Managed session</span>
        <span style={S.sessionTag} title={managedSessionId ?? ''}>
          {restoring ? 'resolving…' : sessionLabel}{sessionLabel.length >= 24 ? '…' : ''}
        </span>
        <span style={{ flex: 1 }} />
        {managedSessionId && (
          <button
            type="button"
            style={S.iconBtn}
            onClick={() => navigate(`/agents/${encodeURIComponent(agentId)}/sessions/_managed?managed=${encodeURIComponent(managedSessionId)}`)}
            title="View managed session event timeline"
          >
            📊 Events
          </button>
        )}
        <button
          type="button"
          style={S.iconBtn}
          onClick={() => navigate(`/agents/${encodeURIComponent(agentId)}/sessions`)}
        >
          📋 All sessions
        </button>
        <button type="button" style={S.iconBtn} onClick={handleNewChat} disabled={busy}>
          ✨ New chat
        </button>
      </div>
      <div style={S.thread} ref={threadRef}>
        {restoring && messages.length === 0 && <div style={S.empty}>Loading conversation…</div>}
        {!restoring && messages.length === 0 && (
          <div style={S.empty}>
            Managed session ready. Messages stream via the session event log.
          </div>
        )}
        {messages.map(m => (
          <div key={m.id} style={{
            ...S.bubble,
            ...(m.role === 'user' ? S.user : m.role === 'system' ? S.system : S.assistant),
          }}>
            {m.tools.length > 0 && (
              <div style={{ marginBottom: m.text ? 10 : 0 }}>
                {m.tools.map(t => (
                  <ToolCallBlock key={t.id} toolName={t.name} toolCallId={t.id} result={t.result} />
                ))}
              </div>
            )}
            {m.text || (m.pending ? <span style={{ color: '#94a3b8' }}>…</span> : null)}
          </div>
        ))}
        {pendingConfirm && (
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
        )}
      </div>
      <div style={S.composer}>
        <textarea
          ref={inputRef}
          style={S.textarea}
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={restoring ? 'Loading…' : pendingConfirm ? 'Confirm tool call above…' : `Message ${agentId}…`}
          rows={1}
          autoFocus
          disabled={restoring || !!pendingConfirm}
        />
        <button
          style={{ ...S.send, ...(canSend ? {} : S.sendDisabled) }}
          onClick={handleSend}
          disabled={!canSend}
        >
          {busy ? '…' : 'Send'}
        </button>
      </div>
    </div>
  );
}
