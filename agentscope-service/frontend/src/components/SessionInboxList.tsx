import React, { useCallback, useEffect, useState } from 'react';
import {
  ManagedSession,
  ManagedSessionListStatus,
  archiveManagedSession,
  deleteManagedSession,
  listManagedSessions,
  restoreManagedSession,
} from '../api/managedSessions';
import { Environment, listEnvironments } from '../api/environments';
import { useNavigate, useParams } from 'react-router-dom';
import NewManagedSessionForm from './NewManagedSessionForm';

const S: Record<string, React.CSSProperties> = {
  root: { padding: '28px 32px', minWidth: 0, maxWidth: 1000 },
  header: { display: 'flex', alignItems: 'center', gap: 12, marginBottom: 18 },
  title: { margin: 0, fontSize: '1.4rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.01em', flex: 1 },
  tabs: { display: 'flex', gap: 6, marginBottom: 16 },
  tab: {
    padding: '6px 12px', borderRadius: 8, border: '1px solid #e2e8f0',
    background: '#ffffff', color: '#64748b', cursor: 'pointer', fontSize: '0.85rem', fontWeight: 500,
  },
  tabActive: { background: '#eef2ff', color: '#4338ca', borderColor: '#c7d2fe' },
  primary: {
    padding: '8px 14px', borderRadius: 8, cursor: 'pointer', fontSize: '0.88rem', fontWeight: 600,
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none',
    boxShadow: '0 2px 6px rgba(99,102,241,0.25)',
  },
  empty: { padding: '60px 0', color: '#94a3b8', fontSize: '0.95rem', textAlign: 'center' },
  emptyLink: {
    color: '#6366f1', cursor: 'pointer', fontWeight: 600, background: 'none',
    border: 'none', fontSize: '0.95rem', padding: 0,
  },
  card: {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 12,
    padding: '18px 20px', marginBottom: 12,
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
  cardHeader: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 },
  label: { fontSize: '0.98rem', color: '#0f172a', fontWeight: 600, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
  time: { fontSize: '0.8rem', color: '#94a3b8', flexShrink: 0 },
  statusTag: {
    fontSize: '0.72rem', textTransform: 'uppercase', letterSpacing: '0.04em', fontWeight: 600,
    padding: '2px 8px', borderRadius: 6, flexShrink: 0,
  },
  stopReason: {
    fontSize: '0.78rem', color: '#64748b', marginTop: 4,
    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
  },
  mounts: { fontSize: '0.78rem', color: '#64748b', marginTop: 8 },
  cardFooter: {
    display: 'flex', alignItems: 'center', gap: 10, marginTop: 12,
    fontSize: '0.78rem', color: '#94a3b8', flexWrap: 'wrap',
  },
  action: {
    color: '#6366f1', cursor: 'pointer', fontWeight: 500, background: 'none',
    border: 'none', padding: 0, fontSize: '0.78rem',
  },
  danger: { color: '#dc2626' },
  err: { color: '#dc2626', fontSize: '0.9rem', marginBottom: 12 },
};

function relTime(ms: number): string {
  const diff = Date.now() - ms;
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h`;
  return `${Math.floor(diff / 86_400_000)}d`;
}

function statusStyle(status: string): React.CSSProperties {
  const base = { ...S.statusTag };
  switch (status) {
    case 'running':
      return { ...base, color: '#0369a1', background: '#e0f2fe' };
    case 'idle':
    case 'active':
      return { ...base, color: '#15803d', background: '#dcfce7' };
    case 'requires_action':
      return { ...base, color: '#c2410c', background: '#ffedd5' };
    case 'terminated':
    case 'archived':
      return { ...base, color: '#64748b', background: '#f1f5f9' };
    case 'rescheduled':
      return { ...base, color: '#a16207', background: '#fef9c3' };
    default:
      return { ...base, color: '#4338ca', background: '#eef2ff' };
  }
}

function stopReasonSummary(stopReason: Record<string, unknown> | null | undefined): string | null {
  if (!stopReason || typeof stopReason !== 'object') return null;
  const type = stopReason.type;
  if (typeof type === 'string' && type) return type;
  try {
    const raw = JSON.stringify(stopReason);
    return raw.length > 80 ? `${raw.slice(0, 77)}…` : raw;
  } catch {
    return null;
  }
}

function mountSummary(s: ManagedSession, envNameById: Map<string, string>): string {
  const env = envNameById.get(s.environmentId) || s.environmentId || '—';
  const vaults = s.vaultIds?.length ?? 0;
  const mems = s.memoryStoreIds?.length ?? 0;
  return `env: ${env} · vaults: ${vaults} · memory: ${mems}`;
}

/**
 * Managed session list (`/api/sessions`). The legacy per-agent inbox
 * (`/api/agents/{id}/sessions/*`) was removed in the four-plane split.
 */
export default function SessionInboxList({ agentId }: { agentId: string }) {
  const [tab, setTab] = useState<ManagedSessionListStatus>('active');
  const [managedEntries, setManagedEntries] = useState<ManagedSession[]>([]);
  const [envNameById, setEnvNameById] = useState<Map<string, string>>(new Map());
  const [err, setErr] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const aid = id ?? agentId;

  const reload = useCallback(async () => {
    setErr(null);
    try {
      const [list, envs] = await Promise.all([
        listManagedSessions(agentId, tab),
        listEnvironments().catch(() => [] as Environment[]),
      ]);
      setManagedEntries(list);
      setEnvNameById(new Map(envs.map(e => [e.id, e.name])));
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to load sessions');
    }
  }, [agentId, tab]);

  useEffect(() => {
    void reload();
  }, [reload]);

  async function runAction(sessionId: string, action: () => Promise<unknown>) {
    setBusyId(sessionId);
    setErr(null);
    try {
      await action();
      await reload();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Action failed');
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div style={S.root}>
      <div style={S.header}>
        <h2 style={S.title}>Sessions</h2>
        <button type="button" style={S.primary} onClick={() => setShowCreate(true)}>
          New session
        </button>
      </div>

      <div style={S.tabs}>
        {([
          ['active', 'Active'],
          ['archived', 'Archived'],
        ] as const).map(([key, label]) => (
          <button
            key={key}
            type="button"
            style={{ ...S.tab, ...(tab === key ? S.tabActive : {}) }}
            onClick={() => setTab(key)}
          >
            {label}
          </button>
        ))}
      </div>

      {err && <div style={S.err}>{err}</div>}

      {!err && managedEntries.length === 0 && (
        <div style={S.empty}>
          {tab === 'archived' ? (
            'No archived sessions.'
          ) : (
            <>
              No managed sessions yet —{' '}
              <button type="button" style={S.emptyLink} onClick={() => setShowCreate(true)}>
                create a new session
              </button>
              .
            </>
          )}
        </div>
      )}

      {managedEntries.map(s => {
        const reason = stopReasonSummary(s.stopReason);
        const archived = !!s.archivedAt;
        return (
          <div key={s.id} style={S.card}>
            <div style={S.cardHeader}>
              <span style={S.label}>{s.id}</span>
              <span style={statusStyle(s.status)}>{s.status}</span>
              <span style={S.time}>{relTime(s.updatedAt)}</span>
            </div>
            {reason && <div style={S.stopReason}>stop: {reason}</div>}
            <div style={S.mounts}>{mountSummary(s, envNameById)}</div>
            <div style={S.cardFooter}>
              {!archived && (
                <button
                  type="button"
                  style={S.action}
                  disabled={busyId === s.id}
                  onClick={() => navigate(`/agents/${encodeURIComponent(aid)}/chat?managed=${encodeURIComponent(s.id)}`)}
                >
                  Resume
                </button>
              )}
              <button
                type="button"
                style={S.action}
                onClick={() => navigate(`/agents/${encodeURIComponent(aid)}/sessions/_managed?managed=${encodeURIComponent(s.id)}`)}
              >
                View transcript
              </button>
              {!archived ? (
                <button
                  type="button"
                  style={S.action}
                  disabled={busyId === s.id}
                  onClick={() => {
                    if (!confirm('Archive this managed session?')) return;
                    void runAction(s.id, () => archiveManagedSession(s.id));
                  }}
                >
                  Archive
                </button>
              ) : (
                <button
                  type="button"
                  style={S.action}
                  disabled={busyId === s.id}
                  onClick={() => void runAction(s.id, () => restoreManagedSession(s.id))}
                >
                  Restore
                </button>
              )}
              <button
                type="button"
                style={{ ...S.action, ...S.danger }}
                disabled={busyId === s.id}
                onClick={() => {
                  if (!confirm('Delete this session entirely?')) return;
                  void runAction(s.id, () => deleteManagedSession(s.id));
                }}
              >
                Delete
              </button>
            </div>
          </div>
        );
      })}

      {showCreate && (
        <NewManagedSessionForm
          agentId={agentId}
          onCancel={() => setShowCreate(false)}
          onCreated={session => {
            setShowCreate(false);
            navigate(`/agents/${encodeURIComponent(aid)}/chat?managed=${encodeURIComponent(session.id)}`);
          }}
        />
      )}
    </div>
  );
}
