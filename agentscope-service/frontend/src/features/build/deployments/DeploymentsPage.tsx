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
import {
  Deployment,
  TriggerType,
  archiveDeployment,
  createDeployment,
  deleteDeployment,
  listDeployments,
  runDeployment,
  updateDeployment,
} from '../../../api/deployments';
import { AgentDefinition, listAgents } from '../../../api/agents';
import { Environment, listEnvironments } from '../../../api/environments';

const S: Record<string, React.CSSProperties> = {
  root: { padding: '40px 44px', maxWidth: 1200 },
  header: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 },
  title: { margin: 0, fontSize: '1.75rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.02em' },
  blurb: { margin: '0 0 24px', color: '#64748b', fontSize: '1rem', lineHeight: 1.6, maxWidth: 760 },
  primaryBtn: {
    display: 'inline-flex', alignItems: 'center', gap: 8,
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
  badge: {
    padding: '3px 10px', borderRadius: 999, fontSize: '0.72rem', fontWeight: 600,
    background: '#f1f5f9', color: '#475569', border: '1px solid #e2e8f0',
  },
  triggerCron: { background: '#eef2ff', color: '#4338ca', border: '1px solid #c7d2fe' },
  triggerWebhook: { background: '#ecfeff', color: '#0e7490', border: '1px solid #a5f3fc' },
  triggerManual: { background: '#f1f5f9', color: '#475569', border: '1px solid #e2e8f0' },
  disabled: { background: '#fef3c7', color: '#92400e', border: '1px solid #fde68a' },
  archived: { background: '#fee2e2', color: '#991b1b', border: '1px solid #fecaca' },
  rowBtn: {
    padding: '7px 14px', fontSize: '0.84rem', fontWeight: 500, borderRadius: 8, cursor: 'pointer',
    border: '1px solid #cbd5e1', background: '#ffffff', color: '#475569',
  },
  danger: { color: '#dc2626', borderColor: '#fca5a5' },
  formField: { display: 'block', fontSize: '0.85rem', color: '#475569', marginBottom: 6, fontWeight: 500 },
  input: {
    width: '100%', boxSizing: 'border-box', padding: '10px 12px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 8,
    color: '#0f172a', fontSize: '0.92rem',
  },
  err: { color: '#dc2626', fontSize: '0.95rem', marginBottom: 16 },
  modal: {
    position: 'fixed', inset: 0, background: 'rgba(15,23,42,0.45)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200,
  },
  modalBody: {
    background: '#ffffff', borderRadius: 14, padding: '28px 32px',
    width: '100%', maxWidth: 480, boxShadow: '0 20px 50px rgba(15,23,42,0.2)',
    maxHeight: '90vh', overflowY: 'auto',
  },
  meta: { fontSize: '0.78rem', color: '#94a3b8' },
};

function triggerBadgeStyle(type: TriggerType): React.CSSProperties {
  if (type === 'cron') return { ...S.badge, ...S.triggerCron };
  if (type === 'webhook') return { ...S.badge, ...S.triggerWebhook };
  return { ...S.badge, ...S.triggerManual };
}
function formatTime(ms?: number | null): string {
  if (!ms) return 'never';
  return new Date(ms).toLocaleString();
}

