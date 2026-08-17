import React, { useCallback, useEffect, useState } from 'react';
import AdminAppShell from '../../components/admin/AdminAppShell';
import {
  approveContribution,
  getContribution,
  listContributions,
  rejectContribution,
  type Contribution,
  type ContributionDetail,
  type ContributionStatus,
  type FileEntry,
} from '../../api/contributions';

const STATUSES: ContributionStatus[] = ['PENDING', 'APPROVED', 'REJECTED'];

const S: Record<string, React.CSSProperties> = {
  page: { padding: '24px 28px', display: 'flex', flexDirection: 'column', gap: 16 },
  header: { display: 'flex', alignItems: 'baseline', gap: 12 },
  h1: { fontSize: '1.1rem', fontWeight: 600, color: '#0f172a' },
  sub: { fontSize: '0.85rem', color: '#64748b' },
  filters: { display: 'flex', gap: 8 },
  card: {
    background: '#ffffff',
    border: '1px solid #e5e7eb',
    borderRadius: 10,
    padding: 16,
    display: 'flex',
    flexDirection: 'column',
    gap: 10,
  },
  cardHeader: { display: 'flex', alignItems: 'center', gap: 8, justifyContent: 'space-between' },
  cardTitle: { fontSize: '0.92rem', fontWeight: 600, color: '#0f172a' },
  meta: { fontSize: '0.78rem', color: '#64748b' },
  rationale: { fontSize: '0.85rem', color: '#334155', fontStyle: 'italic' as const },
  expandBtn: {
    background: '#f8fafc',
    border: '1px solid #e5e7eb',
    borderRadius: 6,
    padding: '5px 10px',
    cursor: 'pointer',
    fontSize: '0.78rem',
    color: '#475569',
    alignSelf: 'flex-start',
  },
  detail: {
    border: '1px solid #e5e7eb',
    borderRadius: 8,
    background: '#f8fafc',
    padding: 10,
    display: 'flex',
    flexDirection: 'column',
    gap: 10,
  },
  fileTabs: { display: 'flex', gap: 4, flexWrap: 'wrap' as const },
  fileTab: {
    padding: '4px 10px',
    fontSize: '0.78rem',
    background: '#ffffff',
    border: '1px solid #e5e7eb',
    borderRadius: 6,
    cursor: 'pointer',
    color: '#475569',
  },
  fileTabActive: {
    background: '#4338ca',
    color: '#ffffff',
    border: '1px solid #4338ca',
  },
  fileTabEdited: { borderColor: '#f59e0b', color: '#b45309' },
  payload: {
    background: '#0f172a',
    color: '#e2e8f0',
    borderRadius: 6,
    padding: '10px 14px',
    fontSize: '0.78rem',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    overflow: 'auto' as const,
    maxHeight: 380,
    whiteSpace: 'pre-wrap' as const,
  },
  editor: {
    width: '100%',
    minHeight: 240,
    background: '#0f172a',
    color: '#e2e8f0',
    borderRadius: 6,
    padding: '10px 14px',
    fontSize: '0.78rem',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    border: '1px solid #1e293b',
    resize: 'vertical' as const,
  },
  editRow: { display: 'flex', gap: 8, alignItems: 'center', fontSize: '0.78rem' },
  smallBtn: {
    background: '#e0e7ff',
    color: '#3730a3',
    border: 'none',
    borderRadius: 6,
    padding: '4px 10px',
    cursor: 'pointer',
    fontSize: '0.75rem',
    fontWeight: 600,
  },
  actions: { display: 'flex', gap: 8, alignItems: 'center' },
  noteInput: {
    flex: 1,
    background: '#f8fafc',
    border: '1px solid #e5e7eb',
    borderRadius: 6,
    padding: '6px 10px',
    fontSize: '0.85rem',
  },
  approveBtn: {
    background: '#16a34a',
    color: '#fff',
    border: 'none',
    borderRadius: 6,
    padding: '6px 16px',
    cursor: 'pointer',
    fontSize: '0.82rem',
    fontWeight: 600,
  },
  rejectBtn: {
    background: '#dc2626',
    color: '#fff',
    border: 'none',
    borderRadius: 6,
    padding: '6px 16px',
    cursor: 'pointer',
    fontSize: '0.82rem',
    fontWeight: 600,
  },
  err: { color: '#dc2626', fontSize: '0.82rem' },
};

