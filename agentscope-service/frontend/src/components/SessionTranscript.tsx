import React, { useEffect, useState } from 'react';
import {
  ManagedSession,
  archiveManagedSession,
  deleteManagedSession,
  getManagedSession,
  restoreManagedSession,
  updateManagedSession,
} from '../api/managedSessions';
import { Environment, listEnvironments } from '../api/environments';
import { MemoryStore, listMemoryStores } from '../api/memoryStores';
import { Vault, listVaults } from '../api/vaults';
import { useNavigate } from 'react-router-dom';
import SessionEventTimeline from './SessionEventTimeline';

const S: Record<string, React.CSSProperties> = {
  root: { padding: '28px 32px', minWidth: 0, maxWidth: 1100 },
  bar: { display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 },
  title: { fontSize: '1.25rem', fontWeight: 700, color: '#0f172a', margin: 0, letterSpacing: '-0.01em' },
  back: {
    background: '#ffffff', border: '1px solid #e2e8f0', color: '#475569',
    padding: '7px 14px', borderRadius: 8, cursor: 'pointer', fontSize: '0.85rem', fontWeight: 500,
  },
  btn: {
    padding: '7px 14px', borderRadius: 8, cursor: 'pointer',
    fontSize: '0.85rem', fontWeight: 500, border: '1px solid #cbd5e1',
    background: '#ffffff', color: '#475569',
  },
  danger: { color: '#dc2626', borderColor: '#fca5a5' },
  primary: {
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none',
    boxShadow: '0 2px 6px rgba(99,102,241,0.25), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  meta: { fontSize: '0.82rem', color: '#94a3b8', fontFamily: 'monospace', marginBottom: 22 },
  err: { color: '#dc2626', fontSize: '0.9rem' },
  notice: { color: '#94a3b8', fontSize: '0.9rem' },
  panel: {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 12,
    padding: '16px 18px', marginBottom: 18,
  },
  panelTitle: { fontSize: '0.95rem', fontWeight: 600, color: '#0f172a', margin: '0 0 6px' },
  hint: { fontSize: '0.78rem', color: '#94a3b8', marginBottom: 12 },
  field: { display: 'block', fontSize: '0.8rem', color: '#64748b', marginBottom: 4, fontWeight: 500 },
  input: {
    width: '100%', boxSizing: 'border-box', padding: '8px 10px', marginBottom: 10,
    border: '1px solid #cbd5e1', borderRadius: 8, fontSize: '0.88rem',
  },
  textarea: {
    width: '100%', boxSizing: 'border-box', padding: '8px 10px', marginBottom: 10,
    border: '1px solid #cbd5e1', borderRadius: 8, fontSize: '0.88rem', minHeight: 90,
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  },
  checkGrid: { display: 'grid', gap: 8, marginBottom: 12 },
  checkRow: { display: 'flex', gap: 10, alignItems: 'center', fontSize: '0.88rem', color: '#334155' },
};

/** Sentinel {@code sessionKey} used by callers linking directly to a managed session. */
const MANAGED_ONLY_KEY = '_managed';

function parseOverrides(raw: string | null | undefined): Record<string, unknown> {
  if (!raw) return {};
  try {
    const v = JSON.parse(raw);
    return v && typeof v === 'object' ? v as Record<string, unknown> : {};
  } catch {
    return {};
  }
}

/**
 * Managed session transcript (event timeline). The legacy per-agent gateway sessions
 * (`/api/agents/{id}/sessions/*`) were removed in the four-plane split.
 */
export default function SessionTranscript({
  agentId,
  sessionKey,
  managedSessionId,
}: {
  agentId: string;
  sessionKey: string;
  managedSessionId?: string;
}) {
  const managedOnly = sessionKey === MANAGED_ONLY_KEY && !!managedSessionId;
  const [managedSession, setManagedSession] = useState<ManagedSession | null>(null);
  const [environments, setEnvironments] = useState<Environment[]>([]);
  const [vaults, setVaults] = useState<Vault[]>([]);
  const [memoryStores, setMemoryStores] = useState<MemoryStore[]>([]);
  const [environmentId, setEnvironmentId] = useState('');
  const [vaultIds, setVaultIds] = useState<string[]>([]);
  const [memoryStoreIds, setMemoryStoreIds] = useState<string[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [system, setSystem] = useState('');
  const [model, setModel] = useState('');
  const [maxIters, setMaxIters] = useState('');
  const [savingOverrides, setSavingOverrides] = useState(false);
  const [savingMounts, setSavingMounts] = useState(false);
  const navigate = useNavigate();

  const archived = !!managedSession?.archivedAt;

  async function reload() {
    setErr(null);
    try {
      if (managedOnly && managedSessionId) {
        const [sess, envs, vs, ms] = await Promise.all([
          getManagedSession(managedSessionId),
          listEnvironments().catch(() => [] as Environment[]),
          listVaults().catch(() => [] as Vault[]),
          listMemoryStores().catch(() => [] as MemoryStore[]),
        ]);
        setManagedSession(sess);
        setEnvironments(envs.filter(e => !e.archivedAt));
        setVaults(vs);
        setMemoryStores(ms);
        setEnvironmentId(sess.environmentId || '');
        setVaultIds(sess.vaultIds ?? []);
        setMemoryStoreIds(sess.memoryStoreIds ?? []);
        const ov = parseOverrides(sess.agentOverridesJson);
        setSystem(typeof ov.system === 'string' ? ov.system : '');
        setModel(typeof ov.model === 'string' ? ov.model : '');
        setMaxIters(ov.maxIters != null ? String(ov.maxIters) : '');
      }
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed');
    }
  }

  useEffect(() => {
    reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId, sessionKey, managedSessionId]);

  async function handleArchiveManaged() {
    if (!managedSessionId || !confirm('Archive this managed session?')) return;
    try {
      await archiveManagedSession(managedSessionId);
      reload();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed');
    }
  }

  async function handleRestoreManaged() {
    if (!managedSessionId) return;
    try {
      await restoreManagedSession(managedSessionId);
      reload();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed');
    }
  }

  async function handleDelete() {
    if (!managedSessionId || !confirm('Delete this session entirely?')) return;
    try {
      await deleteManagedSession(managedSessionId);
      navigate(`/agents/${encodeURIComponent(agentId)}/sessions`, { replace: true });
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed');
    }
  }

  async function handleSaveOverrides(e: React.FormEvent) {
    e.preventDefault();
    if (!managedSessionId || archived) return;
    setSavingOverrides(true);
    setErr(null);
    try {
      const agentOverrides: Record<string, unknown> = {
        system: system.trim() ? system : null,
        model: model.trim() ? model : null,
        maxIters: maxIters.trim() ? Number(maxIters) : null,
      };
      if (maxIters.trim() && Number.isNaN(Number(maxIters))) {
        throw new Error('maxIters must be a number');
      }
      const updated = await updateManagedSession(managedSessionId, { agentOverrides });
      setManagedSession(updated);
    } catch (ex: unknown) {
      setErr(ex instanceof Error ? ex.message : 'Failed to save overrides');
    } finally {
      setSavingOverrides(false);
    }
  }

  async function handleSaveMounts(e: React.FormEvent) {
    e.preventDefault();
    if (!managedSessionId || archived) return;
    setSavingMounts(true);
    setErr(null);
    try {
      if (!environmentId.trim()) throw new Error('environmentId is required');
      const updated = await updateManagedSession(managedSessionId, {
        environmentId: environmentId.trim(),
        vaultIds,
        memoryStoreIds,
      });
      setManagedSession(updated);
    } catch (ex: unknown) {
      setErr(ex instanceof Error ? ex.message : 'Failed to save mounts');
    } finally {
      setSavingMounts(false);
    }
  }

  if (!managedOnly) {
    return (
      <div style={S.root}>
        <div style={S.bar}>
          <button style={S.back} onClick={() => navigate(`/agents/${encodeURIComponent(agentId)}/sessions`)}>← Back</button>
          <h2 style={S.title}>Transcript</h2>
        </div>
        <div style={S.notice}>
          Legacy gateway sessions were removed in the four-plane split. Open a managed session from the sessions list instead.
        </div>
      </div>
    );
  }

  return (
    <div style={S.root}>
      <div style={S.bar}>
        <button style={S.back} onClick={() => navigate(`/agents/${encodeURIComponent(agentId)}/sessions`)}>← Back</button>
        <h2 style={S.title}>Transcript</h2>
        <span style={{ flex: 1 }} />
        {!archived && (
          <button
            style={{ ...S.btn, ...S.primary }}
            onClick={() => navigate(
              `/agents/${encodeURIComponent(agentId)}/chat?managed=${encodeURIComponent(managedSessionId!)}`,
            )}
            title="Resume this conversation in the Chat tab"
          >
            ▶ Continue in Chat
          </button>
        )}
        {archived ? (
          <button style={S.btn} onClick={handleRestoreManaged}>Restore</button>
        ) : (
          <button style={S.btn} onClick={handleArchiveManaged}>Archive</button>
        )}
        <button style={{ ...S.btn, ...S.danger }} onClick={handleDelete}>Delete</button>
      </div>
      <div style={S.meta}>
        {managedSessionId}
        {managedSession && ` · ${managedSession.status}`}
        {archived && ' · archived'}
      </div>
      {err && <div style={S.err}>{err}</div>}

      <div style={S.panel}>
        <div style={S.panelTitle}>Mounts</div>
        <div style={S.hint}>
          {archived
            ? 'Archived sessions are read-only. Restore to change mounts.'
            : 'Applies on the next turn when the data plane resolves the session.'}
        </div>
        <form onSubmit={handleSaveMounts}>
          <label style={S.field}>Environment</label>
          <select
            style={S.input}
            value={environmentId}
            onChange={e => setEnvironmentId(e.target.value)}
            disabled={archived}
          >
            <option value="">Select environment…</option>
            {environments.map(env => (
              <option key={env.id} value={env.id}>{env.name} ({env.type})</option>
            ))}
          </select>
          <label style={S.field}>Vaults</label>
          <div style={S.checkGrid}>
            {vaults.length === 0 && <div style={S.hint}>No vaults available.</div>}
            {vaults.map(v => {
              const on = vaultIds.includes(v.id);
              return (
                <label key={v.id} style={S.checkRow}>
                  <input
                    type="checkbox"
                    checked={on}
                    disabled={archived}
                    onChange={() => {
                      setVaultIds(prev => on ? prev.filter(id => id !== v.id) : [...prev, v.id]);
                    }}
                  />
                  <span>{v.displayName}</span>
                </label>
              );
            })}
          </div>
          <label style={S.field}>Memory stores</label>
          <div style={S.checkGrid}>
            {memoryStores.length === 0 && <div style={S.hint}>No memory stores available.</div>}
            {memoryStores.map(m => {
              const on = memoryStoreIds.includes(m.id);
              return (
                <label key={m.id} style={S.checkRow}>
                  <input
                    type="checkbox"
                    checked={on}
                    disabled={archived}
                    onChange={() => {
                      setMemoryStoreIds(prev => on ? prev.filter(id => id !== m.id) : [...prev, m.id]);
                    }}
                  />
                  <span>{m.name}</span>
                </label>
              );
            })}
          </div>
          {!archived && (
            <button type="submit" style={{ ...S.btn, ...S.primary }} disabled={savingMounts}>
              {savingMounts ? 'Saving…' : 'Save mounts'}
            </button>
          )}
        </form>
      </div>

      <div style={S.panel}>
        <div style={S.panelTitle}>Session overrides</div>
        <div style={S.hint}>Applies on the next turn. Tools / MCP cannot be overridden here.</div>
        <form onSubmit={handleSaveOverrides}>
          <label style={S.field}>System prompt</label>
          <textarea
            style={S.textarea}
            value={system}
            onChange={e => setSystem(e.target.value)}
            placeholder="Leave empty to clear override"
            disabled={archived}
          />
          <label style={S.field}>Model</label>
          <input
            style={S.input}
            value={model}
            onChange={e => setModel(e.target.value)}
            placeholder="e.g. qwen-plus"
            disabled={archived}
          />
          <label style={S.field}>Max iters</label>
          <input
            style={S.input}
            value={maxIters}
            onChange={e => setMaxIters(e.target.value)}
            placeholder="e.g. 10"
            disabled={archived}
          />
          {!archived && (
            <button type="submit" style={{ ...S.btn, ...S.primary }} disabled={savingOverrides}>
              {savingOverrides ? 'Saving…' : 'Save overrides'}
            </button>
          )}
        </form>
      </div>

      {managedSessionId && <SessionEventTimeline managedSessionId={managedSessionId} />}
    </div>
  );
}
