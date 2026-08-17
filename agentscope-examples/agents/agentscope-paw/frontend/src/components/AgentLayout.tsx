import { useEffect, useState } from 'react';
import { Outlet, useLocation, useNavigate, useParams } from 'react-router-dom';
import { AgentDefinition, getAgent } from '../api/agents';

const TABS: { key: string; label: string; icon: string }[] = [
  { key: 'chat', label: 'Chat', icon: '💬' },
  { key: 'workspace', label: 'Workspace', icon: '📁' },
  { key: 'skills', label: 'Skills', icon: '🛠' },
  { key: 'subagents', label: 'Subagents', icon: '🧩' },
  { key: 'tools', label: 'Tools', icon: '🧰' },
  { key: 'sessions', label: 'Sessions', icon: '📋' },
  { key: 'channels', label: 'Channels', icon: '📡' },
  { key: 'settings', label: 'Settings', icon: '⚙️' },
];

export default function AgentLayout() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const [agent, setAgent] = useState<AgentDefinition | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    setErr(null);
    getAgent(id)
      .then(a => { if (!cancelled) setAgent(a); })
      .catch(e => { if (!cancelled) setErr(e.message); });
    return () => { cancelled = true; };
  }, [id]);

  if (!id) return <div style={{ padding: 32 }}>Missing agent id.</div>;

  const activeTab =
    TABS.find(t => location.pathname.startsWith(`/agents/${id}/${t.key}`))?.key ?? 'chat';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={{
        padding: '22px 32px 0', borderBottom: '1px solid #e2e8f0',
        background: '#ffffff', flexShrink: 0,
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 16 }}>
          <span style={{
            display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
            width: 44, height: 44, borderRadius: 12,
            background: 'linear-gradient(135deg,#eef2ff 0%,#e0e7ff 100%)',
            border: '1px solid #c7d2fe',
            fontSize: '1.5rem',
          }}>🤖</span>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 3, minWidth: 0 }}>
            <span style={{ fontSize: '1.2rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.01em' }}>
              {agent?.name ?? id}
            </span>
            {agent?.description && (
              <span style={{ fontSize: '0.88rem', color: '#64748b' }}>{agent.description}</span>
            )}
          </div>
          {agent && (
            <span style={{
              marginLeft: 6, padding: '4px 12px', borderRadius: 999, fontSize: '0.74rem',
              fontWeight: 600, letterSpacing: '0.02em',
              background: agent.builtin ? '#eef2ff' : '#f1f5f9',
              color: agent.builtin ? '#4338ca' : '#475569',
              border: agent.builtin ? '1px solid #c7d2fe' : '1px solid #e2e8f0',
            }}>
              {agent.builtin ? 'built-in' : 'custom'}
            </span>
          )}
          <span style={{ flex: 1 }} />
          {err && <span style={{ marginLeft: 12, color: '#dc2626', fontSize: '0.85rem' }}>{err}</span>}
        </div>

        <div style={{ display: 'flex', gap: 4 }}>
          {TABS.map(t => {
            const active = activeTab === t.key;
            return (
              <button
                key={t.key}
                onClick={() => navigate(`/agents/${encodeURIComponent(id)}/${t.key}`)}
                style={{
                  display: 'flex', alignItems: 'center', gap: 8,
                  background: 'transparent', border: 'none',
                  borderBottom: `2px solid ${active ? '#6366f1' : 'transparent'}`,
                  padding: '12px 18px', cursor: 'pointer',
                  fontSize: '0.92rem',
                  color: active ? '#0f172a' : '#64748b',
                  fontWeight: active ? 600 : 500,
                  marginBottom: -1,
                }}
                onMouseEnter={e => { if (!active) e.currentTarget.style.color = '#0f172a'; }}
                onMouseLeave={e => { if (!active) e.currentTarget.style.color = '#64748b'; }}
              >
                <span>{t.icon}</span> {t.label}
              </button>
            );
          })}
        </div>
      </div>

      <div style={{ flex: 1, overflow: 'auto', background: '#f8fafc' }}>
        <Outlet context={{ agentId: id, agent }} />
      </div>
    </div>
  );
}
