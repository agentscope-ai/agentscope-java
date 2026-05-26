import React, { useCallback, useEffect, useState } from 'react';
import { listMyContributions, submitContribution } from '../api/contributions';
import type { Contribution } from '../api/contributions';

const S: Record<string, React.CSSProperties> = {
  page: { padding: '20px 24px', display: 'flex', flexDirection: 'column', gap: 16, height: '100%', overflow: 'auto' },
  header: { display: 'flex', alignItems: 'baseline', gap: 12 },
  h1: { fontSize: '1.05rem', fontWeight: 600, color: '#e2e8f0' },
  sub: { fontSize: '0.78rem', color: '#7c8bad' },
  panel: { background: '#0d0f18', border: '1px solid #1a1d2e', borderRadius: 8, padding: 16 },
  panelTitle: { fontSize: '0.85rem', fontWeight: 600, color: '#c4caff', marginBottom: 10 },
  row: { display: 'flex', gap: 8, marginBottom: 8 },
  label: { fontSize: '0.72rem', color: '#7c8bad', marginBottom: 4, display: 'block' },
  input: {
    width: '100%', background: '#0f1117', color: '#e2e8f0',
    border: '1px solid #2d3148', borderRadius: 6, padding: '6px 8px', fontSize: '0.8rem',
  },
  textarea: {
    width: '100%', background: '#0f1117', color: '#e2e8f0',
    border: '1px solid #2d3148', borderRadius: 6, padding: '6px 8px', fontSize: '0.8rem',
    minHeight: 110, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  },
  btn: {
    background: '#6366f1', color: '#fff', border: 'none', borderRadius: 6,
    padding: '6px 14px', cursor: 'pointer', fontSize: '0.8rem', fontWeight: 600,
  },
  table: {
    width: '100%', borderCollapse: 'collapse' as const, fontSize: '0.78rem',
  },
  th: {
    textAlign: 'left' as const, padding: '6px 8px', borderBottom: '1px solid #1e2235',
    color: '#7c8bad', fontWeight: 500, fontSize: '0.72rem', textTransform: 'uppercase' as const,
  },
  td: { padding: '8px', borderBottom: '1px solid #131726', color: '#cbd5e1', verticalAlign: 'top' as const },
  err: { color: '#f87171', fontSize: '0.78rem' },
  ok: { color: '#86efac', fontSize: '0.78rem' },
};

function badgeStyle(status: string): React.CSSProperties {
  return {
    display: 'inline-block', padding: '2px 8px', borderRadius: 4, fontSize: '0.7rem', fontWeight: 600,
    background:
      status === 'APPROVED' ? '#1e3a2b' :
      status === 'REJECTED' ? '#3a1e1e' : '#1e2235',
    color:
      status === 'APPROVED' ? '#86efac' :
      status === 'REJECTED' ? '#fca5a5' : '#c4caff',
  };
}

const TYPES = ['skill', 'subagent', 'memory'];

export default function ContributionsPage() {
  const [items, setItems] = useState<Contribution[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const [targetType, setTargetType] = useState('skill');
  const [targetPath, setTargetPath] = useState('');
  const [sourceAgentId, setSourceAgentId] = useState('');
  const [rationale, setRationale] = useState('');
  const [payload, setPayload] = useState('');
  const [submitErr, setSubmitErr] = useState<string | null>(null);
  const [submitOk, setSubmitOk] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      setItems(await listMyContributions());
    } catch (e) {
      setErr(String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitErr(null);
    setSubmitOk(null);
    if (!targetPath.trim() || !payload.trim()) {
      setSubmitErr('Target path and payload are required.');
      return;
    }
    try {
      const created = await submitContribution({
        targetType,
        targetPath: targetPath.trim(),
        sourceAgentId: sourceAgentId.trim() || null,
        rationale: rationale.trim() || null,
        payload,
      });
      setSubmitOk(`Submitted as #${created.id} — awaiting admin approval.`);
      setTargetPath('');
      setSourceAgentId('');
      setRationale('');
      setPayload('');
      load();
    } catch (e) {
      setSubmitErr(String(e));
    }
  }

  return (
    <>
      <div style={S.page}>
        <div style={S.header}>
          <div style={S.h1}>Contributions</div>
          <div style={S.sub}>Nominate a skill, sub-agent, or memory snippet for the shared workspace.</div>
        </div>

        <form onSubmit={onSubmit} style={S.panel}>
          <div style={S.panelTitle}>Submit a new contribution</div>
          <div style={S.row}>
            <div style={{ flex: 1 }}>
              <span style={S.label}>Type</span>
              <select style={{ ...S.input, padding: '5px 8px' }} value={targetType} onChange={e => setTargetType(e.target.value)}>
                {TYPES.map(t => <option key={t} value={t}>{t}</option>)}
              </select>
            </div>
            <div style={{ flex: 2 }}>
              <span style={S.label}>Target path</span>
              <input
                style={S.input}
                value={targetPath}
                onChange={e => setTargetPath(e.target.value)}
                placeholder={
                  targetType === 'skill' ? 'e.g. cohort-builder' :
                  targetType === 'subagent' ? 'e.g. report-writer.md' :
                  'e.g. 2026-05-22-cohort-rules.md'
                }
              />
            </div>
            <div style={{ flex: 1 }}>
              <span style={S.label}>Source agent id (optional)</span>
              <input style={S.input} value={sourceAgentId} onChange={e => setSourceAgentId(e.target.value)} placeholder="data-agent" />
            </div>
          </div>
          <div style={{ marginBottom: 8 }}>
            <span style={S.label}>Rationale for the reviewing admin (optional)</span>
            <input style={S.input} value={rationale} onChange={e => setRationale(e.target.value)} />
          </div>
          <div style={{ marginBottom: 8 }}>
            <span style={S.label}>Payload (verbatim file contents at nomination time)</span>
            <textarea style={S.textarea} value={payload} onChange={e => setPayload(e.target.value)} />
          </div>
          <button type="submit" style={S.btn}>Submit</button>
          {submitErr && <div style={{ ...S.err, marginTop: 8 }}>{submitErr}</div>}
          {submitOk && <div style={{ ...S.ok, marginTop: 8 }}>{submitOk}</div>}
        </form>

        <div style={S.panel}>
          <div style={S.panelTitle}>My submissions</div>
          {loading && <div style={S.sub}>Loading…</div>}
          {err && <div style={S.err}>{err}</div>}
          {!loading && !err && items.length === 0 && (
            <div style={S.sub}>You haven't submitted any contributions yet.</div>
          )}
          {!loading && items.length > 0 && (
            <table style={S.table}>
              <thead>
                <tr>
                  <th style={S.th}>#</th>
                  <th style={S.th}>Status</th>
                  <th style={S.th}>Type</th>
                  <th style={S.th}>Path</th>
                  <th style={S.th}>Submitted</th>
                  <th style={S.th}>Reviewer note</th>
                </tr>
              </thead>
              <tbody>
                {items.map(c => (
                  <tr key={c.id}>
                    <td style={S.td}>{c.id}</td>
                    <td style={S.td}><span style={badgeStyle(c.status)}>{c.status}</span></td>
                    <td style={S.td}>{c.targetType}</td>
                    <td style={S.td}><code>{c.targetPath}</code></td>
                    <td style={S.td}>{new Date(c.createdAt).toLocaleString()}</td>
                    <td style={S.td}>{c.reviewerNote || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </>
  );
}
