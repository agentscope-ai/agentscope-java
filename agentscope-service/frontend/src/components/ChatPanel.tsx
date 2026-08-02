import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Environment, listEnvironments } from '../api/environments';
import { ManagedFile, createFile, listFiles } from '../api/files';
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
import NewManagedSessionForm from './NewManagedSessionForm';
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
    } else if (evt.type === 'agent.turn_stub' || evt.type === 'agent.message') {
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
    } else if (evt.type === 'agent.tool_result') {
      const toolUseId = String(
        evt.payload?.tool_use_id ?? evt.payload?.toolCallId ?? evt.payload?.id ?? '',
      );
      const output = evt.payload?.output != null
        ? String(evt.payload.output)
        : payloadText(evt.payload);
      if (!toolUseId) continue;
      for (let i = out.length - 1; i >= 0; i--) {
        const m = out[i];
        if (m.role !== 'assistant') continue;
        const idx = m.tools.findIndex(t => t.id === toolUseId);
        if (idx >= 0) {
          m.tools = m.tools.map((t, j) => (j === idx ? { ...t, result: output } : t));
          break;
        }
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
  const [managedSession, setManagedSession] = useState<ManagedSession | null>(null);
  const [envNameById, setEnvNameById] = useState<Map<string, string>>(new Map());
  const [needsCreate, setNeedsCreate] = useState(false);
  const [showCreate, setShowCreate] = useState(false);
  const [pendingConfirm, setPendingConfirm] = useState<PendingConfirmation | null>(null);
  const [files, setFiles] = useState<ManagedFile[]>([]);
  const [selectedFileIds, setSelectedFileIds] = useState<string[]>([]);
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

  useEffect(() => {
    listFiles().then(setFiles).catch(() => setFiles([]));
    listEnvironments()
      .then((envs: Environment[]) => setEnvNameById(new Map(envs.map(e => [e.id, e.name]))))
      .catch(() => setEnvNameById(new Map()));
  }, []);

  async function handleUploadFile(file: File) {
    const content = await file.text();
    const created = await createFile({ filename: file.name, content });
    setFiles(prev => [created, ...prev]);
    setSelectedFileIds(prev => prev.includes(created.id) ? prev : [...prev, created.id]);
  }

  const seenEventIdsRef = useRef<Set<string>>(new Set());
  const lastSeqRef = useRef(0);

  const handleManagedEvent = useCallback((evt: SessionEvent) => {
    // Preview frames have null id / seq=-1 — skip id dedupe, track by event_id in payload.
    if (evt.id) {
      if (seenEventIdsRef.current.has(evt.id)) return;
      seenEventIdsRef.current.add(evt.id);
    }
    if (typeof evt.seq === 'number' && evt.seq > lastSeqRef.current) {
      lastSeqRef.current = evt.seq;
    }

    const confirm = extractConfirmation(evt);
    if (confirm) setPendingConfirm(confirm);

    if (evt.type === 'event_start') {
      const targetType = String(evt.payload?.type ?? '');
      const eventId = String(evt.payload?.event_id ?? '');
      if (!eventId) return;
      if (targetType === 'agent.message') {
        const localReply = replyMsgIdRef.current;
        replyMsgIdRef.current = eventId;
        setMessages(prev => {
          if (prev.some(m => m.id === eventId)) return prev;
          if (localReply) {
            return prev.map(m => (m.id === localReply ? { ...m, id: eventId, pending: true } : m));
          }
          return [...prev, { id: eventId, role: 'assistant', text: '', tools: [], pending: true }];
        });
      }
      return;
    }

    if (evt.type === 'event_delta') {
      const targetType = String(evt.payload?.type ?? '');
      const eventId = String(evt.payload?.event_id ?? '');
      const delta = evt.payload?.delta != null ? String(evt.payload.delta) : '';
      if (!eventId || !delta) return;
      if (targetType === 'agent.message') {
        setMessages(prev => {
          const exists = prev.some(m => m.id === eventId);
          if (exists) {
            replyMsgIdRef.current = eventId;
            return prev.map(m => (m.id === eventId ? { ...m, text: m.text + delta, pending: true } : m));
          }
          const localReply = replyMsgIdRef.current;
          if (localReply && localReply !== eventId && prev.some(m => m.id === localReply)) {
            replyMsgIdRef.current = eventId;
            return prev.map(m =>
              m.id === localReply ? { ...m, id: eventId, text: m.text + delta, pending: true } : m);
          }
          replyMsgIdRef.current = eventId;
          return [...prev, { id: eventId, role: 'assistant', text: delta, tools: [], pending: true }];
        });
      } else if (targetType === 'agent.tool_use') {
        // Live tool-arg preview: show accumulating JSON on the latest assistant bubble.
        setMessages(prev => {
          const lastAssistantIdx = [...prev].map((m, i) => ({ m, i })).reverse()
            .find(x => x.m.role === 'assistant')?.i;
          if (lastAssistantIdx == null) {
            return [...prev, {
              id: `${eventId}-host`,
              role: 'assistant',
              text: '',
              tools: [{ id: eventId, name: 'tool', input: delta }],
              pending: true,
            }];
          }
          return prev.map((m, i) => {
            if (i !== lastAssistantIdx) return m;
            const existing = m.tools.find(t => t.id === eventId);
            const tools = existing
              ? m.tools.map(t => (t.id === eventId ? { ...t, input: (t.input ?? '') + delta } : t))
              : [...m.tools, { id: eventId, name: 'tool', input: delta }];
            return { ...m, tools, pending: true };
          });
        });
      }
      return;
    }

    if (evt.type === 'user.message') {
      const text = payloadText(evt.payload);
      if (text) {
        setMessages(prev => {
          if (prev.some(m => m.id === evt.id)) return prev;
          return [...prev, { id: evt.id, role: 'user', text, tools: [] }];
        });
      }
    } else if (evt.type === 'agent.message' || evt.type === 'agent.turn_stub') {
      const text = payloadText(evt.payload);
      setMessages(prev => {
        if (prev.some(m => m.id === evt.id)) {
          return prev.map(m =>
            m.id === evt.id ? { ...m, text: text || m.text || '[agent response]', pending: false } : m);
        }
        const replyId = replyMsgIdRef.current;
        if (replyId && prev.some(m => m.id === replyId)) {
          return prev.map(m =>
            m.id === replyId
              ? { ...m, id: evt.id, text: text || m.text || '[agent response]', pending: false }
              : m);
        }
        return [...prev, { id: evt.id, role: 'assistant', text: text || '[agent response]', tools: [] }];
      });
      replyMsgIdRef.current = null;
    } else if (evt.type === 'agent.tool_use') {
      const tool: ToolEntry = {
        id: String(evt.payload?.id ?? evt.payload?.toolCallId ?? evt.payload?.toolUseId ?? evt.id),
        name: String(evt.payload?.name ?? evt.payload?.toolName ?? 'tool'),
        input: evt.payload?.input != null ? JSON.stringify(evt.payload.input) : undefined,
      };
      // Prefer matching by preview event id when tool_use was streamed.
      const previewKey = evt.id;
      setMessages(prev => {
        let matched = false;
        const next = prev.map(m => {
          if (m.role !== 'assistant') return m;
          const tools = m.tools.map(t => {
            if (t.id === previewKey || t.id === tool.id) {
              matched = true;
              return { ...tool, id: tool.id };
            }
            return t;
          });
          return matched ? { ...m, tools, pending: false } : m;
        });
        if (matched) return next;
        const last = next[next.length - 1];
        if (last?.role === 'assistant') {
          return next.map((m, i) =>
            i === next.length - 1 ? { ...m, tools: [...m.tools, tool], pending: false } : m);
        }
        return [...next, { id: `${evt.id}-host`, role: 'assistant', text: '', tools: [tool] }];
      });
    } else if (evt.type === 'agent.tool_result') {
      const toolUseId = String(
        evt.payload?.tool_use_id ?? evt.payload?.toolCallId ?? evt.payload?.id ?? '',
      );
      const output = evt.payload?.output != null
        ? String(evt.payload.output)
        : payloadText(evt.payload);
      if (!toolUseId) return;
      setMessages(prev => prev.map(m => {
        if (m.role !== 'assistant') return m;
        if (!m.tools.some(t => t.id === toolUseId)) return m;
        return {
          ...m,
          tools: m.tools.map(t => (t.id === toolUseId ? { ...t, result: output } : t)),
        };
      }));
    } else if (evt.type === 'session.status_idle' && !confirm) {
      const replyId = replyMsgIdRef.current;
      if (replyId) {
        setMessages(prev => prev.map(m => m.id === replyId ? { ...m, pending: false } : m));
        replyMsgIdRef.current = null;
      }
    }
  }, []);

  const managedParam = searchParams.get('managed') ?? '';

  const openSession = useCallback(async (
    sessionId: string,
    cancelled: () => boolean,
    opts?: { syncUrl?: boolean },
  ) => {
    const sess = await getManagedSession(sessionId);
    if (cancelled()) return;
    setManagedSession(sess);
    setManagedSessionId(sessionId);
    setNeedsCreate(false);
    setShowCreate(false);
    persistManagedSession(sessionId);
    if (opts?.syncUrl !== false) {
      navigate(`/agents/${encodeURIComponent(agentId)}/chat?managed=${encodeURIComponent(sessionId)}`, { replace: true });
    }
    seenEventIdsRef.current = new Set();
    lastSeqRef.current = 0;
    try {
      const events = await listEvents(sessionId);
      if (!cancelled()) {
        for (const e of events) {
          if (e.id) seenEventIdsRef.current.add(e.id);
          if (typeof e.seq === 'number' && e.seq > lastSeqRef.current) {
            lastSeqRef.current = e.seq;
          }
        }
        setMessages(eventsToMessages(events));
      }
    } catch { /* empty */ }
    if (cancelled()) return;
    streamHandleRef.current?.close();
    streamHandleRef.current = streamEvents(
      sessionId,
      evt => { if (!cancelled()) handleManagedEvent(evt); },
      () => { /* stream ended */ },
      {
        after: lastSeqRef.current,
        eventDeltas: ['agent.message', 'agent.thinking', 'agent.tool_use'],
      },
    );
  }, [agentId, handleManagedEvent, navigate, persistManagedSession]);

  // Managed session restore + SSE (no silent auto-create).
  useEffect(() => {
    let cancelled = false;
    setMessages([]);
    setInput('');
    setRestoring(true);
    setPendingConfirm(null);
    setManagedSession(null);
    setNeedsCreate(false);
    streamHandleRef.current?.close();
    streamHandleRef.current = null;

    async function run() {
      const stored = (() => { try { return localStorage.getItem(managedStorageKey(agentId)); } catch { return null; } })();
      const sessionId = managedParam || stored;
      try {
        if (!sessionId) {
          if (!cancelled) {
            setManagedSessionId(null);
            setNeedsCreate(true);
            setShowCreate(true);
            setRestoring(false);
          }
          return;
        }
        // URL already has the id when deep-linked; avoid a navigate→effect loop.
        await openSession(sessionId, () => cancelled, { syncUrl: !managedParam });
      } catch (e: unknown) {
        if (!cancelled) {
          persistManagedSession(null);
          setManagedSessionId(null);
          setNeedsCreate(true);
          setShowCreate(true);
          const msg = e instanceof Error ? e.message : 'failed to open managed session';
          setMessages([{ id: nextId(), role: 'system', text: `[error] ${msg}`, tools: [] }]);
        }
        return;
      }
      if (!cancelled) setRestoring(false);
    }
    void run();
    return () => {
      cancelled = true;
      streamHandleRef.current?.close();
      streamHandleRef.current = null;
    };
  }, [agentId, managedParam, openSession, persistManagedSession]);

  useEffect(() => {
    threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight });
  }, [messages, pendingConfirm]);

  const canSend = useMemo(
    () => !busy && !restoring && !pendingConfirm && !needsCreate && input.trim().length > 0 && !!managedSessionId,
    [busy, restoring, pendingConfirm, needsCreate, input, managedSessionId],
  );

  const mountLabel = useMemo(() => {
    if (!managedSession) return null;
    const env = envNameById.get(managedSession.environmentId) || managedSession.environmentId || '—';
    const vaults = managedSession.vaultIds?.length ?? 0;
    const mems = managedSession.memoryStoreIds?.length ?? 0;
    return `env: ${env} · vaults: ${vaults} · memory: ${mems}`;
  }, [managedSession, envNameById]);

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

  function handleNewChat() {
    if (busy) return;
    if (messages.length > 0 && !confirm('Start a new chat?')) return;
    streamHandleRef.current?.close();
    setMessages([]);
    setPendingConfirm(null);
    setManagedSession(null);
    setManagedSessionId(null);
    persistManagedSession(null);
    setNeedsCreate(true);
    setShowCreate(true);
    setRestoring(false);
  }

  async function handleSessionCreated(session: ManagedSession) {
    setRestoring(true);
    setMessages([{ id: nextId(), role: 'system', text: 'New managed session started.', tools: [] }]);
    setSelectedFileIds([]);
    try {
      await openSession(session.id, () => false);
    } catch (e: unknown) {
      setMessages([{ id: nextId(), role: 'system', text: `[error] ${e instanceof Error ? e.message : 'failed'}`, tools: [] }]);
    } finally {
      setRestoring(false);
    }
  }

  const sessionLabel = managedSessionId ? managedSessionId.slice(0, 24) : (needsCreate ? 'no session' : '…');

  return (
    <div style={S.root}>
      <div style={S.header}>
        <span>Managed session</span>
        <span style={S.sessionTag} title={managedSessionId ?? ''}>
          {restoring ? 'resolving…' : sessionLabel}{sessionLabel.length >= 24 ? '…' : ''}
        </span>
        {mountLabel && managedSessionId && (
          <button
            type="button"
            style={{ ...S.iconBtn, maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
            title="View / edit mounts on the session transcript page"
            onClick={() => navigate(`/agents/${encodeURIComponent(agentId)}/sessions/_managed?managed=${encodeURIComponent(managedSessionId)}`)}
          >
            {mountLabel}
          </button>
        )}
        <span style={{ flex: 1 }} />
        {files.length > 0 && (
          <select
            style={{ ...S.iconBtn, maxWidth: 180 }}
            value=""
            onChange={e => {
              const id = e.target.value;
              if (!id) return;
              setSelectedFileIds(prev => prev.includes(id) ? prev : [...prev, id]);
            }}
            title="Attach uploaded file to the next new session"
          >
            <option value="">Attach file…</option>
            {files.map(f => (
              <option key={f.id} value={f.id}>{f.filename}</option>
            ))}
          </select>
        )}
        {selectedFileIds.length > 0 && (
          <span style={{ fontSize: '0.75rem', color: '#64748b' }}>
            {selectedFileIds.length} file(s) for next session
            <button
              type="button"
              style={{ ...S.iconBtn, marginLeft: 6, padding: '2px 8px' }}
              onClick={() => setSelectedFileIds([])}
            >
              Clear
            </button>
          </span>
        )}
        <label style={{ ...S.iconBtn, cursor: 'pointer' }} title="Upload a text file (mounted on next new session)">
          📎 Upload
          <input
            type="file"
            accept=".md,.txt,.json,.csv,.yaml,.yml,.xml,.html,.js,.ts,.py,.go,.java"
            style={{ display: 'none' }}
            onChange={e => {
              const f = e.target.files?.[0];
              if (f) void handleUploadFile(f).catch(err => {
                setMessages(prev => [...prev, {
                  id: nextId(), role: 'system',
                  text: `[error] ${err instanceof Error ? err.message : 'upload failed'}`,
                  tools: [],
                }]);
              });
              e.target.value = '';
            }}
          />
        </label>
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
        {!restoring && needsCreate && !showCreate && (
          <div style={S.empty}>
            No session selected.{' '}
            <button type="button" style={{ ...S.iconBtn, color: '#6366f1' }} onClick={() => setShowCreate(true)}>
              Create a new session
            </button>
          </div>
        )}
        {!restoring && !needsCreate && messages.length === 0 && (
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
      {showCreate && (
        <NewManagedSessionForm
          agentId={agentId}
          initialFileIds={selectedFileIds}
          onCancel={needsCreate && !managedSessionId ? undefined : () => setShowCreate(false)}
          onCreated={session => { void handleSessionCreated(session); }}
        />
      )}
    </div>
  );
}