export default function DeploymentsPage() {
  const [items, setItems] = useState<Deployment[]>([]);
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [environments, setEnvironments] = useState<Environment[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);

  const [name, setName] = useState('');
  const [agentId, setAgentId] = useState('');
  const [environmentId, setEnvironmentId] = useState('');
  const [triggerType, setTriggerType] = useState<TriggerType>('manual');
  const [cronExpression, setCronExpression] = useState('0 0 * * * *');

  async function refresh() {
    setLoading(true);
    setErr(null);
    try {
      const [deployments, agentList, envList] = await Promise.all([
        listDeployments(),
        listAgents().catch(() => []),
        listEnvironments().catch(() => []),
      ]);
      setItems(deployments);
      setAgents(agentList);
      setEnvironments(envList);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to load');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void refresh(); }, []);

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim() || !agentId.trim()) return;
    setBusyId('create');
    setErr(null);
    try {
      await createDeployment({
        name: name.trim(),
        agentId: agentId.trim(),
        environmentId: environmentId || undefined,
        triggerType,
        cronExpression: triggerType === 'cron' ? cronExpression.trim() : undefined,
      });
      setCreating(false);
      setName('');
      setAgentId('');
      setEnvironmentId('');
      setTriggerType('manual');
      setCronExpression('0 0 * * * *');
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Create failed');
    } finally {
      setBusyId(null);
    }
  }

  async function handleToggleEnabled(d: Deployment) {
    setBusyId(d.id);
    setErr(null);
    try {
      await updateDeployment(d.id, { enabled: !d.enabled });
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Update failed');
    } finally {
      setBusyId(null);
    }
  }

  async function handleRun(d: Deployment) {
    setBusyId(d.id);
    setErr(null);
    try {
      await runDeployment(d.id);
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Run failed');
    } finally {
      setBusyId(null);
    }
  }

  async function handleArchive(id: string) {
    if (!confirm('Archive this deployment? It will stop firing.')) return;
    setBusyId(id);
    try {
      await archiveDeployment(id);
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Archive failed');
    } finally {
      setBusyId(null);
    }
  }

  async function handleDelete(id: string) {
    if (!confirm('Delete this deployment permanently?')) return;
    setBusyId(id);
    try {
      await deleteDeployment(id);
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Delete failed');
    } finally {
      setBusyId(null);
    }
  }

  function webhookUrl(token: string): string {
    return `${window.location.origin}/api/deployments/webhook/${token}`;
  }

  return (
    <div style={S.root}>
      <div style={S.header}>
        <h1 style={S.title}>Deployments</h1>
        <button type="button" style={S.primaryBtn} onClick={() => setCreating(true)}>＋ New deployment</button>
      </div>
      <p style={S.blurb}>
        Bind an agent, version, and environment to a trigger — cron schedule, webhook, or manual run —
        so it fires without a human driving a chat session.
      </p>
      {err && <div style={S.err}>{err}</div>}
      {loading && <div style={{ color: '#64748b' }}>Loading…</div>}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(340px,1fr))', gap: 18 }}>
        {items.map(d => (
          <div key={d.id} style={S.card}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <span style={{ fontSize: '1.25rem' }}>🚀</span>
              <span style={{ fontWeight: 600, fontSize: '1.05rem', flex: 1 }}>{d.name}</span>
              <span style={triggerBadgeStyle(d.triggerType)}>{d.triggerType}</span>
              {d.archivedAt ? (
                <span style={{ ...S.badge, ...S.archived }}>archived</span>
              ) : !d.enabled ? (
                <span style={{ ...S.badge, ...S.disabled }}>disabled</span>
              ) : null}
            </div>
            <div style={S.meta}>
              agent <code>{d.agentId}</code>{d.agentVersion != null ? ` @v${d.agentVersion}` : ' @latest'}
              {' · env '}<code>{d.environmentId}</code>
            </div>
            {d.triggerType === 'cron' && (
              <div style={S.meta}>cron <code>{d.cronExpression}</code></div>
            )}
            {d.triggerType === 'webhook' && d.webhookToken && (
              <div style={{ ...S.meta, wordBreak: 'break-all' }}>
                webhook <code>{webhookUrl(d.webhookToken)}</code>
              </div>
            )}
            <div style={S.meta}>
              last run: {formatTime(d.lastRunAt)}{d.lastStatus ? ` (${d.lastStatus})` : ''}
            </div>
            {d.lastSessionId && (
              <div style={S.meta}>
                <a href={`/agents/${encodeURIComponent(d.agentId)}/sessions/_managed?managed=${encodeURIComponent(d.lastSessionId)}`}>
                  Replay last session
                </a>
              </div>
            )}
            <div style={{ display: 'flex', gap: 8, marginTop: 6, flexWrap: 'wrap' }}>
              {!d.archivedAt && (
                <button type="button" style={S.rowBtn} disabled={busyId === d.id} onClick={() => handleRun(d)}>
                  ▶ Run now
                </button>
              )}
              {!d.archivedAt && d.triggerType === 'cron' && (
                <button type="button" style={S.rowBtn} disabled={busyId === d.id} onClick={() => handleToggleEnabled(d)}>
                  {d.enabled ? 'Disable' : 'Enable'}
                </button>
              )}
              {!d.archivedAt && (
                <button type="button" style={S.rowBtn} disabled={busyId === d.id} onClick={() => handleArchive(d.id)}>
                  Archive
                </button>
              )}
              <button type="button" style={{ ...S.rowBtn, ...S.danger }} disabled={busyId === d.id} onClick={() => handleDelete(d.id)}>
                Delete
              </button>
            </div>
          </div>
        ))}
        {!loading && items.length === 0 && (
          <div style={{ color: '#94a3b8', fontStyle: 'italic' }}>No deployments yet.</div>
        )}
      </div>

      {creating && (
        <div style={S.modal} onClick={() => setCreating(false)}>
          <div style={S.modalBody} onClick={e => e.stopPropagation()}>
            <h2 style={{ margin: '0 0 18px', fontSize: '1.2rem' }}>New deployment</h2>
            <form onSubmit={handleCreate}>
              <label style={S.formField}>Name</label>
              <input
                style={{ ...S.input, marginBottom: 14 }}
                value={name}
                onChange={e => setName(e.target.value)}
                placeholder="daily-report"
                autoFocus
              />

              <label style={S.formField}>Agent</label>
              {agents.length > 0 ? (
                <select
                  style={{ ...S.input, marginBottom: 14 }}
                  value={agentId}
                  onChange={e => setAgentId(e.target.value)}
                >
                  <option value="">Select an agent…</option>
                  {agents.map(a => (
                    <option key={a.id} value={a.id}>{a.name} ({a.id})</option>
                  ))}
                </select>
              ) : (
                <input
                  style={{ ...S.input, marginBottom: 14 }}
                  value={agentId}
                  onChange={e => setAgentId(e.target.value)}
                  placeholder="agent id"
                />
              )}

              <label style={S.formField}>Environment (optional — defaults to your default environment)</label>
              <select
                style={{ ...S.input, marginBottom: 14 }}
                value={environmentId}
                onChange={e => setEnvironmentId(e.target.value)}
              >
                <option value="">Use default environment</option>
                {environments.map(e => (
                  <option key={e.id} value={e.id}>{e.name}</option>
                ))}
              </select>

              <label style={S.formField}>Trigger</label>
              <select
                style={{ ...S.input, marginBottom: 14 }}
                value={triggerType}
                onChange={e => setTriggerType(e.target.value as TriggerType)}
              >
                <option value="manual">Manual — run on demand</option>
                <option value="cron">Cron — fire on a schedule</option>
                <option value="webhook">Webhook — fire via HTTP POST</option>
              </select>

              {triggerType === 'cron' && (
                <>
                  <label style={S.formField}>Cron expression (Spring 6-field: sec min hour dom mon dow)</label>
                  <input
                    style={{ ...S.input, marginBottom: 20 }}
                    value={cronExpression}
                    onChange={e => setCronExpression(e.target.value)}
                    placeholder="0 0 * * * *"
                  />
                </>
              )}

              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: triggerType === 'cron' ? 0 : 6 }}>
                <button type="button" style={S.rowBtn} onClick={() => setCreating(false)}>Cancel</button>
                <button type="submit" style={S.primaryBtn} disabled={busyId === 'create'}>
                  {busyId === 'create' ? 'Creating…' : 'Create'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
