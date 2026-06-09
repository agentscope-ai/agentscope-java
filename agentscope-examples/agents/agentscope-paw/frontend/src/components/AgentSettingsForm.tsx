import React, { useEffect, useState } from 'react';
import { AgentDefinition, updateAgent, deleteAgent } from '../api/agents';
import { useNavigate } from 'react-router-dom';

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

export default function AgentSettingsForm({ agent }: { agent: AgentDefinition }) {
  const navigate = useNavigate();
  const isBuiltin = agent.builtin;
  const readOnly = isBuiltin;

  const [name, setName] = useState(agent.name);
  const [description, setDescription] = useState(agent.description ?? '');
  const [sysPrompt, setSysPrompt] = useState(agent.sysPrompt ?? '');
  const [maxIters, setMaxIters] = useState<string>(String(agent.maxIters ?? 12));
  const [saving, setSaving] = useState(false);
  const [ok, setOk] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    setName(agent.name);
    setDescription(agent.description ?? '');
    setSysPrompt(agent.sysPrompt ?? '');
    setMaxIters(String(agent.maxIters ?? 12));
  }, [agent.id]);

  async function handleSave() {
    setOk(false);
    setErr(null);
    setSaving(true);
    try {
      const iters = Number.parseInt(maxIters, 10);
      await updateAgent(agent.id, {
        name: name.trim() || agent.id,
        description: description.trim() || undefined,
        sysPrompt: sysPrompt || undefined,
        maxIters: Number.isFinite(iters) && iters > 0 ? iters : undefined,
      });
      setOk(true);
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

  return (
    <div style={S.page}>
      {isBuiltin && (
        <div style={S.banner}>
          Built-in agents are read-only from the UI. Edit <code>~/.agentscope/agentscope.json</code> to change them.
        </div>
      )}

      <div style={S.card}>
        <span style={S.cardLabel}>Identity</span>

        <div style={S.row}>
          <label style={S.fieldLabel}>Agent ID</label>
          <div style={S.meta}>{agent.id}</div>
        </div>

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
      </div>

      <div style={S.card}>
        <span style={S.cardLabel}>Behavior</span>

        <div style={S.row}>
          <label style={S.fieldLabel}>System prompt</label>
          <textarea
            style={S.textarea}
            value={sysPrompt}
            onChange={e => setSysPrompt(e.target.value)}
            disabled={readOnly}
            placeholder="High-level instructions. Workspace AGENTS.md still takes precedence at runtime."
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

      {!isBuiltin && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap' }}>
          <button style={S.saveBtn} onClick={handleSave} disabled={saving}>
            {saving ? 'Saving…' : 'Save changes'}
          </button>
          <button style={S.dangerBtn} onClick={handleDelete}>Delete agent</button>
        </div>
      )}
      {ok && <p style={S.success}>Saved.</p>}
      {err && <p style={S.error}>{err}</p>}

      <div style={{ ...S.card, marginTop: 24 }}>
        <span style={S.cardLabel}>Metadata</span>
        <div style={S.row}>
          <label style={S.fieldLabel}>Kind</label>
          <div style={S.meta}>{isBuiltin ? 'built-in' : 'custom'}</div>
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
