import React, { useEffect, useState } from 'react';
import { TurnEntry, turns, resetSession, deleteSession, markRead } from '../api/sessions';
import { ManagedSession, archiveManagedSession, deleteManagedSession, getManagedSession } from '../api/managedSessions';
import { useNavigate } from 'react-router-dom';
import ToolCallBlock from './ToolCallBlock';
import SessionEventTimeline from './SessionEventTimeline';

const S: Record<string, React.CSSProperties> = {
  root: { padding: '28px 32px', minWidth: 0, maxWidth: 1100 },
  bar: { display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 },
  title: { fontSize: '1.25rem', fontWeight: 700, color: '#0f172a', margin: 0, letterSpacing: '-0.01em' },
  back: {
    background: '#ffffff', border: '1px solid #e2e8f0', color: '#475569',
    padding: '7px 14px', borderRadius: 8, cursor: 'pointer', fontSize: '0.85rem', fontWeight: 500,
  },
  btn: {
    padding: '7px 14px', borderRadius: 8, cursor: 'pointer',
    fontSize: '0.85rem', fontWeight: 500, border: '1px solid #cbd5e1',
    background: '#ffffff', color: '#475569',
  },
  danger: { color: '#dc2626', borderColor: '#fca5a5' },
  primary: {
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none',
    boxShadow: '0 2px 6px rgba(99,102,241,0.25), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  meta: { fontSize: '0.82rem', color: '#94a3b8', fontFamily: 'monospace', marginBottom: 22 },
  msg: { padding: '14px 18px', borderRadius: 12, marginBottom: 14, fontSize: '0.95rem', lineHeight: 1.6, whiteSpace: 'pre-wrap', wordBreak: 'break-word' },
  user: { background: '#eef2ff', color: '#1e1b4b', borderLeft: '3px solid #6366f1' },
  assistant: { background: '#ffffff', color: '#0f172a', border: '1px solid #e2e8f0', boxShadow: '0 1px 2px rgba(15,23,42,0.04)' },
  tool: { background: '#f8fafc', border: '1px dashed #cbd5e1', color: '#475569' },
  role: { fontSize: '0.74rem', color: '#94a3b8', textTransform: 'uppercase', letterSpacing: '0.1em', fontWeight: 600, marginBottom: 6 },
  err: { color: '#dc2626', fontSize: '0.9rem' },
};

/** Sentinel {@code sessionKey} used by callers linking directly to a managed session (no legacy gateway session exists). */
const MANAGED_ONLY_KEY = '_managed';

export default function SessionTranscript({
  agentId,
  sessionKey,
  managedSessionId,
}: {
  agentId: string;
  sessionKey: string;
  managedSessionId?: string;
}) {
  // A pure managed-session view (deep-linked from the managed inbox) has no legacy gateway
  // session to load turns/read-state for — everything comes from `/api/sessions/*` instead.
  const managedOnly = sessionKey === MANAGED_ONLY_KEY && !!managedSessionId;
  const [entries, setEntries] = useState<TurnEntry[]>([]);
  const [managedSession, setManagedSession] = useState<ManagedSession | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const navigate = useNavigate();

  async function reload() {
    setErr(null);
    try {
      if (managedOnly && managedSessionId) {
        setManagedSession(await getManagedSession(managedSessionId));
      } else {
        setEntries(await turns(agentId, sessionKey));
      }
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed');
    }
  }

  useEffect(() => {
    reload();
    if (!managedOnly) markRead(agentId, sessionKey).catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId, sessionKey, managedSessionId]);

  async function handleReset() {
    if (!confirm('Reset this session? History will be cleared.')) return;
    await resetSession(agentId, sessionKey).catch(e => setErr(String(e)));
    reload();
  }
  async function handleArchiveManaged() {
    if (!managedSessionId || !confirm('Archive this managed session?')) return;
    try {
      await archiveManagedSession(managedSessionId);
      reload();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed');
    }
  }
  async function handleDelete() {
    if (!confirm('Delete this session entirely?')) return;
    try {
      if (managedOnly && managedSessionId) {
        await deleteManagedSession(managedSessionId);
      } else {
        await deleteSession(agentId, sessionKey);
      }
      navigate(`/agents/${encodeURIComponent(agentId)}/sessions`, { replace: true });
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed');
    }
  }

  return (
    <div style={S.root}>
      <div style={S.bar}>
        <button style={S.back} onClick={() => navigate(`/agents/${encodeURIComponent(agentId)}/sessions`)}>← Back</button>
        <h2 style={S.title}>Transcript</h2>
        <span style={{ flex: 1 }} />
        <button
          style={{ ...S.btn, ...S.primary }}
          onClick={() => navigate(
            managedOnly
              ? `/agents/${encodeURIComponent(agentId)}/chat?managed=${encodeURIComponent(managedSessionId!)}`
              : `/agents/${encodeURIComponent(agentId)}/chat?session=${encodeURIComponent(sessionKey)}`,
          )}
          title="Resume this conversation in the Chat tab"
        >
          ▶ Continue in Chat
        </button>
        {managedOnly ? (
          <button style={S.btn} onClick={handleArchiveManaged}>Archive</button>
        ) : (
          <button style={S.btn} onClick={handleReset}>Reset</button>
        )}
        <button style={{ ...S.btn, ...S.danger }} onClick={handleDelete}>Delete</button>
      </div>
      <div style={S.meta}>
        {managedOnly ? managedSessionId : sessionKey}
        {managedSession && ` · ${managedSession.status}`}
      </div>
      {err && <div style={S.err}>{err}</div>}
      {!managedOnly && !err && entries.length === 0 && (
        <div style={{ color: '#94a3b8', fontSize: '0.85rem' }}>No turns recorded.</div>
      )}
      {!managedOnly && entries.map(t => {
        const role = String(t.role).toUpperCase();
        if (role === 'TOOL') {
          return (
            <div key={t.id} style={{ marginBottom: 12 }}>
              <ToolCallBlock
                toolName={t.toolName ?? 'tool'}
                toolCallId={t.id}
                result={t.toolResult ?? t.toolInput ?? ''}
              />
            </div>
          );
        }
        const style = role === 'USER' ? S.user : role === 'ASSISTANT' ? S.assistant : S.tool;
        return (
          <div key={t.id} style={{ ...S.msg, ...style }}>
            <div style={S.role}>{role}</div>
            {t.content ?? ''}
          </div>
        );
      })}
      {managedSessionId && <SessionEventTimeline managedSessionId={managedSessionId} />}
    </div>
  );
}
