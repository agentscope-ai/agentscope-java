import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AgentDefinition, listAgents } from '../api/agents';

type Filter = 'all' | 'builtin' | 'custom';

const FILTERS: { key: Filter; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'builtin', label: 'Built-in' },
  { key: 'custom', label: 'Custom' },
];

function badgeFor(a: AgentDefinition) {
  if (a.builtin) {
    return { label: 'built-in', bg: '#eef2ff', fg: '#4338ca', bd: '#c7d2fe' };
  }
  return { label: 'custom', bg: '#f1f5f9', fg: '#475569', bd: '#e2e8f0' };
}

function bucket(a: AgentDefinition, key: Filter): boolean {
  if (key === 'all') return true;
  if (key === 'builtin') return a.builtin;
  return !a.builtin;
}

export default function AgentsHubPage() {
  const navigate = useNavigate();
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<Filter>('all');

  async function refresh() {
    setLoading(true);
    try {
      const list = await listAgents();
      setAgents(list);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void refresh(); }, []);

  const visible = useMemo(() => agents.filter(a => bucket(a, filter)), [agents, filter]);

  return (
    <div style={{ padding: '40px 44px', maxWidth: 1200 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
        <h1 style={{ margin: 0, fontSize: '1.75rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.02em' }}>
          Agents
        </h1>
        <button
          onClick={() => navigate('/agents/new')}
          style={{
            display: 'inline-flex', alignItems: 'center', gap: 8,
            background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
            color: '#ffffff', border: 'none',
            borderRadius: 10, padding: '11px 20px', fontSize: '0.95rem', fontWeight: 600,
            cursor: 'pointer',
            boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
          }}
        >＋ New agent</button>
      </div>

      <p style={{ margin: '0 0 24px', color: '#64748b', fontSize: '1rem', lineHeight: 1.6, maxWidth: 720 }}>
        Each agent is defined by its workspace folder under <code style={{ background: '#f1f5f9', padding: '1px 6px', borderRadius: 4, fontSize: '0.92em' }}>~/.agentscope/agents/&lt;id&gt;/workspace</code>.
        Files like <code style={{ background: '#f1f5f9', padding: '1px 6px', borderRadius: 4, fontSize: '0.92em' }}>AGENTS.md</code>,
        {' '}<code style={{ background: '#f1f5f9', padding: '1px 6px', borderRadius: 4, fontSize: '0.92em' }}>tools.json</code>, skills and subagents shape its behaviour.
      </p>

      <div style={{ display: 'flex', gap: 8, marginBottom: 24, flexWrap: 'wrap' }}>
        {FILTERS.map(f => {
          const active = filter === f.key;
          const count = agents.filter(a => bucket(a, f.key)).length;
          return (
            <button
              key={f.key}
              onClick={() => setFilter(f.key)}
              style={{
                padding: '7px 14px', borderRadius: 999, cursor: 'pointer',
                fontSize: '0.85rem', fontWeight: 600,
                background: active ? '#0f172a' : '#ffffff',
                color: active ? '#ffffff' : '#475569',
                border: `1px solid ${active ? '#0f172a' : '#e2e8f0'}`,
                display: 'inline-flex', alignItems: 'center', gap: 6,
              }}
            >
              {f.label}
              <span style={{
                fontSize: '0.72rem', fontWeight: 600,
                padding: '1px 7px', borderRadius: 999,
                background: active ? 'rgba(255,255,255,0.18)' : '#f1f5f9',
                color: active ? '#ffffff' : '#64748b',
              }}>{count}</span>
            </button>
          );
        })}
      </div>

      {loading && <div style={{ color: '#64748b', fontSize: '0.95rem' }}>Loading…</div>}
      {err && <div style={{ color: '#dc2626', fontSize: '0.95rem', marginBottom: 16 }}>{err}</div>}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(300px,1fr))', gap: 18 }}>
        {visible.map(a => {
          const b = badgeFor(a);
          return (
            <div
              key={a.id}
              style={{
                position: 'relative',
                background: '#ffffff', border: '1px solid #e2e8f0',
                borderRadius: 14, padding: '20px 22px',
                display: 'flex', flexDirection: 'column', gap: 10,
                boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
                transition: 'transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease',
                cursor: 'pointer',
              }}
              onClick={() => navigate(`/agents/${encodeURIComponent(a.id)}/chat`)}
              onMouseEnter={e => {
                e.currentTarget.style.transform = 'translateY(-2px)';
                e.currentTarget.style.boxShadow = '0 8px 24px rgba(15,23,42,0.08), 0 2px 6px rgba(15,23,42,0.04)';
                e.currentTarget.style.borderColor = '#c7d2fe';
              }}
              onMouseLeave={e => {
                e.currentTarget.style.transform = 'translateY(0)';
                e.currentTarget.style.boxShadow = '0 1px 3px rgba(15,23,42,0.04)';
                e.currentTarget.style.borderColor = '#e2e8f0';
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <span style={{
                  display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                  width: 40, height: 40, borderRadius: 10,
                  background: 'linear-gradient(135deg,#eef2ff 0%,#e0e7ff 100%)',
                  border: '1px solid #c7d2fe', fontSize: '1.25rem', flexShrink: 0,
                }}>🤖</span>
                <span style={{ fontWeight: 600, fontSize: '1.05rem', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {a.name}
                </span>
                <span style={{
                  padding: '3px 10px', borderRadius: 999,
                  fontSize: '0.7rem', fontWeight: 600, letterSpacing: '0.02em',
                  background: b.bg, color: b.fg, border: `1px solid ${b.bd}`,
                  whiteSpace: 'nowrap',
                }}>{b.label}</span>
              </div>
              <div style={{ fontSize: '0.88rem', color: '#64748b', minHeight: 40, lineHeight: 1.5 }}>
                {a.description || <em style={{ color: '#94a3b8' }}>No description</em>}
              </div>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 4 }}>
                <span style={{ fontSize: '0.76rem', color: '#94a3b8', fontFamily: 'monospace' }}>
                  {a.id}
                </span>
              </div>
            </div>
          );
        })}
        {!loading && visible.length === 0 && (
          <div style={{ color: '#94a3b8', fontSize: '0.92rem', fontStyle: 'italic' }}>
            No agents in this view yet.
          </div>
        )}
      </div>
    </div>
  );
}
