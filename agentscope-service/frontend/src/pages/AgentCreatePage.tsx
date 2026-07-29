import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AgentCreateRequest, createAgent } from '../api/agents';

const S: Record<string, React.CSSProperties> = {
  page: { padding: '36px 40px', maxWidth: 880 },
  title: { margin: '0 0 24px', fontSize: '1.6rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.02em' },
  card: {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 14,
    padding: '28px 30px',
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
  fieldLabel: { display: 'block', fontSize: '0.88rem', color: '#475569', marginBottom: 8, fontWeight: 500 },
  input: {
    width: '100%', boxSizing: 'border-box', padding: '11px 14px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 9,
    color: '#0f172a', fontSize: '0.95rem',
  },
  textarea: {
    width: '100%', boxSizing: 'border-box', padding: '12px 14px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 9,
    color: '#0f172a', fontSize: '0.95rem',
    minHeight: 130, resize: 'vertical', lineHeight: 1.55,
  },
  row: { marginBottom: 20 },
  actions: { marginTop: 24, display: 'flex', gap: 12, alignItems: 'center' },
  btn: {
    padding: '11px 24px',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff',
    border: 'none', borderRadius: 9, cursor: 'pointer', fontSize: '0.95rem', fontWeight: 600,
    boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  btnDisabled: { background: '#e2e8f0', color: '#94a3b8', cursor: 'not-allowed', boxShadow: 'none' },
  cancel: {
    padding: '11px 20px', background: '#ffffff', color: '#475569',
    border: '1px solid #cbd5e1', borderRadius: 9, cursor: 'pointer', fontSize: '0.92rem', fontWeight: 500,
  },
  err: { color: '#dc2626', fontSize: '0.88rem' },
  hint: { fontSize: '0.8rem', color: '#94a3b8', marginTop: 6, lineHeight: 1.5 },
  tip: { fontSize: '0.88rem', color: '#64748b', marginBottom: 20, lineHeight: 1.55 },
};

export default function AgentCreatePage() {
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [workspacePath, setWorkspacePath] = useState('');
  const [sysPrompt, setSysPrompt] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const canSubmit = !submitting && !!name.trim();

  async function handleSubmit() {
    setErr(null);
    setSubmitting(true);
    try {
      const req: AgentCreateRequest = {
        name: name.trim(),
        description: description.trim() || undefined,
        system: sysPrompt.trim() || undefined,
        workspacePath: workspacePath.trim() || undefined,
      };
      const created = await createAgent(req);
      navigate(`/agents/${encodeURIComponent(created.id)}/workspace`, { replace: true });
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to create');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={S.page}>
      <h1 style={S.title}>New agent</h1>
      <div style={S.card}>
        <div style={S.tip}>Start from a blank agent — edit workspace, skills and tools after creation.</div>

        <div style={S.row}>
          <label style={S.fieldLabel}>Name *</label>
          <input
            style={S.input}
            value={name}
            onChange={e => setName(e.target.value)}
            placeholder="e.g. Research Assistant"
          />
        </div>

        <div style={S.row}>
          <label style={S.fieldLabel}>Description</label>
          <input
            style={S.input}
            value={description}
            onChange={e => setDescription(e.target.value)}
            placeholder="Short summary shown on cards and tabs"
          />
        </div>

        <div style={S.row}>
          <label style={S.fieldLabel}>Workspace path</label>
          <input
            style={S.input}
            value={workspacePath}
            onChange={e => setWorkspacePath(e.target.value)}
            placeholder="leave blank for default under aistiod workspace root"
          />
          <div style={S.hint}>
            Leave blank to use the control-plane default path. Absolute paths are used as-is.
          </div>
        </div>

        <div style={S.row}>
          <label style={S.fieldLabel}>System prompt</label>
          <textarea
            style={S.textarea}
            value={sysPrompt}
            onChange={e => setSysPrompt(e.target.value)}
            placeholder="High-level behavior. You can also edit AGENTS.md after creation."
          />
        </div>

        <div style={S.actions}>
          <button
            style={{ ...S.btn, ...(canSubmit ? {} : S.btnDisabled) }}
            onClick={handleSubmit}
            disabled={!canSubmit}
          >
            {submitting ? 'Creating…' : 'Create agent'}
          </button>
          <button style={S.cancel} onClick={() => navigate('/agents')}>Cancel</button>
          {err && <span style={S.err}>{err}</span>}
        </div>
      </div>
    </div>
  );
}
