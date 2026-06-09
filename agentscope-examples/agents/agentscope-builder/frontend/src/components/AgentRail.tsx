import React, { useEffect, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { listAgents, AgentDefinition } from '../api/agents';

export default function AgentRail() {
  const navigate = useNavigate();
  const location = useLocation();
  const { id: activeId } = useParams<{ id?: string }>();
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    listAgents()
      .then(list => { if (!cancelled) { setAgents(list); setErr(null); } })
      .catch(e => { if (!cancelled) setErr(e.message); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [location.pathname]);

  const mine = agents.filter(a => a.scope === 'user');
  const shared = agents.filter(a => a.scope === 'global');

  return (
    <div style={{
      width: 256, background: '#ffffff', borderRight: '1px solid #e2e8f0',
      display: 'flex', flexDirection: 'column', flexShrink: 0, overflowY: 'auto',
    }}>
      <div style={{ padding: '18px 16px 12px', borderBottom: '1px solid #f1f5f9' }}>
        <button
          onClick={() => navigate('/agents/new')}
          style={{
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
            width: '100%', background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
            color: '#ffffff', border: 'none',
            borderRadius: 10, padding: '11px 14px', fontSize: '0.92rem', fontWeight: 600,
            cursor: 'pointer',
            boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
          }}
          onMouseEnter={e => (e.currentTarget.style.boxShadow = '0 4px 12px rgba(99,102,241,0.45), inset 0 1px 0 rgba(255,255,255,0.18)')}
          onMouseLeave={e => (e.currentTarget.style.boxShadow = '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)')}
        >
          <span style={{ fontSize: '1rem' }}>＋</span> New agent
        </button>
        <button
          onClick={() => navigate('/agents')}
          style={{
            display: 'flex', alignItems: 'center', gap: 8, marginTop: 10,
            width: '100%', background: '#f8fafc', color: '#334155',
            border: '1px solid #e2e8f0', borderRadius: 10, padding: '9px 12px',
            fontSize: '0.88rem', fontWeight: 500, cursor: 'pointer',
          }}
          onMouseEnter={e => (e.currentTarget.style.background = '#eef2ff')}
          onMouseLeave={e => (e.currentTarget.style.background = '#f8fafc')}
        >
          <span>⊞</span> Agents hub
        </button>
      </div>

      <Section title="Mine" agents={mine} activeId={activeId} navigate={navigate} loading={loading} err={err} />
      <Section title="Shared" agents={shared} activeId={activeId} navigate={navigate} loading={loading} err={null} />
    </div>
  );
}

function Section({ title, agents, activeId, navigate, loading, err }: {
  title: string;
  agents: AgentDefinition[];
  activeId: string | undefined;
  navigate: (path: string) => void;
  loading: boolean;
  err: string | null;
}) {
  return (
    <div style={{ padding: '14px 10px 6px' }}>
      <div style={{
        fontSize: '0.7rem', fontWeight: 700, letterSpacing: '0.1em',
        color: '#94a3b8', textTransform: 'uppercase', padding: '0 8px', marginBottom: 8,
      }}>
        {title}
      </div>
      {loading && <div style={{ padding: '6px 10px', fontSize: '0.82rem', color: '#94a3b8' }}>Loading…</div>}
      {err && <div style={{ padding: '6px 10px', fontSize: '0.82rem', color: '#dc2626' }}>{err}</div>}
      {!loading && !err && agents.length === 0 && (
        <div style={{ padding: '6px 10px', fontSize: '0.82rem', color: '#94a3b8' }}>None.</div>
      )}
      {agents.map(a => {
        const active = a.id === activeId;
        return (
          <button
            key={a.id}
            onClick={() => navigate(`/agents/${encodeURIComponent(a.id)}/chat`)}
            style={{
              display: 'flex', alignItems: 'center', gap: 10, width: '100%',
              background: active ? '#eef2ff' : 'transparent',
              border: active ? '1px solid #c7d2fe' : '1px solid transparent',
              borderRadius: 9, padding: '9px 10px', cursor: 'pointer',
              fontSize: '0.9rem', color: active ? '#3730a3' : '#475569',
              textAlign: 'left', fontWeight: active ? 600 : 500,
              overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
              marginBottom: 2,
            }}
            onMouseEnter={e => { if (!active) e.currentTarget.style.background = '#f8fafc'; }}
            onMouseLeave={e => { if (!active) e.currentTarget.style.background = 'transparent'; }}
            title={a.description ?? a.name}
          >
            <span style={{ fontSize: '1rem', flexShrink: 0 }}>🤖</span>
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{a.name}</span>
          </button>
        );
      })}
    </div>
  );
}
