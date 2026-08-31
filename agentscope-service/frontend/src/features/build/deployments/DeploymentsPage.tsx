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
  pauseDeployment,
  runDeployment,
  unpauseDeployment,
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
  nameRow: { display: 'flex', alignItems: 'center', gap: 10 },
  name: { fontWeight: 600, fontSize: '1.05rem', flex: 1, color: '#0f172a' },
  badge: {
    padding: '3px 10px', borderRadius: 999, fontSize: '0.72rem', fontWeight: 600,
    background: '#f1f5f9', color: '#475569', border: '1px solid #e2e8f0',
  },
  ok: { background: '#dcfce7', color: '#166534', border: '1px solid #bbf7d0' },
  paused: { background: '#fef3c7', color: '#92400e', border: '1px solid #fde68a' },
  archived: { background: '#e2e8f0', color: '#475569', border: '1px solid #cbd5e1' },
  meta: { fontSize: '0.78rem', color: '#94a3b8', fontFamily: 'monospace' },
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
    width: '100%', maxWidth: 440, boxShadow: '0 20px 50px rgba(15,23,42,0.2)',
  },
};

const TRIGGERS: TriggerType[] = ['manual', 'cron', 'webhook'];

const CRON_RANGES: Array<[number, number]> = [[0, 59], [0, 23], [1, 31], [1, 12], [0, 7]];

function isValidCronField(field: string, min: number, max: number): boolean {
  return field.split(',').every(part => {
    const [base, step] = part.split('/');
    if (part.split('/').length > 2 || step === '' || (step !== undefined && (!/^\d+$/.test(step) || Number(step) < 1))) {
      return false;
    }
    if (base === '*') return true;
    const range = base.split('-');
    if (range.length > 2 || !range.every(value => /^\d+$/.test(value))) return false;
    const start = Number(range[0]);
    const end = Number(range[range.length - 1]);
    return start >= min && end <= max && start <= end;
  });
}