function filterBtnStyle(active: boolean): React.CSSProperties {
  return {
    background: active ? '#4338ca' : '#ffffff',
    color: active ? '#ffffff' : '#475569',
    border: '1px solid ' + (active ? '#4338ca' : '#e5e7eb'),
    borderRadius: 6,
    padding: '6px 14px',
    cursor: 'pointer',
    fontSize: '0.82rem',
    fontWeight: 600,
  };
}

function badgeStyle(status: string): React.CSSProperties {
  return {
    display: 'inline-block',
    padding: '3px 10px',
    borderRadius: 4,
    fontSize: '0.72rem',
    fontWeight: 700,
    background:
      status === 'APPROVED' ? '#dcfce7' : status === 'REJECTED' ? '#fee2e2' : '#e0e7ff',
    color: status === 'APPROVED' ? '#166534' : status === 'REJECTED' ? '#991b1b' : '#4338ca',
  };
}

function labelFor(fe: FileEntry, idx: number): string {
  if (fe.relPath && fe.relPath.length > 0) return fe.relPath;
  return `file-${idx + 1}`;
}

interface DetailViewProps {
  contributionId: number;
  status: string;
  initialNote: string;
  onApproved: () => void;
  onRejected: () => void;
}

function DetailView({ contributionId, status, initialNote, onApproved, onRejected }: DetailViewProps) {
  const [detail, setDetail] = useState<ContributionDetail | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [active, setActive] = useState(0);
  // Map of file index → edited content. Absence means "use original".
  const [edits, setEdits] = useState<Record<number, string>>({});
  const [editingIdx, setEditingIdx] = useState<number | null>(null);
  const [note, setNote] = useState(initialNote);
  const [working, setWorking] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setErr(null);
    getContribution(contributionId)
      .then(d => {
        if (cancelled) return;
        setDetail(d);
        // Seed edits from approvedPayload when present (re-opened after a prior edit).
        if (d.approvedPayload && d.approvedPayload.length === d.payload.length) {
          const seeded: Record<number, string> = {};
          d.approvedPayload.forEach((fe, i) => {
            if (fe.content !== d.payload[i].content) seeded[i] = fe.content;
          });
          setEdits(seeded);
        }
      })
      .catch(e => !cancelled && setErr(String(e)));
    return () => {
      cancelled = true;
    };
  }, [contributionId]);

  if (err) return <div style={S.err}>{err}</div>;
  if (!detail) return <div style={S.sub}>Loading payload…</div>;

  const files = detail.payload;
  const file = files[active];
  const original = file?.content ?? '';
  const edited = edits[active];
  const display = edited !== undefined ? edited : original;
  const isEdited = (i: number) => edits[i] !== undefined && edits[i] !== files[i].content;

  function buildApprovedPayload(): FileEntry[] | null {
    const hasEdits = Object.keys(edits).some(k => isEdited(Number(k)));
    if (!hasEdits) return null;
    return files.map((fe, i) => ({
      relPath: fe.relPath,
      content: edits[i] !== undefined ? edits[i] : fe.content,
    }));
  }

  async function doApprove() {
    setWorking(true);
    try {
      await approveContribution(contributionId, {
        note: note || null,
        approvedPayload: buildApprovedPayload(),
      });
      onApproved();
    } catch (e) {
      setErr(String(e));
    } finally {
      setWorking(false);
    }
  }
  async function doReject() {
    setWorking(true);
    try {
      await rejectContribution(contributionId, note || '');
      onRejected();
    } catch (e) {
      setErr(String(e));
    } finally {
      setWorking(false);
    }
  }

  return (
    <div style={S.detail}>
      <div style={S.fileTabs}>
        {files.map((fe, i) => {
          const styleBase: React.CSSProperties = { ...S.fileTab };
          if (i === active) Object.assign(styleBase, S.fileTabActive);
          else if (isEdited(i)) Object.assign(styleBase, S.fileTabEdited);
          return (
            <button
              key={`${i}-${fe.relPath}`}
              type="button"
              style={styleBase}
              onClick={() => {
                setActive(i);
                setEditingIdx(null);
              }}
            >
              {labelFor(fe, i)}
              {isEdited(i) && i !== active ? ' •' : ''}
            </button>
          );
        })}
      </div>

      {editingIdx === active ? (
        <textarea
          style={S.editor}
          value={display}
          onChange={e =>
            setEdits(prev => ({ ...prev, [active]: e.target.value }))
          }
        />
      ) : (
        <pre style={S.payload}>{display}</pre>
      )}

      <div style={S.editRow}>
        {status === 'PENDING' && editingIdx !== active && (
          <button type="button" style={S.smallBtn} onClick={() => setEditingIdx(active)}>
            Edit
          </button>
        )}
        {editingIdx === active && (
          <>
            <button type="button" style={S.smallBtn} onClick={() => setEditingIdx(null)}>
              Done
            </button>
            <button
              type="button"
              style={{ ...S.smallBtn, background: '#fef3c7', color: '#92400e' }}
              onClick={() => {
                setEdits(prev => {
                  const next = { ...prev };
                  delete next[active];
                  return next;
                });
                setEditingIdx(null);
              }}
            >
              Revert
            </button>
          </>
        )}
        {Object.keys(edits).length > 0 && (
          <span style={S.meta}>
            {Object.keys(edits).filter(k => isEdited(Number(k))).length} file(s) edited
          </span>
        )}
      </div>

      {status === 'PENDING' && (
        <div style={S.actions}>
          <input
            style={S.noteInput}
            placeholder="Reviewer note (optional)"
            value={note}
            onChange={e => setNote(e.target.value)}
          />
          <button style={S.approveBtn} onClick={doApprove} disabled={working}>
            Approve
          </button>
          <button style={S.rejectBtn} onClick={doReject} disabled={working}>
            Reject
          </button>
        </div>
      )}
    </div>
  );
}

