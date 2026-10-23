import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { currentSession, stream } from '../api/chat';
import { TurnEntry, turns as fetchTurns } from '../api/sessions';
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

const S: Record<string, React.CSSProperties> = {
  root: { display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0, background: '#f8fafc' },
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
};

let counter = 0;
const nextId = () => `m${Date.now().toString(36)}-${counter++}`;

const STORAGE_PREFIX = 'claw_chat_session:';
const storageKey = (agentId: string) => `${STORAGE_PREFIX}${agentId}`;

function turnsToMessages(turns: TurnEntry[]): Message[] {
  const out: Message[] = [];
  for (const t of turns) {
    const role = String(t.role).toUpperCase();
    if (role === 'USER') {
      out.push({ id: t.id, role: 'user', text: t.content ?? '', tools: [] });
    } else if (role === 'ASSISTANT') {
      out.push({ id: t.id, role: 'assistant', text: t.content ?? '', tools: [] });
    } else if (role === 'TOOL') {
      const last = out.length > 0 ? out[out.length - 1] : null;
      const tool: ToolEntry = {
        id: t.id,
        name: t.toolName ?? 'tool',
        input: t.toolInput ?? undefined,
        result: t.toolResult ?? undefined,
      };
      if (last && last.role === 'assistant') {
        last.tools = [...last.tools, tool];
      } else {
        out.push({ id: `${t.id}-host`, role: 'assistant', text: '', tools: [tool] });
      }
    }
  }
  return out;
}

export interface ChatPanelProps {
  agentId: string;
  /** Called after each successful message turn so the sessions sidebar can refresh. */
  onSessionUpdate?: () => void;
}

export default function ChatPanel({ agentId, onSessionUpdate }: ChatPanelProps) {
  const [searchParams, setSearchParams] = useSearchParams();
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [restoring, setRestoring] = useState(true);
  const [sessionKey, setSessionKey] = useState<string | null>(null);
  const threadRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);

  const persistSession = useCallback((key: string | null) => {
    if (key) {
      try { localStorage.setItem(storageKey(agentId), key); } catch { /* ignore quota */ }
    } else {
      try { localStorage.removeItem(storageKey(agentId)); } catch { /* ignore */ }
    }
  }, [agentId]);

  // On agent or URL session change: pick a session (URL > localStorage > backend default) and rehydrate.
  const urlSession = searchParams.get('session');
  useEffect(() => {
    let cancelled = false;
    setMessages([]);
    setInput('');
    setRestoring(true);

    const stored = (() => { try { return localStorage.getItem(storageKey(agentId)); } catch { return null; } })();

    async function run() {
      // URL-provided session always wins: it may be a freshly-minted UUID that the
      // backend has never seen yet, and we must not let `currentSession` overwrite it.
      let key: string | null = urlSession;
      if (!key) {
        try {
          const cur = await currentSession(agentId, stored ?? undefined);
          key = cur.sessionKey || stored || null;
        } catch {
          key = stored || null;
        }
      }
      if (cancelled) return;
      setSessionKey(key);
      if (key) {
        try {
          const list = await fetchTurns(agentId, key);
          if (cancelled) return;
          setMessages(turnsToMessages(list));
        } catch {
          // missing/empty session is fine — we just start empty
        }
      }
      if (cancelled) return;
      setRestoring(false);
      if (key && key !== urlSession) {
        const next = new URLSearchParams(searchParams);
        next.set('session', key);
        setSearchParams(next, { replace: true });
      }
    }
    run();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId, urlSession]);

  useEffect(() => {
    threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight });
  }, [messages]);

  const canSend = useMemo(() => !busy && !restoring && input.trim().length > 0, [busy, restoring, input]);

  async function handleSend() {
    if (!canSend) return;
    const text = input.trim();
    setInput('');
    setBusy(true);
    const userMsg: Message = { id: nextId(), role: 'user', text, tools: [] };
    const replyMsg: Message = { id: nextId(), role: 'assistant', text: '', tools: [], pending: true };
    setMessages(prev => [...prev, userMsg, replyMsg]);

    try {
      for await (const evt of stream(agentId, { message: text, sessionKey: sessionKey ?? undefined })) {
        if (evt.type === 'token') {
          const chunk = evt.data ?? '';
          setMessages(prev => prev.map(m => m.id === replyMsg.id ? { ...m, text: m.text + chunk } : m));
        } else if (evt.type === 'tool_call') {
          const entry: ToolEntry = {
            id: `${evt.toolName ?? 'tool'}-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
            name: evt.toolName ?? 'tool',
            input: evt.toolInput,
          };
          setMessages(prev => prev.map(m => m.id === replyMsg.id ? { ...m, tools: [...m.tools, entry] } : m));
        } else if (evt.type === 'tool_result') {
          setMessages(prev => prev.map(m => {
            if (m.id !== replyMsg.id) return m;
            const tools = [...m.tools];
            for (let i = tools.length - 1; i >= 0; i--) {
              if (tools[i].name === evt.toolName && !tools[i].result) {
                tools[i] = { ...tools[i], result: evt.toolResult };
                return { ...m, tools };
              }
            }
            tools.push({
              id: `${evt.toolName ?? 'tool'}-${Date.now()}`,
              name: evt.toolName ?? 'tool',
              result: evt.toolResult,
            });
            return { ...m, tools };
          }));
        } else if (evt.type === 'done') {
          if (evt.sessionKey) {
            setSessionKey(evt.sessionKey);
            persistSession(evt.sessionKey);
            const next = new URLSearchParams(searchParams);
            if (next.get('session') !== evt.sessionKey) {
              next.set('session', evt.sessionKey);
              setSearchParams(next, { replace: true });
            }
          }
          setMessages(prev => prev.map(m => m.id === replyMsg.id ? { ...m, pending: false } : m));
        } else if (evt.type === 'error') {
          setMessages(prev => prev.map(m => m.id === replyMsg.id
            ? { ...m, pending: false, text: m.text + (m.text ? '\n' : '') + `[error] ${evt.error ?? 'unknown'}` }
            : m));
        }
      }
      onSessionUpdate?.();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'stream failed';
      setMessages(prev => prev.map(m => m.id === replyMsg.id
        ? { ...m, pending: false, text: m.text + (m.text ? '\n' : '') + `[error] ${msg}` }
        : m));
    } finally {
      setBusy(false);
      inputRef.current?.focus();
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }

  return (
    <div style={S.root}>
      <div style={S.thread} ref={threadRef}>
        {restoring && messages.length === 0 && (
          <div style={S.empty}>Loading conversation…</div>
        )}
        {!restoring && messages.length === 0 && (
          <div style={S.empty}>
            Start a new conversation. Try <code style={{ background: '#e2e8f0', padding: '1px 6px', borderRadius: 4 }}>/reset</code> to clear the session.
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
                  <ToolCallBlock
                    key={t.id}
                    toolName={t.name}
                    toolCallId={t.id}
                    result={t.result}
                  />
                ))}
              </div>
            )}
            {m.text || (m.pending ? <span style={{ color: '#94a3b8' }}>…</span> : null)}
          </div>
        ))}
      </div>
      <div style={S.composer}>
        <textarea
          ref={inputRef}
          style={S.textarea}
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={restoring ? 'Loading…' : `Message ${agentId}…`}
          rows={1}
          autoFocus
          disabled={restoring}
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
