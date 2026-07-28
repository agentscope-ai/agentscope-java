import React, { useEffect, useMemo, useState } from 'react';
import {
  ActiveTool,
  ActiveToolsResponse,
  disableConfiguredTool,
  fetchConfiguredActive,
} from '../api/tools';

interface Props {
  agentId: string;
  refreshKey: number;
  onChange: () => void;
  onRequestBrowse: () => void;
}

const S: Record<string, React.CSSProperties> = {
  root: { padding: '20px 24px', display: 'flex', flexDirection: 'column', gap: 16, height: '100%', minHeight: 0 },
  headerRow: { display: 'flex', alignItems: 'center', gap: 12 },
  title: { fontSize: '1.05rem', fontWeight: 600, color: '#0f172a' },
  sub: { fontSize: '0.82rem', color: '#64748b' },
  primaryBtn: {
    padding: '8px 16px',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none', borderRadius: 8, cursor: 'pointer',
    fontSize: '0.86rem', fontWeight: 600,
    boxShadow: '0 1px 3px rgba(99,102,241,0.3)',
  },
  refreshBtn: {
    background: '#f8fafc', border: '1px solid #e2e8f0', color: '#475569',
    borderRadius: 7, padding: '6px 12px', cursor: 'pointer',
    fontSize: '0.78rem', fontWeight: 500,
  },
  warnings: {
    background: '#fffbeb', border: '1px solid #fde68a', color: '#92400e',
    borderRadius: 8, padding: '10px 14px', fontSize: '0.82rem', lineHeight: 1.5,
  },
  groupHeader: {
    fontSize: '0.74rem', fontWeight: 700, color: '#94a3b8',
    textTransform: 'uppercase', letterSpacing: '0.1em',
    marginTop: 12, marginBottom: 6,
  },
  list: {
    display: 'flex', flexDirection: 'column', gap: 8,
    overflow: 'auto', flex: 1, minHeight: 0,
  },
  card: {
    border: '1px solid #e2e8f0', borderRadius: 10, padding: '12px 14px',
    background: '#ffffff', display: 'flex', alignItems: 'flex-start', gap: 12,
  },
  cardName: { fontWeight: 600, color: '#0f172a', fontSize: '0.92rem' },
  cardDesc: { color: '#64748b', fontSize: '0.82rem', marginTop: 3, lineHeight: 1.45 },
  badge: {
    fontSize: '0.7rem', fontWeight: 600, padding: '2px 8px', borderRadius: 999,
    background: '#eef2ff', color: '#4338ca', border: '1px solid #c7d2fe',
    textTransform: 'uppercase', letterSpacing: '0.04em', flexShrink: 0,
  },
  mcpBadge: {
    fontSize: '0.7rem', fontWeight: 600, padding: '2px 8px', borderRadius: 999,
    background: '#ecfeff', color: '#0e7490', border: '1px solid #a5f3fc',
    textTransform: 'uppercase', letterSpacing: '0.04em', flexShrink: 0,
  },
  disableBtn: {
    background: '#fef2f2', border: '1px solid #fecaca', color: '#b91c1c',
    borderRadius: 6, padding: '4px 10px', cursor: 'pointer',
    fontSize: '0.74rem', fontWeight: 500, marginLeft: 'auto', flexShrink: 0,
  },
  empty: { padding: 32, textAlign: 'center', color: '#94a3b8', fontSize: '0.88rem' },
  err: { color: '#dc2626', fontSize: '0.85rem' },
};

export default function ToolsActivePanel({ agentId, refreshKey, onChange, onRequestBrowse }: Props) {
  const [data, setData] = useState<ActiveToolsResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [actionErr, setActionErr] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true); setErr(null);
    fetchConfiguredActive(agentId)
      .then(d => { if (!cancelled) setData(d); })
      .catch(e => { if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [agentId, refreshKey]);

  const grouped = useMemo(() => {
    const out = new Map<string, ActiveTool[]>();
    for (const t of data?.tools ?? []) {
      const key = t.source.startsWith('mcp:') ? 'mcp' : (t.source || 'unknown');
      if (!out.has(key)) out.set(key, []);
      out.get(key)!.push(t);
    }
    return out;
  }, [data]);

  async function disableTool(t: ActiveTool) {
    setActionErr(null);
    try {
      await disableConfiguredTool(agentId, t);
      onChange();
    } catch (e: unknown) {
      setActionErr(e instanceof Error ? e.message : 'Failed to update agent');
    }
  }

  return (
    <div style={S.root}>
      <div style={S.headerRow}>
        <div style={{ flex: 1 }}>
          <div style={S.title}>Configured tools</div>
          <div style={S.sub}>
            From Agent body (<code>tools</code> / <code>mcpServers</code>). Saves create a new agent version.
          </div>
        </div>
        <button style={S.refreshBtn} onClick={() => onChange()} disabled={loading}>
          {loading ? '…' : '↻ refresh'}
        </button>
        <button style={S.primaryBtn} onClick={onRequestBrowse}>
          + Add / configure
        </button>
      </div>

      {data?.warnings && data.warnings.length > 0 && (
        <div style={S.warnings}>
          {data.warnings.map((w, i) => <div key={i}>⚠ {w}</div>)}
        </div>
      )}
      {actionErr && <div style={S.err}>{actionErr}</div>}
      {err && <div style={S.err}>{err}</div>}

      <div style={S.list}>
        {!err && !loading && (data?.tools ?? []).length === 0 && (
          <div style={S.empty}>No tools configured. Click <b>Add / configure</b> to enable some.</div>
        )}
        {Array.from(grouped.entries()).map(([source, tools]) => (
          <div key={source}>
            <div style={S.groupHeader}>{source === 'built-in' || source === 'unknown' ? 'Built-in' : 'MCP servers'}</div>
            {tools.map(t => (
              <div key={`${source}:${t.name}`} style={S.card}>
                <span style={t.source === 'built-in' ? S.badge : S.mcpBadge}>
                  {t.source === 'built-in' ? 'built-in' : 'mcp'}
                </span>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={S.cardName}>{t.name}</div>
                  {t.description && <div style={S.cardDesc}>{t.description}</div>}
                </div>
                <button
                  style={S.disableBtn}
                  onClick={() => disableTool(t)}
                  title={t.source === 'built-in' ? 'Disable built-in tool' : 'Remove MCP server'}
                >
                  Disable
                </button>
              </div>
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}