export default function ApprovalsPage() {
  const [filter, setFilter] = useState<ContributionStatus>('PENDING');
  const [items, setItems] = useState<Contribution[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<number>>(() => new Set());
  const [notes] = useState<Record<number, string>>({});

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

  useEffect(() => {
    load();
  }, [load]);

  const toggleExpand = (id: number) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  return (
    <AdminAppShell>
      <div style={S.page}>
        <div style={S.header}>
          <div style={S.h1}>Workspace contribution approvals</div>
          <div style={S.sub}>
            Approve to materialise under <code>shared/agents/&lt;agentId&gt;/</code> for every
            tenant of that agent.
          </div>
        </div>

        <div style={S.filters}>
          {STATUSES.map(s => (
            <button key={s} style={filterBtnStyle(filter === s)} onClick={() => setFilter(s)}>
              {s}
            </button>
          ))}
        </div>

        {loading && <div style={S.sub}>Loading…</div>}
        {err && <div style={S.err}>{err}</div>}
        {!loading && !err && items.length === 0 && (
          <div style={S.sub}>
            No contributions with status <strong>{filter}</strong>.
          </div>
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
              {c.sourceAgentId ? (
                <>
                  {' '}
                  · source agent <strong>{c.sourceAgentId}</strong>
                </>
              ) : null}
              {c.targetAgentId && c.targetAgentId !== c.sourceAgentId ? (
                <>
                  {' '}
                  · target agent <strong>{c.targetAgentId}</strong>
                </>
              ) : null}
              {' · '}
              {new Date(c.createdAt).toLocaleString()}
            </div>
            {c.rationale && <div style={S.rationale}>"{c.rationale}"</div>}
            {c.reviewerNote && (
              <div style={S.meta}>
                Reviewer note: <em>{c.reviewerNote}</em>
                {c.reviewerUserId && <> (by {c.reviewerUserId})</>}
              </div>
            )}
            <button
              type="button"
              style={S.expandBtn}
              onClick={() => toggleExpand(c.id)}
            >
              {expanded.has(c.id) ? 'Hide payload' : 'View payload'}
            </button>
            {expanded.has(c.id) && (
              <DetailView
                contributionId={c.id}
                status={c.status}
                initialNote={notes[c.id] || ''}
                onApproved={() => {
                  toggleExpand(c.id);
                  load();
                }}
                onRejected={() => {
                  toggleExpand(c.id);
                  load();
                }}
              />
            )}
          </div>
        ))}
      </div>
    </AdminAppShell>
  );
}
