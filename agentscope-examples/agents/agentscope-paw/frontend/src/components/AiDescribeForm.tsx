import React, { useState } from 'react';
import { AgentDraft, draftAgentWithAi } from '../api/agents';

interface Props {
  available: boolean;
  draft: AgentDraft | null;
  onDraft: (d: AgentDraft | null) => void;
}

const S: Record<string, React.CSSProperties> = {
  root: { display: 'flex', flexDirection: 'column', gap: 14 },
  textarea: {
    width: '100%', boxSizing: 'border-box', padding: '12px 14px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 9,
    color: '#0f172a', fontSize: '0.95rem', lineHeight: 1.55,
    minHeight: 130, resize: 'vertical',
  },
  btn: {
    alignSelf: 'flex-start', padding: '10px 22px',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff',
    border: 'none', borderRadius: 9, cursor: 'pointer', fontSize: '0.92rem', fontWeight: 600,
    boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  btnDisabled: { background: '#e2e8f0', color: '#94a3b8', cursor: 'not-allowed', boxShadow: 'none' },
  err: { color: '#dc2626', fontSize: '0.9rem' },
  draftCard: {
    background: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: 12,
    padding: '18px 20px',
  },
  draftLabel: {
    fontSize: '0.78rem', color: '#94a3b8', fontWeight: 700,
    textTransform: 'uppercase', letterSpacing: '0.1em', marginBottom: 10, display: 'block',
  },
  draftField: { fontSize: '0.95rem', color: '#0f172a', marginBottom: 10, lineHeight: 1.55 },
  bullet: { fontSize: '0.88rem', color: '#475569', lineHeight: 1.55 },
  unavailable: {
    padding: '16px 20px', background: '#f1f5f9', borderRadius: 10,
    color: '#475569', fontSize: '0.92rem', border: '1px solid #cbd5e1',
  },
};

export default function AiDescribeForm({ available, draft, onDraft }: Props) {
  const [desc, setDesc] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  if (!available) {
    return (
      <div style={S.unavailable}>
        AI drafting is not available. Configure a model (e.g. <code>DASHSCOPE_API_KEY</code>) and restart.
      </div>
    );
  }

  async function handleDraft() {
    setBusy(true); setErr(null);
    try {
      const d = await draftAgentWithAi(desc.trim());
      onDraft(d);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Drafting failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={S.root}>
      <textarea
        style={S.textarea}
        value={desc}
        onChange={e => setDesc(e.target.value)}
        placeholder="Describe what you want this agent to do. e.g. 'A research assistant that reads our /docs folder and summarizes the answer to a question with citations.'"
      />
      <button
        style={{ ...S.btn, ...((busy || !desc.trim()) ? S.btnDisabled : {}) }}
        onClick={handleDraft}
        disabled={busy || !desc.trim()}
      >
        {busy ? 'Drafting…' : draft ? 'Redraft' : 'Draft with AI'}
      </button>
      {err && <div style={S.err}>{err}</div>}

      {draft && (
        <div style={S.draftCard}>
          <span style={S.draftLabel}>Suggested agent</span>
          <div style={S.draftField}><strong>{draft.name}</strong></div>
          {draft.description && <div style={S.draftField}>{draft.description}</div>}
          {draft.sysPrompt && (
            <>
              <span style={S.draftLabel}>System prompt</span>
              <div style={{ ...S.draftField, whiteSpace: 'pre-wrap', fontSize: '0.9rem', color: '#334155' }}>
                {draft.sysPrompt}
              </div>
            </>
          )}
          {draft.suggestedTools && draft.suggestedTools.length > 0 && (
            <>
              <span style={S.draftLabel}>Tools</span>
              <div style={S.bullet}>{draft.suggestedTools.join(', ')}</div>
            </>
          )}
          {draft.suggestedSkills && draft.suggestedSkills.length > 0 && (
            <>
              <span style={S.draftLabel}>Skills</span>
              {draft.suggestedSkills.map(s => (
                <div key={s.name} style={S.bullet}>• {s.name}</div>
              ))}
            </>
          )}
          {draft.suggestedSubagents && draft.suggestedSubagents.length > 0 && (
            <>
              <span style={S.draftLabel}>Subagents</span>
              {draft.suggestedSubagents.map(s => (
                <div key={s.name} style={S.bullet}>• {s.name}</div>
              ))}
            </>
          )}
        </div>
      )}
    </div>
  );
}
