import React, { useCallback, useEffect, useState } from 'react';
import AdminAppShell from '../../components/admin/AdminAppShell';
import { approveContribution, listContributions, rejectContribution } from '../../api/contributions';
import type { Contribution, ContributionStatus } from '../../api/contributions';

const STATUSES: ContributionStatus[] = ['PENDING', 'APPROVED', 'REJECTED'];

const S: Record<string, React.CSSProperties> = {
  page: { padding: '24px 28px', display: 'flex', flexDirection: 'column', gap: 16 },
  header: { display: 'flex', alignItems: 'baseline', gap: 12 },
  h1: { fontSize: '1.1rem', fontWeight: 600, color: '#0f172a' },
  sub: { fontSize: '0.85rem', color: '#64748b' },
  filters: { display: 'flex', gap: 8 },
  card: {
    background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 10,
    padding: 16, display: 'flex', flexDirection: 'column', gap: 10,
  },
  cardHeader: { display: 'flex', alignItems: 'center', gap: 8, justifyContent: 'space-between' },
  cardTitle: { fontSize: '0.92rem', fontWeight: 600, color: '#0f172a' },
  meta: { fontSize: '0.78rem', color: '#64748b' },
  rationale: { fontSize: '0.85rem', color: '#334155', fontStyle: 'italic' as const },
  payload: {
    background: '#0f172a', color: '#e2e8f0', borderRadius: 6, padding: '10px 14px',
    fontSize: '0.78rem', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    overflow: 'auto' as const, maxHeight: 280, whiteSpace: 'pre-wrap' as const,
  },
  actions: { display: 'flex', gap: 8, alignItems: 'center' },
  noteInput: {
    flex: 1, background: '#f8fafc', border: '1px solid #e5e7eb',
    borderRadius: 6, padding: '6px 10px', fontSize: '0.85rem',
  },
  approveBtn: {
    background: '#16a34a', color: '#fff', border: 'none', borderRadius: 6,
    padding: '6px 16px', cursor: 'pointer', fontSize: '0.82rem', fontWeight: 600,
  },
  rejectBtn: {
    background: '#dc2626', color: '#fff', border: 'none', borderRadius: 6,
    padding: '6px 16px', cursor: 'pointer', fontSize: '0.82rem', fontWeight: 600,
  },
  err: { color: '#dc2626', fontSize: '0.82rem' },
};

function filterBtnStyle(active: boolean): React.CSSProperties {
  return {
    background: active ? '#4338ca' : '#ffffff',
    color: active ? '#ffffff' : '#475569',
    border: '1px solid ' + (active ? '#4338ca' : '#e5e7eb'),
    borderRadius: 6, padding: '6px 14px', cursor: 'pointer',
    fontSize: '0.82rem', fontWeight: 600,
  };
}

function badgeStyle(status: string): React.CSSProperties {
  return {
    display: 'inline-block', padding: '3px 10px', borderRadius: 4, fontSize: '0.72rem', fontWeight: 700,
    background:
      status === 'APPROVED' ? '#dcfce7' :
      status === 'REJECTED' ? '#fee2e2' : '#e0e7ff',
    color:
      status === 'APPROVED' ? '#166534' :
      status === 'REJECTED' ? '#991b1b' : '#4338ca',
  };
}

export default function ApprovalsPage() {
  const [filter, setFilter] = useState<ContributionStatus>('PENDING');
  const [items, setItems] = useState<Contribution[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [notes, setNotes] = useState<Record<number, string>>({});
  const [actionErr, setActionErr] = useState<Record<number, string>>({});

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      setItems(await listContributions(filter));
    } catch (e) {
      setErr(String(e));
    } finally {
      setLoading(false);
    }
  }, [filter]);

  useEffect(() => { load(); }, [load]);

  async function onApprove(id: number) {
    try {
      await approveContribution(id, notes[id] || '');
      load();
    } catch (e) {
      setActionErr(prev => ({ ...prev, [id]: String(e) }));
    }
  }

  async function onReject(id: number) {
    try {
      await rejectContribution(id, notes[id] || '');
      load();
    } catch (e) {
      setActionErr(prev => ({ ...prev, [id]: String(e) }));
    }
  }

  return (
    <AdminAppShell>
      <div style={S.page}>
        <div style={S.header}>
          <div style={S.h1}>Workspace contribution approvals</div>
          <div style={S.sub}>Approve to materialise under <code>shared/</code> for every tenant.</div>
        </div>

        <div style={S.filters}>
          {STATUSES.map(s => (
            <button key={s} style={filterBtnStyle(filter === s)} onClick={() => setFilter(s)}>{s}</button>
          ))}
        </div>

        {loading && <div style={S.sub}>Loading…</div>}
        {err && <div style={S.err}>{err}</div>}
        {!loading && !err && items.length === 0 && (
          <div style={S.sub}>No contributions with status <strong>{filter}</strong>.</div>
        )}

        {items.map(c => (
          <div key={c.id} style={S.card}>
            <div style={S.cardHeader}>
              <div style={S.cardTitle}>
                #{c.id} · {c.targetType} · <code>{c.targetPath}</code>
              </div>
              <span style={badgeStyle(c.status)}>{c.status}</span>
            </div>
            <div style={S.meta}>
              from <strong>{c.sourceUserId}</strong>
              {c.sourceAgentId ? <> · agent <strong>{c.sourceAgentId}</strong></> : null}
              {' · '} {new Date(c.createdAt).toLocaleString()}
            </div>
            {c.rationale && <div style={S.rationale}>"{c.rationale}"</div>}
            <div style={S.payload}>{`(payload is stored verbatim — fetch via API if you need to inspect it before approving)`}</div>
            {c.reviewerNote && (
              <div style={S.meta}>
                Reviewer note: <em>{c.reviewerNote}</em>
                {c.reviewerUserId && <> (by {c.reviewerUserId})</>}
              </div>
            )}
            {c.status === 'PENDING' && (
              <div style={S.actions}>
                <input
                  style={S.noteInput}
                  placeholder="Reviewer note (optional)"
                  value={notes[c.id] || ''}
                  onChange={e => setNotes(prev => ({ ...prev, [c.id]: e.target.value }))}
                />
                <button style={S.approveBtn} onClick={() => onApprove(c.id)}>Approve</button>
                <button style={S.rejectBtn} onClick={() => onReject(c.id)}>Reject</button>
              </div>
            )}
            {actionErr[c.id] && <div style={S.err}>{actionErr[c.id]}</div>}
          </div>
        ))}
      </div>
    </AdminAppShell>
  );
}
