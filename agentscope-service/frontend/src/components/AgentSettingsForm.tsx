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
import { AgentDefinition, AgentVersionEntry, archiveAgent, getAgent, listVersions, updateAgent, deleteAgent } from '../api/agents';
import { useNavigate, Link } from 'react-router-dom';
import ShareAgentDialog from './ShareAgentDialog';
import { getWorkspace, listWorkspaces, WorkspaceSummary } from '../api/workspaces';
import { Environment, listEnvironments } from '../api/environments';
import { Vault, listVaults } from '../api/vaults';
import { MemoryStore, listMemoryStores } from '../api/memoryStores';
import { getUsername } from '../lib/auth';

const S: Record<string, React.CSSProperties> = {
  page: { padding: '32px 36px', maxWidth: 820 },
  card: {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 14,
    padding: '24px 28px', marginBottom: 20,
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
  cardLabel: {
    fontSize: '0.78rem', color: '#94a3b8', fontWeight: 700,
    textTransform: 'uppercase', letterSpacing: '0.1em',
    marginBottom: 18, display: 'block',
  },
  fieldLabel: {
    display: 'block', fontSize: '0.88rem', fontWeight: 500,
    color: '#475569', marginBottom: 8,
  },
  input: {
    width: '100%', boxSizing: 'border-box', padding: '11px 14px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 9,
    color: '#0f172a', fontSize: '0.95rem',
  },
  textarea: {
    width: '100%', boxSizing: 'border-box', padding: '12px 14px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 9,
    color: '#0f172a', fontSize: '0.95rem', lineHeight: 1.55,
    minHeight: 150, resize: 'vertical',
  },
  row: { marginBottom: 18 },
  saveBtn: {
    padding: '11px 24px',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff',
    border: 'none', borderRadius: 9, cursor: 'pointer',
    fontSize: '0.95rem', fontWeight: 600,
    boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  dangerBtn: {
    padding: '11px 20px', background: '#ffffff', color: '#dc2626',
    border: '1px solid #fca5a5', borderRadius: 9, cursor: 'pointer',
    fontSize: '0.92rem', fontWeight: 500,
  },
  shareBtn: {
    padding: '11px 18px', background: '#ffffff', color: '#4338ca',
    border: '1px solid #c7d2fe', borderRadius: 9, cursor: 'pointer',
    fontSize: '0.92rem', fontWeight: 600,
  },
  banner: {
    padding: '14px 18px', borderRadius: 10, marginBottom: 20,
    background: '#eef2ff', color: '#3730a3', fontSize: '0.9rem',
    border: '1px solid #c7d2fe',
  },
  success: { color: '#059669', fontSize: '0.9rem', marginTop: 10 },
  error: { color: '#dc2626', fontSize: '0.9rem', marginTop: 10 },
  meta: {
    fontSize: '0.85rem', color: '#64748b', fontFamily: 'monospace',
  },
};

export default function AgentSettingsForm({
  agent,
  onSaved,
}: {
  agent: AgentDefinition;
  onSaved?: () => void | Promise<unknown>;
}) {
  const navigate = useNavigate();
  const isGlobal = agent.scope === 'global';
  const tier = agent.tierForCurrentUser;
  // The backend never populates tierForCurrentUser, so treat the owner as EDIT-capable.
  const canEdit = !isGlobal && (tier === 'EDIT' || (tier == null && agent.ownerId === getUsername()));
  const canShare = canEdit; // sharing requires EDIT
  const readOnly = !canEdit;
  const [shareOpen, setShareOpen] = useState(false);

  const [name, setName] = useState(agent.name);
  const [description, setDescription] = useState(agent.description ?? '');
  const [model, setModel] = useState(agent.model ?? '');
  const [system, setSystem] = useState(agent.system ?? '');
  const [maxIters, setMaxIters] = useState<string>(String(agent.maxIters ?? 12));
  const [workspaceId, setWorkspaceId] = useState(agent.workspaceId ?? '');
  const [workspaces, setWorkspaces] = useState<WorkspaceSummary[]>([]);
  const [linkedSummary, setLinkedSummary] = useState<WorkspaceSummary | null>(null);
  const [defaultEnvironmentId, setDefaultEnvironmentId] = useState(agent.defaultEnvironmentId ?? '');
  const [defaultVaultIds, setDefaultVaultIds] = useState<string[]>(agent.defaultVaultIds ?? []);
  const [defaultMemoryStoreIds, setDefaultMemoryStoreIds] = useState<string[]>(agent.defaultMemoryStoreIds ?? []);
  const [environments, setEnvironments] = useState<Environment[]>([]);
  const [vaults, setVaults] = useState<Vault[]>([]);
  const [memoryStores, setMemoryStores] = useState<MemoryStore[]>([]);
  const [version, setVersion] = useState<number | undefined>(agent.version);
  const [saving, setSaving] = useState(false);
  const [ok, setOk] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [versions, setVersions] = useState<AgentVersionEntry[]>([]);
  const [versionsErr, setVersionsErr] = useState<string | null>(null);
  const [archiving, setArchiving] = useState(false);

  useEffect(() => {
    setName(agent.name);
    setDescription(agent.description ?? '');
    setModel(agent.model ?? '');
    setSystem(agent.system ?? '');
    setMaxIters(String(agent.maxIters ?? 12));
    setWorkspaceId(agent.workspaceId ?? '');
    setDefaultEnvironmentId(agent.defaultEnvironmentId ?? '');
    setDefaultVaultIds(agent.defaultVaultIds ?? []);
    setDefaultMemoryStoreIds(agent.defaultMemoryStoreIds ?? []);
    setVersion(agent.version);
  }, [
    agent.id, agent.version, agent.system, agent.name, agent.description, agent.model, agent.maxIters,
    agent.workspaceId, agent.defaultEnvironmentId,
    JSON.stringify(agent.defaultVaultIds ?? []),
    JSON.stringify(agent.defaultMemoryStoreIds ?? []),
  ]);

  useEffect(() => {
    listWorkspaces().then(setWorkspaces).catch(() => undefined);
    listEnvironments().then(setEnvironments).catch(() => undefined);
    listVaults().then(setVaults).catch(() => undefined);
    listMemoryStores().then(setMemoryStores).catch(() => undefined);
  }, []);

  useEffect(() => {
    if (!workspaceId) {
      setLinkedSummary(null);
      return;
    }
    let cancelled = false;
    getWorkspace(workspaceId)
      .then(w => { if (!cancelled) setLinkedSummary(w); })
      .catch(() => { if (!cancelled) setLinkedSummary(null); });
    return () => { cancelled = true; };
  }, [workspaceId]);

  useEffect(() => {
    if (agent.scope === 'global' || !agent.ownerId) return;
    let cancelled = false;
    listVersions(agent.id)
      .then(v => { if (!cancelled) { setVersions(v); setVersionsErr(null); } })
      .catch(e => { if (!cancelled) setVersionsErr(e instanceof Error ? e.message : 'Failed to load versions'); });
    return () => { cancelled = true; };
  }, [agent.id, agent.scope, agent.ownerId, agent.version]);

  async function handleSave() {
    setOk(false);
    setErr(null);
    setSaving(true);
    try {
      if (version == null) throw new Error('Missing agent version for optimistic lock');
      const iters = Number.parseInt(maxIters, 10);
      const updated = await updateAgent(agent.id, {
        name: name.trim() || agent.id,
        description: description.trim() || undefined,
        model: model.trim() || undefined,
        system: system || undefined,
        maxIters: Number.isFinite(iters) && iters > 0 ? iters : undefined,
        // Empty string unlinks; omitted would keep previous on some clients — always send.
        workspaceId: workspaceId || '',
        defaultEnvironmentId: defaultEnvironmentId || '',
        defaultVaultIds,
        defaultMemoryStoreIds,
        version,
      });
      setVersion(updated.version);
      setWorkspaceId(updated.workspaceId ?? '');
      setDefaultEnvironmentId(updated.defaultEnvironmentId ?? '');
      setDefaultVaultIds(updated.defaultVaultIds ?? []);
      setDefaultMemoryStoreIds(updated.defaultMemoryStoreIds ?? []);
      setOk(true);
      await onSaved?.();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete() {
    if (!confirm(`Delete agent "${agent.name}"? This removes its workspace and sessions.`)) return;
    try {
      await deleteAgent(agent.id);
      navigate('/agents', { replace: true });
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Delete failed');
    }
  }

  async function handleArchive() {
    if (!confirm(`Archive agent "${agent.name}"? New managed sessions cannot be created.`)) return;
    setArchiving(true);
    setErr(null);
    try {
      await archiveAgent(agent.id);
      await getAgent(agent.id);
      setOk(true);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Archive failed');
    } finally {
      setArchiving(false);
    }
  }

  return (
    <div style={S.page}>
      {isGlobal && (
        <div style={S.banner}>
          Global agents are read-only from the UI. Edit <code>agentscope.json</code> to change them.
        </div>
      )}
      {!isGlobal && !canEdit && (
        <div style={S.banner}>
          You have <strong>{tier ?? 'no'}</strong> access to this agent. Only EDIT-tier collaborators can change settings.
        </div>
      )}

      <div style={S.card}>
        <span style={S.cardLabel}>Identity</span>

        <div style={S.row}>
          <label style={S.fieldLabel}>Agent ID</label>
          <div style={S.meta}>{agent.id}</div>
        </div>

        {agent.version != null && (
          <div style={S.row}>
            <label style={S.fieldLabel}>Current version</label>
            <div style={S.meta}>v{version ?? agent.version}</div>
          </div>
        )}

        {agent.archivedAt != null && (
          <div style={{
            padding: '10px 14px', borderRadius: 8, marginBottom: 14,
            background: '#fef3c7', color: '#92400e', border: '1px solid #fde68a', fontSize: '0.88rem',
          }}>
            Archived {new Date(agent.archivedAt).toLocaleString()}
          </div>
        )}

        <div style={S.row}>
          <label style={S.fieldLabel}>Name</label>
          <input
            style={S.input}
            value={name}
            onChange={e => setName(e.target.value)}
            disabled={readOnly}
          />
        </div>

        <div style={S.row}>
          <label style={S.fieldLabel}>Description</label>
          <input
            style={S.input}
            value={description}
            onChange={e => setDescription(e.target.value)}
            disabled={readOnly}
            placeholder="Short summary shown on cards and tabs"
          />
        </div>

        <div style={S.row}>
          <label style={S.fieldLabel}>Model</label>
          <input
            style={S.input}
            value={model}
            onChange={e => setModel(e.target.value)}
            disabled={readOnly}
            placeholder="e.g. dashscope:qwen-max, deepseek:deepseek-chat, openai:gpt-4o"
          />
          <div style={{ fontSize: '0.8rem', color: '#94a3b8', marginTop: 6, lineHeight: 1.5 }}>
            Provider-qualified model id resolved via ModelRegistry; empty falls back to the data-plane default model.
          </div>
        </div>
      </div>

      <div style={S.card}>
        <span style={S.cardLabel}>Workspace</span>
        <div style={S.row}>
          <label style={S.fieldLabel}>Linked workspace</label>
          <select
            style={S.input}
            value={workspaceId}
            onChange={e => setWorkspaceId(e.target.value)}
            disabled={readOnly}
          >
            <option value="">None (agent-private skills/tools/files)</option>
            {workspaces.map(w => (
              <option key={w.id} value={w.id}>{w.name}</option>
            ))}
          </select>
          <div style={{ fontSize: '0.8rem', color: '#94a3b8', marginTop: 6, lineHeight: 1.5 }}>
            Linking rematerializes tools/skills/AGENTS.md into this agent version. Edit shared content under Build → Workspaces.
          </div>
        </div>
        {linkedSummary && (
          <div style={{
            padding: '12px 14px', borderRadius: 10, background: '#f8fafc',
            border: '1px solid #e2e8f0', fontSize: '0.88rem', color: '#475569',
          }}>
            <div style={{ fontWeight: 650, color: '#0f172a', marginBottom: 6 }}>
              {linkedSummary.name}{' '}
              <Link to={`/workspaces/${encodeURIComponent(linkedSummary.id)}`} style={{ color: '#4338ca' }}>
                Open →
              </Link>
            </div>
            <div>
              v{linkedSummary.version}
              {linkedSummary.agentsMdExists ? ' · AGENTS.md' : ''}
              {' · '}skills {linkedSummary.skillCount ?? 0}
              {' · '}subagents {linkedSummary.subagentCount ?? 0}
            </div>
            {linkedSummary.description && (
              <div style={{ marginTop: 6 }}>{linkedSummary.description}</div>
            )}
          </div>
        )}
      </div>

      <div style={S.card}>
        <span style={S.cardLabel}>Session defaults</span>
        <div style={{ fontSize: '0.8rem', color: '#94a3b8', marginBottom: 14, lineHeight: 1.5 }}>
          Prefills the New session form and is used when Channel / Deploy omit mounts.
          Per-session mounts remain the runtime source of truth and can be edited later.
        </div>
        <div style={S.row}>
          <label style={S.fieldLabel}>Default environment</label>
          <select
            style={S.input}
            value={defaultEnvironmentId}
            onChange={e => setDefaultEnvironmentId(e.target.value)}
            disabled={readOnly}
          >
            <option value="">None (use owner heuristic / ensure default)</option>
            {environments.map(env => (
              <option key={env.id} value={env.id}>{env.name} ({env.type})</option>
            ))}
          </select>
          <div style={{ fontSize: '0.78rem', color: '#94a3b8', marginTop: 6 }}>
            Manage environments under Build → Environments.
          </div>
        </div>
        <div style={S.row}>
          <label style={S.fieldLabel}>Default vaults</label>
          {vaults.length === 0 ? (
            <div style={{ fontSize: '0.85rem', color: '#94a3b8' }}>
              No vaults yet. Create one under Build → Vaults.
            </div>
          ) : (
            <div style={{ display: 'grid', gap: 8 }}>
              {vaults.map(v => {
                const on = defaultVaultIds.includes(v.id);
                return (
                  <label key={v.id} style={{ display: 'flex', gap: 10, alignItems: 'center', fontSize: '0.9rem' }}>
                    <input
                      type="checkbox"
                      checked={on}
                      disabled={readOnly}
                      onChange={() => {
                        setDefaultVaultIds(prev =>
                          on ? prev.filter(id => id !== v.id) : [...prev, v.id]);
                      }}
                    />
                    <span>{v.displayName}</span>
                  </label>
                );
              })}
            </div>
          )}
        </div>
        <div style={S.row}>
          <label style={S.fieldLabel}>Default memory stores</label>
          {memoryStores.length === 0 ? (
            <div style={{ fontSize: '0.85rem', color: '#94a3b8' }}>
              No memory stores yet. Create one under Build → Memory.
            </div>
          ) : (
            <div style={{ display: 'grid', gap: 8 }}>
              {memoryStores.map(m => {
                const on = defaultMemoryStoreIds.includes(m.id);
                return (
                  <label key={m.id} style={{ display: 'flex', gap: 10, alignItems: 'center', fontSize: '0.9rem' }}>
                    <input
                      type="checkbox"
                      checked={on}
                      disabled={readOnly}
                      onChange={() => {
                        setDefaultMemoryStoreIds(prev =>
                          on ? prev.filter(id => id !== m.id) : [...prev, m.id]);
                      }}
                    />
                    <span>{m.name}</span>
                  </label>
                );
              })}
            </div>
          )}
        </div>
      </div>

      <div style={S.card}>
        <span style={S.cardLabel}>Behavior</span>

        <div style={S.row}>
          <label style={S.fieldLabel}>System prompt</label>
          <textarea
            style={S.textarea}
            value={system}
            onChange={e => setSystem(e.target.value)}
            disabled={readOnly}
            placeholder="Optional override. When linked and empty, Workspace AGENTS.md is used on rematerialize."
          />
        </div>

        <div style={S.row}>
          <label style={S.fieldLabel}>Max iterations</label>
          <input
            style={{ ...S.input, width: 140 }}
            type="number"
            min={1}
            max={64}
            value={maxIters}
            onChange={e => setMaxIters(e.target.value)}
            disabled={readOnly}
          />
        </div>
      </div>

      {canEdit && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap' }}>
          <button style={S.saveBtn} onClick={handleSave} disabled={saving}>
            {saving ? 'Saving…' : 'Save changes'}
          </button>
          {canShare && (
            <button style={S.shareBtn} onClick={() => setShareOpen(true)}>↗ Share</button>
          )}
          {!agent.archivedAt && (
            <button style={S.dangerBtn} onClick={handleArchive} disabled={archiving}>
              {archiving ? 'Archiving…' : 'Archive agent'}
            </button>
          )}
          <button style={S.dangerBtn} onClick={handleDelete}>Delete agent</button>
        </div>
      )}
      {ok && <p style={S.success}>Saved.</p>}
      {err && <p style={S.error}>{err}</p>}

      {shareOpen && (
        <ShareAgentDialog agent={agent} onClose={() => setShareOpen(false)} />
      )}

      <div style={{ ...S.card, marginTop: 24 }}>
        <span style={S.cardLabel}>Version history</span>
        {versionsErr && <p style={S.error}>{versionsErr}</p>}
        {!versionsErr && versions.length === 0 && (
          <p style={{ color: '#94a3b8', fontSize: '0.88rem', margin: 0 }}>No version snapshots yet.</p>
        )}
        {versions.map(v => (
          <div key={v.version} style={{
            padding: '12px 14px', borderRadius: 8, marginBottom: 8,
            background: '#f8fafc', border: '1px solid #e2e8f0',
          }}>
            <div style={{ fontWeight: 600, fontSize: '0.92rem', color: '#0f172a' }}>
              v{v.version}
              {agent.version === v.version && (
                <span style={{
                  marginLeft: 8, padding: '2px 8px', borderRadius: 999,
                  fontSize: '0.72rem', background: '#dcfce7', color: '#15803d',
                }}>current</span>
              )}
            </div>
            <div style={{ fontSize: '0.78rem', color: '#94a3b8', marginTop: 4 }}>
              {new Date(v.createdAt).toLocaleString()}
            </div>
            {v.snapshot && (
              <pre style={{
                fontSize: '0.76rem', color: '#64748b', marginTop: 8, marginBottom: 0,
                whiteSpace: 'pre-wrap', maxHeight: 120, overflow: 'auto',
              }}>
                {JSON.stringify(v.snapshot, null, 2)}
              </pre>
            )}
          </div>
        ))}
      </div>

      <div style={{ ...S.card, marginTop: 24 }}>
        <span style={S.cardLabel}>Metadata</span>
        <div style={S.row}>
          <label style={S.fieldLabel}>Owner</label>
          <div style={S.meta}>{agent.ownerId ?? '—'}</div>
        </div>
        <div style={S.row}>
          <label style={S.fieldLabel}>Created</label>
          <div style={S.meta}>{new Date(agent.createdAt).toLocaleString()}</div>
        </div>
        <div style={S.row}>
          <label style={S.fieldLabel}>Updated</label>
          <div style={S.meta}>{new Date(agent.updatedAt).toLocaleString()}</div>
        </div>
      </div>
    </div>
  );
}