function isValidCronExpression(expression: string): boolean {
  const fields = expression.trim().split(/\s+/);
  return fields.length === 5 && fields.every((field, index) => isValidCronField(field, ...CRON_RANGES[index]));
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
  const [cronExpression, setCronExpression] = useState('');

  async function refresh() {
    setLoading(true);
    setErr(null);
    try {
      const [deps, ags, envs] = await Promise.all([
        listDeployments(),
        listAgents().catch(() => []),
        listEnvironments(),
      ]);
      setItems(deps);
      setAgents(ags);
      setEnvironments(envs.filter(environment => !environment.archivedAt));
    } catch (e: unknown) {
      setEnvironments([]);
      setErr(e instanceof Error ? e.message : 'Failed to load');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void refresh(); }, []);

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    const cron = cronExpression.trim();
    if (!name.trim() || !agentId || !environmentId || (triggerType === 'cron' && !isValidCronExpression(cron))) return;
    setBusyId('create');
    setErr(null);
    try {
      await createDeployment({
        name: name.trim(),
        agentId,
        environmentId,
        triggerType,
        ...(triggerType === 'cron'
          ? { cronExpression: cron }
          : {}),
      });
      setCreating(false);
      setName('');
      setAgentId('');
      setEnvironmentId('');
      setTriggerType('manual');
      setCronExpression('');
      await refresh();
    } catch (ex: unknown) {
      setErr(ex instanceof Error ? ex.message : 'Create failed');
    } finally {
      setBusyId(null);
    }
  }

  async function action(id: string, label: string, fn: () => Promise<unknown>) {
    setBusyId(id);
    setErr(null);
    try {
      await fn();
      await refresh();
    } catch (ex: unknown) {
      setErr(ex instanceof Error ? ex.message : `${label} failed`);
    } finally {
      setBusyId(null);
    }
  }

  async function handleDelete(id: string) {
    if (!confirm('Delete this deployment permanently?')) return;
    await action(id, 'Delete', () => deleteDeployment(id));
  }

  async function handleArchive(id: string) {
    if (!confirm('Archive this deployment?')) return;
    await action(id, 'Archive', () => archiveDeployment(id));
  }

  function fmtTime(ts?: number | null): string {
    return ts ? new Date(ts).toLocaleString() : '—';
  }

  function closeCreateForm() {
    setCreating(false);
    setName('');
    setAgentId('');
    setEnvironmentId('');
    setTriggerType('manual');
    setCronExpression('');
  }

  return (
    <div style={S.root}>
      <div style={S.header}>
        <h1 style={S.title}>Deployments</h1>
        <button type="button" style={S.primaryBtn} onClick={() => setCreating(true)} disabled={loading}>
          ＋ New deployment
        </button>
      </div>
      <p style={S.blurb}>
        Run agents on a schedule or trigger them from webhooks. Each deployment pins an agent
        version to an execution environment and records the outcome of its last run.
      </p>
      {err && <div style={S.err}>{err}</div>}
      {loading && <div style={{ color: '#64748b' }}>Loading…</div>}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(320px,1fr))', gap: 18 }}>
        {items.map(dep => (
          <div key={dep.id} style={S.card}>
            <div style={S.nameRow}>
              <span style={S.name}>{dep.name}</span>
              {dep.archivedAt ? (
                <span style={{ ...S.badge, ...S.archived }}>archived</span>
              ) : dep.enabled ? (
                <span style={{ ...S.badge, ...S.ok }}>{dep.triggerType}</span>
              ) : (
                <span style={{ ...S.badge, ...S.paused }}>paused</span>
              )}
            </div>
            <div style={S.meta}>{dep.id}</div>
            <div style={{ fontSize: '0.82rem', color: '#64748b' }}>
              agent <code>{dep.agentId}</code>
              {dep.agentVersion != null ? ` · v${dep.agentVersion}` : ''}
              {dep.triggerType === 'cron' && dep.cronExpression ? ` · cron ${dep.cronExpression}` : ''}
            </div>
            <div style={{ fontSize: '0.8rem', color: '#94a3b8' }}>
              last run {fmtTime(dep.lastRunAt)}
              {dep.lastStatus ? ` · ${dep.lastStatus}` : ''}
              {dep.lastSessionId ? ` · session ${dep.lastSessionId}` : ''}
            </div>
            {!dep.archivedAt && (
              <div style={{ display: 'flex', gap: 8, marginTop: 6, flexWrap: 'wrap' }}>
                <button
                  type="button"
                  style={S.rowBtn}
                  disabled={busyId === dep.id}
                  onClick={() => action(dep.id, 'Run', () => runDeployment(dep.id))}
                >
                  Run
                </button>
                {dep.enabled ? (
                  <button
                    type="button"
                    style={S.rowBtn}
                    disabled={busyId === dep.id}
                    onClick={() => action(dep.id, 'Pause', () => pauseDeployment(dep.id))}
                  >
                    Pause
                  </button>
                ) : (
                  <button
                    type="button"
                    style={S.rowBtn}
                    disabled={busyId === dep.id}
                    onClick={() => action(dep.id, 'Unpause', () => unpauseDeployment(dep.id))}
                  >
                    Unpause
                  </button>
                )}
                <button
                  type="button"
                  style={S.rowBtn}
                  disabled={busyId === dep.id}
                  onClick={() => handleArchive(dep.id)}
                >
                  Archive
                </button>
                <button
                  type="button"
                  style={{ ...S.rowBtn, ...S.danger }}
                  disabled={busyId === dep.id}
                  onClick={() => handleDelete(dep.id)}
                >
                  Delete
                </button>
              </div>
            )}
          </div>
        ))}
        {!loading && items.length === 0 && (
          <div style={{ color: '#94a3b8', fontStyle: 'italic' }}>No deployments yet.</div>
        )}
      </div>

      {creating && (
        <div style={S.modal} onClick={closeCreateForm}>
          <div style={S.modalBody} onClick={e => e.stopPropagation()}>
            <h2 style={{ margin: '0 0 18px', fontSize: '1.2rem' }}>New deployment</h2>
            <form onSubmit={handleCreate}>
              <label style={S.formField}>Name</label>
              <input
                style={{ ...S.input, marginBottom: 14 }}
                value={name}
                onChange={e => setName(e.target.value)}
                placeholder="nightly-report"
                autoFocus
              />
              <label style={S.formField}>Agent</label>
              <select
                style={{ ...S.input, marginBottom: 14 }}
                value={agentId}
                onChange={e => setAgentId(e.target.value)}
              >
                <option value="">Select an agent…</option>
                {agents.map(a => (
                  <option key={a.id} value={a.id}>{a.name}</option>
                ))}
              </select>
              <label style={S.formField}>Environment</label>
              {environments.length === 0 ? (
                <div style={{ ...S.err, marginBottom: 14 }}>
                  No active environments available. Create or activate an environment first.
                </div>
              ) : (
                <select
                  style={{ ...S.input, marginBottom: 14 }}
                  value={environmentId}
                  onChange={e => setEnvironmentId(e.target.value)}
                  required
                >
                  <option value="">Select an environment…</option>
                  {environments.map(environment => (
                    <option key={environment.id} value={environment.id}>
                      {environment.name} ({environment.type})
                    </option>
                  ))}
                </select>
              )}
              <label style={S.formField}>Trigger</label>
              <select
                style={{ ...S.input, marginBottom: 14 }}
                value={triggerType}
                onChange={e => setTriggerType(e.target.value as TriggerType)}
              >
                {TRIGGERS.map(t => <option key={t} value={t}>{t}</option>)}
              </select>
              {triggerType === 'cron' && (
                <>
                  <label style={S.formField}>Cron expression</label>
                  <input
                    style={{ ...S.input, marginBottom: 14 }}
                    value={cronExpression}
                    onChange={e => setCronExpression(e.target.value)}
                    placeholder="0 9 * * 1-5"
                    required
                    aria-invalid={cronExpression.trim() !== '' && !isValidCronExpression(cronExpression)}
                  />
                  {cronExpression.trim() !== '' && !isValidCronExpression(cronExpression) && (
                    <div style={{ ...S.err, marginTop: -8, marginBottom: 14 }}>
                      Use a valid 5-field cron expression, for example: 0 9 * * 1-5.
                    </div>
                  )}
                </>
              )}
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
                <button type="button" style={S.rowBtn} onClick={closeCreateForm}>Cancel</button>
                <button
                  type="submit"
                  style={S.primaryBtn}
                  disabled={busyId === 'create' || !name.trim() || !agentId || !environmentId || environments.length === 0 || (triggerType === 'cron' && !isValidCronExpression(cronExpression))}
                >
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
