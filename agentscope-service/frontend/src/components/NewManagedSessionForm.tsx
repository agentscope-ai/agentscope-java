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
import { getAgent } from '../api/agents';
import { Environment, ensureDefaultEnvironment, listEnvironments } from '../api/environments';
import { ManagedFile, listFiles } from '../api/files';
import { MemoryStore, listMemoryStores } from '../api/memoryStores';
import { createManagedSession, ManagedSession } from '../api/managedSessions';
import { Vault, listVaults } from '../api/vaults';

const S: Record<string, React.CSSProperties> = {
  overlay: {
    position: 'fixed', inset: 0, background: 'rgba(15,23,42,0.35)',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    zIndex: 1000, padding: 24,
  },
  panel: {
    background: '#ffffff', borderRadius: 14, border: '1px solid #e2e8f0',
    boxShadow: '0 20px 50px rgba(15,23,42,0.18)',
    width: '100%', maxWidth: 560, maxHeight: '90vh', overflow: 'auto',
    padding: '22px 24px',
  },
  title: { margin: '0 0 6px', fontSize: '1.15rem', fontWeight: 700, color: '#0f172a' },
  hint: { fontSize: '0.8rem', color: '#94a3b8', marginBottom: 16, lineHeight: 1.45 },
  field: { display: 'block', fontSize: '0.82rem', color: '#64748b', marginBottom: 6, fontWeight: 500 },
  input: {
    width: '100%', boxSizing: 'border-box', padding: '9px 12px', marginBottom: 14,
    border: '1px solid #cbd5e1', borderRadius: 8, fontSize: '0.9rem',
  },
  checkGrid: { display: 'grid', gap: 8, marginBottom: 14 },
  checkRow: { display: 'flex', gap: 10, alignItems: 'center', fontSize: '0.9rem', color: '#334155' },
  empty: { fontSize: '0.85rem', color: '#94a3b8', marginBottom: 14 },
  actions: { display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 8 },
  btn: {
    padding: '8px 14px', borderRadius: 8, cursor: 'pointer',
    fontSize: '0.88rem', fontWeight: 500, border: '1px solid #cbd5e1',
    background: '#ffffff', color: '#475569',
  },
  primary: {
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none',
    boxShadow: '0 2px 6px rgba(99,102,241,0.25)',
  },
  linkBtn: {
    background: 'transparent', border: 'none', color: '#6366f1',
    cursor: 'pointer', fontSize: '0.8rem', fontWeight: 500, padding: 0, marginBottom: 14,
  },
  err: { color: '#dc2626', fontSize: '0.88rem', marginBottom: 10 },
  details: { marginBottom: 10 },
  summary: { cursor: 'pointer', fontSize: '0.85rem', color: '#64748b', fontWeight: 500, marginBottom: 8 },
};

export interface NewManagedSessionFormProps {
  agentId: string;
  /** When true, render as a modal overlay; otherwise as an inline panel. */
  modal?: boolean;
  initialFileIds?: string[];
  onCreated: (session: ManagedSession) => void;
  onCancel?: () => void;
}

/**
 * Explicit Managed session create form with env / vault / memory mount pickers.
 * Prefills Agent session defaults; submits explicit mount IDs (no omit-to-merge).
 */
