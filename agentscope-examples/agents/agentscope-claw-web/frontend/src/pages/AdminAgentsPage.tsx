import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import AppShell from '../components/AppShell';
import AdminPageLayout from '../components/AdminPageLayout';

interface RegisteredAgentView {
  id: string;
  name: string;
  description: string | null;
  maxIters: number | null;
}

function authH(): Record<string, string> {
  return { Authorization: `Bearer ${localStorage.getItem('claw_token') ?? ''}` };
}

async function listAgents(): Promise<RegisteredAgentView[]> {
  const res = await fetch('/api/admin/agents', { headers: authH() });
  if (!res.ok) throw new Error(`Failed to load agents: ${res.status}`);
  return res.json();
}

const S: Record<string, React.CSSProperties> = {
  err:        { color: '#f87171', fontSize: '0.82rem', background: '#1f1520', border: '1px solid #5b2030', borderRadius: 8, padding: '8px 12px', marginBottom: 14 },
  table:      { width: '100%', borderCollapse: 'collapse' as const, fontSize: '0.83rem' },
  th:         { textAlign: 'left' as const, padding: '8px 10px', background: '#13151f', color: '#7c8bad', borderBottom: '1px solid #1e2235', fontWeight: 600 },
  td:         { padding: '8px 10px', borderBottom: '1px solid #1a1d2e', color: '#94a3b8', verticalAlign: 'top' as const },
  mono:       { fontFamily: 'monospace', fontSize: '0.78rem' },
  detailLink: { color: '#a5b4fc', textDecoration: 'none', borderBottom: '1px dashed #6366f1' },
  refreshBtn: { background: 'transparent', border: '1px solid #2d3148', color: '#7c8bad', borderRadius: 6, padding: '4px 10px', cursor: 'pointer', fontSize: '0.78rem' },
  hint:       { fontSize: '0.78rem', color: '#4b5280', marginBottom: 14, lineHeight: 1.6 },
};

export default function AdminAgentsPage() {
  const [agents,  setAgents]  = useState<RegisteredAgentView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error,   setError]   = useState<string | null>(null);

  async function load() {
    setLoading(true); setError(null);
    try { setAgents(await listAgents()); }
    catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setLoading(false); }
  }

  useEffect(() => { load(); }, []);

  return (
    <AppShell>
      <AdminPageLayout>
        {error && <div style={S.err}>{error}</div>}

        <div style={{ display: 'flex', alignItems: 'center', marginBottom: 12 }}>
          <h2 style={{ margin: 0, fontSize: '1.1rem', fontWeight: 700, color: '#e2e8f0' }}>
            Registered Agents
          </h2>
          <button style={{ ...S.refreshBtn, marginLeft: 12 }} onClick={load} disabled={loading}>
            {loading ? '…' : '↺ Refresh'}
          </button>
          <span style={{ marginLeft: 'auto', fontSize: '0.78rem', color: '#4b5280' }}>
            {agents.length} agent{agents.length !== 1 ? 's' : ''}
          </span>
        </div>

        <div style={S.hint}>
          Global agents are declared in <code style={{ color: '#6366f1' }}>agentscope.json</code> under each
          <code style={{ color: '#6366f1' }}> .agentscope/</code> workspace. This view is read-only —
          edit the JSON on the deployment host and restart claw to add or remove agents.
        </div>

        <table style={S.table}>
          <thead>
            <tr>
              <th style={S.th}>ID</th>
              <th style={S.th}>Name</th>
              <th style={S.th}>Description</th>
              <th style={S.th}>Max Iters</th>
            </tr>
          </thead>
          <tbody>
            {agents.map(a => (
              <tr key={a.id}>
                <td style={{ ...S.td, ...S.mono }}>
                  <Link to={`/agents/${encodeURIComponent(a.id)}`} style={S.detailLink}>{a.id}</Link>
                </td>
                <td style={{ ...S.td, fontWeight: 600, color: '#e2e8f0' }}>{a.name}</td>
                <td style={S.td}>{a.description ?? <span style={{ color: '#4b5280' }}>—</span>}</td>
                <td style={{ ...S.td, ...S.mono, textAlign: 'right' as const }}>{a.maxIters ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {!loading && agents.length === 0 && (
          <p style={{ color: '#4b5280', fontSize: '0.85rem', marginTop: 12 }}>No global agents found.</p>
        )}
      </AdminPageLayout>
    </AppShell>
  );
}
