import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import AdminPageLayout from '../../components/admin/AdminPageLayout';
import {
  getOverview, listInstances,
  OverviewView, InstanceView,
} from '../../api/admin';

// ── helpers ───────────────────────────────────────────────────────────
function timeAgo(ms: number): string {
  const d = Date.now() - ms;
  if (d < 60_000)    return `${Math.floor(d / 1000)}s ago`;
  if (d < 3_600_000) return `${Math.floor(d / 60_000)}m ago`;
  if (d < 86_400_000)return `${Math.floor(d / 3_600_000)}h ago`;
  return `${Math.floor(d / 86_400_000)}d ago`;
}

function uptime(ms: number): string {
  const s = Math.floor(ms / 1000);
  if (s < 60)   return `${s}s`;
  if (s < 3600) return `${Math.floor(s / 60)}m ${s % 60}s`;
  return `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`;
}

// ── styles ────────────────────────────────────────────────────────────
const S: Record<string, React.CSSProperties> = {
  grid4: { display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 18, marginBottom: 28 },
  grid2: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 22, marginBottom: 22 },
  kpi: {
    background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 14,
    padding: '22px 24px', cursor: 'pointer',
    transition: 'border-color 0.15s, box-shadow 0.15s, transform 0.08s',
    position: 'relative' as const,
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
  kpiHover: {
    background: '#ffffff', border: '1px solid #c7d2fe', borderRadius: 14,
    padding: '22px 24px', cursor: 'pointer',
    position: 'relative' as const,
    boxShadow: '0 8px 24px rgba(79,70,229,0.10), 0 2px 6px rgba(15,23,42,0.04)',
    transform: 'translateY(-1px)',
  },
  kpiIcon: { fontSize: '1.5rem', marginBottom: 10, display: 'block' },
  kpiLabel: {
    fontSize: '0.78rem', fontWeight: 700, letterSpacing: '0.08em',
    textTransform: 'uppercase' as const, color: '#64748b', marginBottom: 8,
  },
  kpiValue: { fontSize: '2.4rem', fontWeight: 700, color: '#4f46e5', lineHeight: 1, letterSpacing: '-0.02em' },
  kpiSub:   { fontSize: '0.82rem', color: '#94a3b8', marginTop: 8 },
  kpiArrow: {
    position: 'absolute' as const, right: 16, top: '50%', transform: 'translateY(-50%)',
    color: '#cbd5e1', fontSize: '1rem',
  },
  section: {
    background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 14,
    overflow: 'hidden',
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
  sectionHeader: {
    padding: '14px 20px', background: '#f8fafc', borderBottom: '1px solid #e5e7eb',
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
  },
  sectionTitle: { fontSize: '0.95rem', fontWeight: 600, color: '#0f172a' },
  seeAll: {
    fontSize: '0.82rem', color: '#4f46e5', cursor: 'pointer',
    background: 'none', border: 'none', padding: 0, fontWeight: 500,
  },
  table:  { width: '100%', borderCollapse: 'collapse' as const, fontSize: '0.9rem' },
  th:     { padding: '12px 20px', textAlign: 'left' as const, color: '#64748b', fontWeight: 600, borderBottom: '1px solid #e5e7eb', background: '#f8fafc', fontSize: '0.78rem', textTransform: 'uppercase' as const, letterSpacing: '0.04em' },
  td:     { padding: '12px 20px', borderBottom: '1px solid #f1f5f9', color: '#334155' },
  mono:   { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.82rem', color: '#64748b' },
  agentPill: {
    display: 'inline-block', padding: '2px 10px', borderRadius: 999,
    fontSize: '0.8rem', background: '#dbeafe', color: '#1d4ed8', fontWeight: 500,
  },
  statusBase: {
    display: 'inline-block', width: 8, height: 8, borderRadius: '50%', marginRight: 8,
  } as React.CSSProperties,
  refreshBtn: {
    background: '#ffffff', border: '1px solid #d1d5db', color: '#475569',
    borderRadius: 8, padding: '7px 16px', cursor: 'pointer', fontSize: '0.86rem', fontWeight: 500,
    transition: 'border-color 0.12s, color 0.12s',
  },
  err: {
    color: '#dc2626', fontSize: '0.9rem', padding: '12px 16px',
    background: '#fef2f2', border: '1px solid #fecaca', borderRadius: 10, marginBottom: 18,
  },
};

// ── KPI card with hover ───────────────────────────────────────────────
function KpiCard({
  icon, label, value, sub, onClick,
}: { icon: string; label: string; value: string | number; sub?: string; onClick: () => void }) {
  const [hover, setHover] = useState(false);
  return (
    <div
      style={hover ? S.kpiHover : S.kpi}
      onClick={onClick}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
    >
      <span style={S.kpiIcon}>{icon}</span>
      <div style={S.kpiLabel}>{label}</div>
      <div style={S.kpiValue}>{value}</div>
      {sub && <div style={S.kpiSub}>{sub}</div>}
      <span style={S.kpiArrow}>›</span>
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────
export default function OverviewPage() {
  const navigate = useNavigate();
  const [data,      setData]      = useState<OverviewView | null>(null);
  const [instances, setInstances] = useState<InstanceView[]>([]);
  const [loading,   setLoading]   = useState(true);
  const [error,     setError]     = useState<string | null>(null);
  const [startMs]                 = useState(Date.now());
  const [now,       setNow]       = useState(Date.now());

  // Tick for live uptime counter
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  async function load() {
    setLoading(true); setError(null);
    try {
      const [ov, inst] = await Promise.all([getOverview(), listInstances()]);
      setData(ov);
      setInstances(inst);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    } finally { setLoading(false); }
  }

  useEffect(() => { load(); }, []);

  // Recent activity from overview (top 5)
  const recentActivity = data?.recentActivity.slice(0, 5) ?? [];

  return (
    <>
      <AdminPageLayout>
        {/* Toolbar */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 28 }}>
          <h2 style={{ margin: 0, fontSize: '1.5rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.02em' }}>
            Platform Overview
          </h2>
          <button style={S.refreshBtn} onClick={load} disabled={loading}>
            {loading ? '…' : '↺ Refresh'}
          </button>
          <span style={{ marginLeft: 'auto', fontSize: '0.85rem', color: '#94a3b8' }}>
            Uptime: {uptime(now - startMs)}
          </span>
        </div>

        {error && <div style={S.err}>{error}</div>}

        {/* KPI cards — 4 columns, each navigates to detail page */}
        {data && (
          <div style={S.grid4}>
            <KpiCard
              icon="🗂" label="Active Sessions" value={data.activeSessionCount}
              sub="click to browse"
              onClick={() => navigate('/admin/sessions')}
            />
            <KpiCard
              icon="⚡" label="Live Instances" value={instances.length}
              sub="click to manage"
              onClick={() => navigate('/admin/instances')}
            />
            <KpiCard
              icon="🤖" label="Global Agents" value={data.registeredAgentCount}
              sub="click to configure"
              onClick={() => navigate('/admin/agents')}
            />
            <KpiCard
              icon="👥" label="Users" value={data.totalUserCount}
              sub="click to manage"
              onClick={() => navigate('/admin/users')}
            />
          </div>
        )}

        {/* Second row: 2 more KPIs */}
        {data && (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 18, marginBottom: 28 }}>
            <KpiCard
              icon="📡" label="Channels" value={data.registeredChannelCount}
              sub="click to view bindings"
              onClick={() => navigate('/admin/channels')}
            />
            <KpiCard
              icon="📈" label="Usage" value="View Metrics"
              sub="turns, users, agents"
              onClick={() => navigate('/admin/usage')}
            />
          </div>
        )}

        {/* Live tables: side by side */}
        <div style={S.grid2}>

          {/* Registered agents */}
          <div style={S.section}>
            <div style={S.sectionHeader}>
              <span style={S.sectionTitle}>🤖 Registered Agents</span>
              <button style={S.seeAll} onClick={() => navigate('/admin/instances')}>See all →</button>
            </div>
            <table style={S.table}>
              <thead>
                <tr>
                  <th style={S.th}>Agent</th>
                  <th style={S.th}>Class</th>
                </tr>
              </thead>
              <tbody>
                {instances.length === 0 && (
                  <tr><td style={{ ...S.td, color: '#94a3b8' }} colSpan={2}>No registered agents.</td></tr>
                )}
                {instances.slice(0, 5).map(i => (
                  <tr key={i.agentId} style={{ cursor: 'pointer' }}
                    onClick={() => navigate('/admin/instances')}>
                    <td style={S.td}>
                      <span style={{ ...S.statusBase, background: '#16a34a' }} />
                      <span style={S.agentPill}>{i.agentId}</span>
                    </td>
                    <td style={{ ...S.td, ...S.mono }}>{i.className}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Recent sessions */}
          <div style={S.section}>
            <div style={S.sectionHeader}>
              <span style={S.sectionTitle}>🗂 Recent Sessions</span>
              <button style={S.seeAll} onClick={() => navigate('/admin/sessions')}>See all →</button>
            </div>
            <table style={S.table}>
              <thead>
                <tr>
                  <th style={S.th}>Session (short)</th>
                  <th style={S.th}>Agent</th>
                  <th style={S.th}>Last Active</th>
                </tr>
              </thead>
              <tbody>
                {recentActivity.length === 0 && (
                  <tr><td style={{ ...S.td, color: '#94a3b8' }} colSpan={3}>No recent activity.</td></tr>
                )}
                {recentActivity.map(a => (
                  <tr key={a.sessionKey} style={{ cursor: 'pointer' }}
                    onClick={() => navigate('/admin/sessions')}>
                    <td style={{ ...S.td, ...S.mono }}>{a.sessionKey.slice(0, 20)}…</td>
                    <td style={S.td}><span style={S.agentPill}>{a.agentId ?? '—'}</span></td>
                    <td style={{ ...S.td, fontSize: '0.85rem' }}>{a.lastActivityMs ? timeAgo(a.lastActivityMs) : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        {/* Quick links row */}
        <div style={{
          background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 14,
          padding: '18px 22px', display: 'flex', gap: 14, flexWrap: 'wrap' as const,
          alignItems: 'center', marginTop: 22,
          boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
        }}>
          <span style={{ fontSize: '0.85rem', color: '#475569', fontWeight: 600 }}>Quick actions:</span>
          {[
            { label: 'Agents',      path: '/agents'   },
            { label: 'Users',       path: '/users'    },
            { label: 'View Config', path: '/config'   },
            { label: 'Debug Logs',  path: '/debug'    },
          ].map(l => (
            <button key={l.path} onClick={() => navigate(l.path)} style={{
              background: '#eef2ff', border: '1px solid #c7d2fe', color: '#4338ca',
              borderRadius: 8, padding: '7px 16px', cursor: 'pointer', fontSize: '0.86rem', fontWeight: 500,
              transition: 'background 0.12s, border-color 0.12s',
            }}>
              {l.label}
            </button>
          ))}
        </div>
      </AdminPageLayout>
    </>
  );
}