export default function NewManagedSessionForm({
  agentId,
  modal = true,
  initialFileIds = [],
  onCreated,
  onCancel,
}: NewManagedSessionFormProps) {
  const [environments, setEnvironments] = useState<Environment[]>([]);
  const [vaults, setVaults] = useState<Vault[]>([]);
  const [memoryStores, setMemoryStores] = useState<MemoryStore[]>([]);
  const [files, setFiles] = useState<ManagedFile[]>([]);
  const [environmentId, setEnvironmentId] = useState('');
  const [vaultIds, setVaultIds] = useState<string[]>([]);
  const [memoryStoreIds, setMemoryStoreIds] = useState<string[]>([]);
  const [selectedFileIds, setSelectedFileIds] = useState<string[]>(initialFileIds);
  const [system, setSystem] = useState('');
  const [model, setModel] = useState('');
  const [maxIters, setMaxIters] = useState('');
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [defaultEnvId, setDefaultEnvId] = useState('');
  const [defaultVaultIds, setDefaultVaultIds] = useState<string[]>([]);
  const [defaultMemoryStoreIds, setDefaultMemoryStoreIds] = useState<string[]>([]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      setErr(null);
      try {
        const [agent, envs, vs, ms, fs] = await Promise.all([
          getAgent(agentId),
          listEnvironments(),
          listVaults(),
          listMemoryStores(),
          listFiles().catch(() => [] as ManagedFile[]),
        ]);
        if (cancelled) return;
        setEnvironments(envs.filter(e => !e.archivedAt));
        setVaults(vs);
        setMemoryStores(ms);
        setFiles(fs);
        const envDefault = agent.defaultEnvironmentId || '';
        setDefaultEnvId(envDefault);
        setDefaultVaultIds(agent.defaultVaultIds ?? []);
        setDefaultMemoryStoreIds(agent.defaultMemoryStoreIds ?? []);
        setEnvironmentId(envDefault);
        setVaultIds(agent.defaultVaultIds ?? []);
        setMemoryStoreIds(agent.defaultMemoryStoreIds ?? []);
      } catch (e: unknown) {
        if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed to load form');
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [agentId]);

  function applyAgentDefaults() {
    setEnvironmentId(defaultEnvId);
    setVaultIds([...defaultVaultIds]);
    setMemoryStoreIds([...defaultMemoryStoreIds]);
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setErr(null);
    try {
      let envId = environmentId.trim();
      if (!envId) {
        envId = (await ensureDefaultEnvironment()).id;
        setEnvironmentId(envId);
      }
      if (maxIters.trim() && Number.isNaN(Number(maxIters))) {
        throw new Error('maxIters must be a number');
      }
      const agentOverrides: Record<string, unknown> = {};
      if (system.trim()) agentOverrides.system = system.trim();
      if (model.trim()) agentOverrides.model = model.trim();
      if (maxIters.trim()) agentOverrides.maxIters = Number(maxIters);
      const session = await createManagedSession({
        agent: agentId,
        environmentId: envId,
        vaultIds,
        memoryStoreIds,
        resources: selectedFileIds.map(fileId => ({ type: 'file', fileId })),
        ...(Object.keys(agentOverrides).length > 0 ? { agentOverrides } : {}),
      });
      onCreated(session);
    } catch (ex: unknown) {
      setErr(ex instanceof Error ? ex.message : 'Failed to create session');
    } finally {
      setSubmitting(false);
    }
  }

  const body = (
    <form onSubmit={handleSubmit} style={modal ? S.panel : undefined} onClick={ev => ev.stopPropagation()}>
      <h3 style={S.title}>New session</h3>
      <div style={S.hint}>
        Create a session definition only — no turn starts until the first Chat message.
        Choose environment, vaults, and memory stores. Agent session defaults prefill the form.
      </div>
      {err && <div style={S.err}>{err}</div>}
      {loading ? (
        <div style={S.empty}>Loading…</div>
      ) : (
        <>
          <button type="button" style={S.linkBtn} onClick={applyAgentDefaults}>
            Reset to Agent defaults
          </button>
          <label style={S.field}>Environment</label>
          <select
            style={S.input}
            value={environmentId}
            onChange={e => setEnvironmentId(e.target.value)}
            required
          >
            <option value="">Select environment…</option>
            {environments.map(env => (
              <option key={env.id} value={env.id}>{env.name} ({env.type})</option>
            ))}
          </select>

          <label style={S.field}>Vaults</label>
          {vaults.length === 0 ? (
            <div style={S.empty}>No vaults. Create one under Build → Vaults.</div>
          ) : (
            <div style={S.checkGrid}>
              {vaults.map(v => {
                const on = vaultIds.includes(v.id);
                return (
                  <label key={v.id} style={S.checkRow}>
                    <input
                      type="checkbox"
                      checked={on}
                      onChange={() => {
                        setVaultIds(prev => on ? prev.filter(id => id !== v.id) : [...prev, v.id]);
                      }}
                    />
                    <span>{v.displayName}</span>
                  </label>
                );
              })}
            </div>
          )}

          <label style={S.field}>Memory stores</label>
          {memoryStores.length === 0 ? (
            <div style={S.empty}>No memory stores. Create one under Build → Memory.</div>
          ) : (
            <div style={S.checkGrid}>
              {memoryStores.map(m => {
                const on = memoryStoreIds.includes(m.id);
                return (
                  <label key={m.id} style={S.checkRow}>
                    <input
                      type="checkbox"
                      checked={on}
                      onChange={() => {
                        setMemoryStoreIds(prev => on ? prev.filter(id => id !== m.id) : [...prev, m.id]);
                      }}
                    />
                    <span>{m.name}</span>
                  </label>
                );
              })}
            </div>
          )}

          {files.length > 0 && (
            <>
              <label style={S.field}>File resources (optional)</label>
              <div style={S.checkGrid}>
                {files.map(f => {
                  const on = selectedFileIds.includes(f.id);
                  return (
                    <label key={f.id} style={S.checkRow}>
                      <input
                        type="checkbox"
                        checked={on}
                        onChange={() => {
                          setSelectedFileIds(prev =>
                            on ? prev.filter(id => id !== f.id) : [...prev, f.id]);
                        }}
                      />
                      <span>{f.filename}</span>
                    </label>
                  );
                })}
              </div>
            </>
          )}

          <details style={S.details}>
            <summary style={S.summary}>Optional overrides</summary>
            <label style={S.field}>System prompt</label>
            <textarea
              style={{ ...S.input, minHeight: 72, fontFamily: 'ui-monospace, Menlo, monospace' }}
              value={system}
              onChange={e => setSystem(e.target.value)}
              placeholder="Leave empty to use agent default"
            />
            <label style={S.field}>Model</label>
            <input style={S.input} value={model} onChange={e => setModel(e.target.value)} placeholder="e.g. qwen-plus" />
            <label style={S.field}>Max iters</label>
            <input style={S.input} value={maxIters} onChange={e => setMaxIters(e.target.value)} placeholder="e.g. 10" />
          </details>
        </>
      )}
      <div style={S.actions}>
        {onCancel && (
          <button type="button" style={S.btn} onClick={onCancel} disabled={submitting}>Cancel</button>
        )}
        <button type="submit" style={{ ...S.btn, ...S.primary }} disabled={loading || submitting}>
          {submitting ? 'Creating…' : 'Create session'}
        </button>
      </div>
    </form>
  );

  if (!modal) return body;
  return (
    <div style={S.overlay} onClick={onCancel}>
      {body}
    </div>
  );
}
