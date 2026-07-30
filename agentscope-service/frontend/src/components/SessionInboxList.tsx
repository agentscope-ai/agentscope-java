import React, { useEffect, useState } from 'react';
import { ManagedSession, listManagedSessions } from '../api/managedSessions';
import { useNavigate, useParams } from 'react-router-dom';

const S: Record<string, React.CSSProperties> = {
  root: { padding: '28px 32px', minWidth: 0, maxWidth: 1000 },
  title: { margin: '0 0 18px', fontSize: '1.4rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.01em' },
  empty: { padding: '60px 0', color: '#94a3b8', fontSize: '0.95rem', textAlign: 'center' },
  card: {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 12,
    padding: '18px 20px', marginBottom: 12, cursor: 'pointer',
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
    transition: 'border-color 0.15s ease, box-shadow 0.15s ease, transform 0.15s ease',
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
  cardFooter: {
    display: 'flex', alignItems: 'center', gap: 10, marginTop: 10,
    fontSize: '0.78rem', color: '#94a3b8',
  },
  transcriptLink: {
    color: '#6366f1', cursor: 'pointer', fontWeight: 500, textDecoration: 'none',
  },
  err: { color: '#dc2626', fontSize: '0.9rem' },
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

/**
 * Managed session list (`/api/sessions`). The legacy per-agent inbox
 * (`/api/agents/{id}/sessions/*`) was removed in the four-plane split.
 */
export default function SessionInboxList({ agentId }: { agentId: string }) {
  const [managedEntries, setManagedEntries] = useState<ManagedSession[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const aid = id ?? agentId;

  useEffect(() => {
    let cancelled = false;
    setErr(null);
    listManagedSessions(agentId)
      .then(list => {
        if (!cancelled) setManagedEntries(list.filter(s => s.archivedAt == null));
      })
      .catch(e => {
        if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed to load sessions');
      });
    return () => { cancelled = true; };
  }, [agentId]);

  return (
    <div style={S.root}>
      <h2 style={S.title}>Sessions</h2>
      {err && <div style={S.err}>{err}</div>}

      {!err && managedEntries.length === 0 && (
        <div style={S.empty}>No managed sessions yet — try chatting first.</div>
      )}
      {managedEntries.map(s => {
        const reason = stopReasonSummary(s.stopReason);
        return (
          <div
            key={s.id}
            style={S.card}
            onClick={() => navigate(`/agents/${encodeURIComponent(aid)}/chat?managed=${encodeURIComponent(s.id)}`)}
            title="Resume this managed session in Chat"
            onMouseEnter={ev => {
              ev.currentTarget.style.borderColor = '#c7d2fe';
              ev.currentTarget.style.boxShadow = '0 4px 12px rgba(15,23,42,0.06)';
            }}
            onMouseLeave={ev => {
              ev.currentTarget.style.borderColor = '#e2e8f0';
              ev.currentTarget.style.boxShadow = '0 1px 3px rgba(15,23,42,0.04)';
            }}
          >
            <div style={S.cardHeader}>
              <span style={S.label}>{s.id}</span>
              <span style={statusStyle(s.status)}>{s.status}</span>
              <span style={S.time}>{relTime(s.updatedAt)}</span>
            </div>
            {reason && <div style={S.stopReason}>stop: {reason}</div>}
            <div style={S.cardFooter}>
              <span>Click to resume in Chat</span>
              <span style={{ flex: 1 }} />
              <span
                style={S.transcriptLink}
                onClick={ev => {
                  ev.stopPropagation();
                  navigate(`/agents/${encodeURIComponent(aid)}/sessions/_managed?managed=${encodeURIComponent(s.id)}`);
                }}
              >
                View transcript →
              </span>
            </div>
          </div>
        );
      })}
    </div>
  );
}
