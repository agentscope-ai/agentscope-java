/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import React, { useEffect, useMemo, useState } from 'react';
import { getAgent } from '../api/agents';
import {
  ActiveTool,
  ActiveToolsResponse,
  BuiltinToolInfo,
  ToolPermissionType,
  computeToolPolicies,
  disableConfiguredTool,
  fetchBuiltinCatalog,
  fetchConfiguredActive,
  saveBuiltinToolConfig,
} from '../api/tools';

interface Props {
  agentId: string;
  refreshKey: number;
  onChange: () => void;
  onRequestBrowse: () => void;
  /** When true, show snapshot only — edits belong on the linked Workspace. */
  readOnly?: boolean;
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
  askBadge: {
    fontSize: '0.7rem', fontWeight: 600, padding: '2px 8px', borderRadius: 999,
    background: '#fff7ed', color: '#c2410c', border: '1px solid #fed7aa',
    textTransform: 'uppercase', letterSpacing: '0.04em', flexShrink: 0,
  },
  policySelect: {
    fontSize: '0.74rem',
    border: '1px solid #cbd5e1',
    borderRadius: 6,
    padding: '4px 8px',
    color: '#475569',
    background: '#ffffff',
    flexShrink: 0,
  },
  disableBtn: {
    background: '#fef2f2', border: '1px solid #fecaca', color: '#b91c1c',
    borderRadius: 6, padding: '4px 10px', cursor: 'pointer',
    fontSize: '0.74rem', fontWeight: 500, flexShrink: 0,
  },
  empty: { padding: 32, textAlign: 'center', color: '#94a3b8', fontSize: '0.88rem' },
  err: { color: '#dc2626', fontSize: '0.85rem' },
};

export default function ToolsActivePanel({
  agentId,
  refreshKey,
  onChange,
  onRequestBrowse,
  readOnly = false,
}: Props) {
  const [data, setData] = useState<ActiveToolsResponse | null>(null);
  const [catalog, setCatalog] = useState<BuiltinToolInfo[]>([]);
  const [policies, setPolicies] = useState<Map<string, ToolPermissionType>>(new Map());
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [actionErr, setActionErr] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true); setErr(null);
    Promise.all([
      fetchConfiguredActive(agentId),
      fetchBuiltinCatalog(agentId),
      getAgent(agentId),
    ])
      .then(([active, cat, agent]) => {
        if (cancelled) return;
        setData(active);
        setCatalog(cat);
        setPolicies(computeToolPolicies(agent.tools));
      })
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

  async function changePolicy(toolName: string, type: ToolPermissionType) {
    setActionErr(null);
    try {
      const enabled = new Set(
        (data?.tools ?? []).filter(t => t.source === 'built-in').map(t => t.name),
      );
      const next = new Map(policies);
      next.set(toolName, type);
      // Preserve policies for catalog tools not currently listed as active.
      for (const b of catalog) {
        if (!next.has(b.id)) next.set(b.id, policies.get(b.id) ?? 'always_allow');
      }
      await saveBuiltinToolConfig(agentId, catalog, enabled, next);
      onChange();
    } catch (e: unknown) {
      setActionErr(e instanceof Error ? e.message : 'Failed to update permission');
    }
  }

  return (
    <div style={S.root}>
      <div style={S.headerRow}>
        <div style={{ flex: 1 }}>
          <div style={S.title}>Configured tools</div>
          <div style={S.sub}>
            {readOnly
              ? 'Snapshot materialized from the linked Workspace. Edit tools there to refresh linked agents.'
              : <>From Agent body (<code>tools</code> / <code>mcpServers</code>). Saves create a new agent version.</>}
          </div>
        </div>
        <button style={S.refreshBtn} onClick={() => onChange()} disabled={loading}>
          {loading ? '…' : '↻ refresh'}
        </button>
        {!readOnly && (
          <button style={S.primaryBtn} onClick={onRequestBrowse}>
            + Add / configure
          </button>
        )}
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
            {tools.map(t => {
              const policy = policies.get(t.name) ?? 'always_allow';
              return (
                <div key={`${source}:${t.name}`} style={S.card}>
                  <span style={t.source === 'built-in' ? S.badge : S.mcpBadge}>
                    {t.source === 'built-in' ? 'built-in' : 'mcp'}
                  </span>
                  {t.source === 'built-in' && policy === 'always_ask' && (
                    <span style={S.askBadge}>ask</span>
                  )}
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={S.cardName}>{t.name}</div>
                    {t.description && <div style={S.cardDesc}>{t.description}</div>}
                  </div>
                  {!readOnly && t.source === 'built-in' && (
                    <select
                      style={S.policySelect}
                      value={policy}
                      onChange={e => changePolicy(t.name, e.target.value as ToolPermissionType)}
                      title="Auto runs without asking; Ask pauses for confirmation"
                    >
                      <option value="always_allow">Auto</option>
                      <option value="always_ask">Ask</option>
                    </select>
                  )}
                  {!readOnly && (
                    <button
                      style={S.disableBtn}
                      onClick={() => disableTool(t)}
                      title={t.source === 'built-in' ? 'Disable built-in tool' : 'Remove MCP server'}
                    >
                      Disable
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        ))}
      </div>
    </div>
  );
}
