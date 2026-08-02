import React, { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { getManagedSession, ManagedSession } from '../api/managedSessions';
import ChatPanel from '../components/ChatPanel';
import SessionTranscript from '../components/SessionTranscript';

type Tab = 'chat' | 'details';

const S: Record<string, React.CSSProperties> = {
  root: { display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 },
  bar: {
    display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap',
    padding: '14px 28px 0', borderBottom: '1px solid #e2e8f0', background: '#ffffff', flexShrink: 0,
  },
  back: {
    color: '#6366f1', textDecoration: 'none', fontSize: '0.85rem', fontWeight: 500,
  },
  title: { fontSize: '1.05rem', fontWeight: 700, color: '#0f172a', margin: 0 },
  meta: {
    fontSize: '0.78rem', color: '#94a3b8', fontFamily: 'ui-monospace, Menlo, monospace',
  },
  tabs: { display: 'flex', gap: 4, marginLeft: 8 },
  tab: {
    background: 'transparent', border: 'none', borderBottom: '2px solid transparent',
    padding: '12px 16px', cursor: 'pointer', fontSize: '0.9rem', color: '#64748b', fontWeight: 500,
    marginBottom: -1,
  },
  tabActive: { color: '#0f172a', fontWeight: 600, borderBottomColor: '#6366f1' },
  body: { flex: 1, minHeight: 0, overflow: 'auto' },
  err: { padding: 32, color: '#dc2626' },
  loading: { padding: 32, color: '#94a3b8' },
};

export default function SessionDetailPage() {
  const { sessionId = '' } = useParams<{ sessionId: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const tabParam = searchParams.get('tab');
  const tab: Tab = tabParam === 'details' ? 'details' : 'chat';
  const [session, setSession] = useState<ManagedSession | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  useEffect(() => {
    if (!sessionId) return;
    let cancelled = false;
    setLoading(true);
    setErr(null);
    getManagedSession(sessionId)
      .then(s => { if (!cancelled) setSession(s); })
      .catch(e => {
        if (!cancelled) {
          setSession(null);
          setErr(e instanceof Error ? e.message : 'Failed to load session');
        }
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [sessionId]);

  function setTab(next: Tab) {
    const params = new URLSearchParams(searchParams);
    if (next === 'chat') params.delete('tab');
    else params.set('tab', 'details');
    setSearchParams(params, { replace: true });
  }

  if (!sessionId) {
    return <div style={S.err}>Missing session id. <Link to="/sessions">Back to sessions</Link></div>;
  }

  if (loading) {
    return <div style={S.loading}>Loading session…</div>;
  }

  if (err || !session) {
    return (
      <div style={S.err}>
        {err || 'Session not found.'}{' '}
        <Link to="/sessions">Back to sessions</Link>
        {' · '}
        <Link to="/sessions/new">Create session</Link>
      </div>
    );
  }

  return (
    <div style={S.root}>
      <div style={S.bar}>
        <Link
          to={`/sessions?agentId=${encodeURIComponent(session.agentId)}`}
          style={S.back}
        >
          ← Sessions
        </Link>
        <h1 style={S.title}>Session</h1>
        <span style={S.meta} title={session.id}>{session.id}</span>
        <span style={{ flex: 1 }} />
        <div style={S.tabs}>
          <button
            type="button"
            style={{ ...S.tab, ...(tab === 'chat' ? S.tabActive : {}) }}
            onClick={() => setTab('chat')}
          >
            Chat
          </button>
          <button
            type="button"
            style={{ ...S.tab, ...(tab === 'details' ? S.tabActive : {}) }}
            onClick={() => setTab('details')}
          >
            Details
          </button>
        </div>
      </div>
      <div style={S.body}>
        {tab === 'chat' ? (
          <ChatPanel sessionId={session.id} agentId={session.agentId} />
        ) : (
          <SessionTranscript
            agentId={session.agentId}
            sessionId={session.id}
            embedded
            onDeleted={() => navigate('/sessions', { replace: true })}
          />
        )}
      </div>
    </div>
  );
}
