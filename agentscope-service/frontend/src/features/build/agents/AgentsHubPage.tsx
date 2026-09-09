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

import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { AgentDefinition, archiveAgent, deleteAgent, listAgents } from '../../../api/agents';

const S: Record<string, React.CSSProperties> = {
  root: { padding: '40px 44px', maxWidth: 1200 },
  header: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 },
  title: { margin: 0, fontSize: '1.75rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.02em' },
  blurb: { margin: '0 0 24px', color: '#64748b', fontSize: '1rem', lineHeight: 1.6, maxWidth: 760 },
  primaryBtn: {
    display: 'inline-flex', alignItems: 'center', gap: 8, textDecoration: 'none',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none',
    borderRadius: 10, padding: '11px 20px', fontSize: '0.95rem', fontWeight: 600,
    cursor: 'pointer',
    boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  card: {
    background: '#ffffff', border: '1px solid #e2e8f0',
    borderRadius: 14, padding: '20px 22px',
    display: 'flex', flexDirection: 'column', gap: 10,
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
  nameRow: { display: 'flex', alignItems: 'center', gap: 10 },
  name: { fontWeight: 600, fontSize: '1.05rem', flex: 1, color: '#0f172a', textDecoration: 'none' },
  desc: { margin: 0, color: '#475569', fontSize: '0.88rem', lineHeight: 1.55 },
  badge: {
    padding: '3px 10px', borderRadius: 999, fontSize: '0.72rem', fontWeight: 600,
    background: '#f1f5f9', color: '#475569', border: '1px solid #e2e8f0',
  },
  archived: { background: '#fef3c7', color: '#92400e', border: '1px solid #fde68a' },
  id: { fontSize: '0.78rem', color: '#94a3b8', fontFamily: 'monospace' },
  rowBtn: {
    padding: '7px 14px', fontSize: '0.84rem', fontWeight: 500, borderRadius: 8, cursor: 'pointer',
    border: '1px solid #cbd5e1', background: '#ffffff', color: '#475569', textDecoration: 'none',
    display: 'inline-block',
  },
  danger: { color: '#dc2626', borderColor: '#fca5a5' },
  err: { color: '#dc2626', fontSize: '0.95rem', marginBottom: 16 },
};

export default function AgentsHubPage() {
  const [items, setItems] = useState<AgentDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  async function refresh() {
    setLoading(true);
    setErr(null);
    try {
      setItems(await listAgents());
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to load');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void refresh(); }, []);

  async function handleArchive(id: string) {
    if (!confirm('Archive this agent? Its versions are kept and it can no longer be run.')) return;
    setBusyId(id);
    setErr(null);
    try {
      await archiveAgent(id);
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Archive failed');
    } finally {
      setBusyId(null);
    }
  }

  async function handleDelete(id: string) {
    if (!confirm('Delete this agent permanently?')) return;
    setBusyId(id);
    setErr(null);
    try {
      await deleteAgent(id);
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Delete failed');
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div style={S.root}>
      <div style={S.header}>
        <h1 style={S.title}>Agents</h1>
        <Link to="/agents/new" style={S.primaryBtn}>＋ New agent</Link>
      </div>
      <p style={S.blurb}>
        Your managed agents. Open an agent to configure its tools, skills, subagents and
        workspace, or start a session to chat with it.
      </p>
      {err && <div style={S.err}>{err}</div>}
      {loading && <div style={{ color: '#64748b' }}>Loading…</div>}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(320px,1fr))', gap: 18 }}>
        {items.map(agent => (
          <div key={agent.id} style={S.card}>
            <div style={S.nameRow}>
              <Link to={`/agents/${encodeURIComponent(agent.id)}`} style={S.name}>
                {agent.name}
              </Link>
              {agent.archivedAt && <span style={{ ...S.badge, ...S.archived }}>archived</span>}
              {!agent.archivedAt && <span style={S.badge}>{agent.scope}</span>}
            </div>
            <p style={S.desc}>{agent.description || 'No description.'}</p>
            <div style={S.id}>
              {agent.id}
              {agent.model ? ` · ${agent.model}` : ''}
              {agent.version != null ? ` · v${agent.version}` : ''}
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 6, flexWrap: 'wrap' }}>
              <Link to={`/agents/${encodeURIComponent(agent.id)}`} style={S.rowBtn}>Open</Link>
              {!agent.archivedAt && (
                <button
                  type="button"
                  style={S.rowBtn}
                  disabled={busyId === agent.id}
                  onClick={() => handleArchive(agent.id)}
                >
                  Archive
                </button>
              )}
              <button
                type="button"
                style={{ ...S.rowBtn, ...S.danger }}
                disabled={busyId === agent.id}
                onClick={() => handleDelete(agent.id)}
              >
                Delete
              </button>
            </div>
          </div>
        ))}
        {!loading && items.length === 0 && (
          <div style={{ color: '#94a3b8', fontStyle: 'italic' }}>
            No agents yet — create your first one.
          </div>
        )}
      </div>
    </div>
  );
}
