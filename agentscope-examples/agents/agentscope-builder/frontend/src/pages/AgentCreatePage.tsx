import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AgentCreateRequest, AgentDraft, createAgent } from '../api/agents';
import { me } from '../api/auth';
import TemplatePicker from '../components/TemplatePicker';
import AiDescribeForm from '../components/AiDescribeForm';

type Mode = 'blank' | 'template' | 'ai';

const S: Record<string, React.CSSProperties> = {
  page: { padding: '36px 40px', maxWidth: 880 },
  title: { margin: '0 0 24px', fontSize: '1.6rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.02em' },
  tabs: { display: 'flex', gap: 4, borderBottom: '1px solid #e2e8f0', marginBottom: 28 },
  tab: {
    background: 'transparent', border: 'none', borderBottom: '2px solid transparent',
    color: '#64748b', padding: '12px 20px', fontSize: '0.95rem', cursor: 'pointer',
    fontWeight: 500, marginBottom: -1,
  },
  tabActive: { color: '#0f172a', borderBottom: '2px solid #6366f1', fontWeight: 600 },
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
  aiTabDisabled: { opacity: 0.5, cursor: 'not-allowed' },
};

export default function AgentCreatePage() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<Mode>('blank');
  const [aiAvailable, setAiAvailable] = useState(false);

  // shared inputs
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [workspacePath, setWorkspacePath] = useState('');
  const [sysPrompt, setSysPrompt] = useState('');

  // template
  const [templateId, setTemplateId] = useState<string | null>(null);

  // ai
  const [draft, setDraft] = useState<AgentDraft | null>(null);

  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    me().then(m => setAiAvailable(!!m.aiAvailable)).catch(() => undefined);
  }, []);

  function handleDraftChange(d: AgentDraft | null) {
    setDraft(d);
    if (d) {
      if (!name) setName(d.name);
      if (!description && d.description) setDescription(d.description);
      if (!sysPrompt && d.sysPrompt) setSysPrompt(d.sysPrompt);
    }
  }

  const canSubmit = (() => {
    if (submitting) return false;
    if (mode === 'template') return !!templateId && !!name.trim();
    if (mode === 'ai') return !!draft && !!name.trim();
    return !!name.trim();
  })();

  async function handleSubmit() {
    setErr(null);
    setSubmitting(true);
    try {
      const req: AgentCreateRequest = {
        name: name.trim(),
        description: description.trim() || undefined,
        sysPrompt: sysPrompt.trim() || undefined,
        workspacePath: workspacePath.trim() || undefined,
        templateId: mode === 'template' && templateId ? templateId : undefined,
        aiDraft: mode === 'ai' && draft ? draft : undefined,
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

      <div style={S.tabs}>
        <button
          style={{ ...S.tab, ...(mode === 'blank' ? S.tabActive : {}) }}
          onClick={() => setMode('blank')}
        >
          Blank
        </button>
        <button
          style={{ ...S.tab, ...(mode === 'template' ? S.tabActive : {}) }}
          onClick={() => setMode('template')}
        >
          From template
        </button>
        <button
          style={{
            ...S.tab,
            ...(mode === 'ai' ? S.tabActive : {}),
            ...(aiAvailable ? {} : S.aiTabDisabled),
          }}
          onClick={() => aiAvailable && setMode('ai')}
          disabled={!aiAvailable}
          title={aiAvailable ? '' : 'Configure DashScope to enable'}
        >
          Describe with AI
        </button>
      </div>

      <div style={S.card}>
        {mode === 'blank' && (
          <div style={S.tip}>Start from a clean scaffold — AGENTS.md, tools.json, an example skill.</div>
        )}
        {mode === 'template' && (
          <>
            <div style={S.tip}>Pick a starter template; you can still customize identity below.</div>
            <div style={{ marginBottom: 24 }}>
              <TemplatePicker selected={templateId} onSelect={setTemplateId} />
            </div>
          </>
        )}
        {mode === 'ai' && (
          <>
            <div style={S.tip}>Describe what you want. The model proposes a name, sysPrompt, tools and skills.</div>
            <div style={{ marginBottom: 24 }}>
              <AiDescribeForm available={aiAvailable} draft={draft} onDraft={handleDraftChange} />
            </div>
          </>
        )}

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
            placeholder="~/.agentscope/builder/workspace/users/<userId>/agents/<agentId>"
          />
          <div style={S.hint}>
            Leave blank to use the default per-user path at{' '}
            <code>~/.agentscope/builder/workspace/users/&lt;userId&gt;/agents/&lt;agentId&gt;</code>.
            Absolute paths are used as-is. Set at creation only.
          </div>
        </div>

        {mode !== 'template' && (
          <div style={S.row}>
            <label style={S.fieldLabel}>System prompt</label>
            <textarea
              style={S.textarea}
              value={sysPrompt}
              onChange={e => setSysPrompt(e.target.value)}
              placeholder="High-level behavior. You can also edit AGENTS.md after creation."
            />
          </div>
        )}

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
