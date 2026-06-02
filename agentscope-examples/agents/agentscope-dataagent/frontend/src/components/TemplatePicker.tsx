import React, { useEffect, useState } from 'react';
import { TemplateSummary, listTemplates } from '../api/templates';

interface Props {
  selected: string | null;
  onSelect: (id: string) => void;
}

const S: Record<string, React.CSSProperties> = {
  list: { display: 'flex', flexDirection: 'column', gap: 12 },
  card: {
    border: '1px solid #e2e8f0', background: '#ffffff', borderRadius: 12,
    padding: '16px 18px', cursor: 'pointer',
    transition: 'border-color 0.15s ease, background 0.15s ease, box-shadow 0.15s ease',
  },
  cardActive: {
    border: '1px solid #6366f1', background: '#eef2ff',
    boxShadow: '0 0 0 3px rgba(99,102,241,0.12)',
  },
  head: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8 },
  name: { fontSize: '1rem', fontWeight: 600, color: '#0f172a' },
  tag: {
    padding: '2px 9px', borderRadius: 999, fontSize: '0.74rem', fontWeight: 500,
    background: '#f1f5f9', color: '#475569', border: '1px solid #e2e8f0',
  },
  desc: { fontSize: '0.9rem', color: '#64748b', marginBottom: 10, lineHeight: 1.5 },
  preview: {
    background: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: 8,
    padding: '10px 12px', color: '#475569', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.82rem', whiteSpace: 'pre-wrap', maxHeight: 140, overflow: 'hidden', lineHeight: 1.5,
  },
  err: { color: '#dc2626', fontSize: '0.9rem' },
};

export default function TemplatePicker({ selected, onSelect }: Props) {
  const [list, setList] = useState<TemplateSummary[]>([]);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    listTemplates()
      .then(setList)
      .catch(e => setErr(e instanceof Error ? e.message : 'Failed'));
  }, []);

  if (err) return <div style={S.err}>{err}</div>;
  if (list.length === 0) return <div style={{ color: '#94a3b8', fontSize: '0.82rem' }}>No templates available.</div>;

  return (
    <div style={S.list}>
      {list.map(t => {
        const active = selected === t.id;
        return (
          <div
            key={t.id}
            style={{ ...S.card, ...(active ? S.cardActive : {}) }}
            onClick={() => onSelect(t.id)}
          >
            <div style={S.head}>
              <span style={S.name}>{t.name}</span>
              {t.tags.map(tag => <span key={tag} style={S.tag}>{tag}</span>)}
            </div>
            <div style={S.desc}>{t.description}</div>
            {t.agentsMdPreview && (
              <div style={S.preview}>{t.agentsMdPreview.slice(0, 380)}</div>
            )}
          </div>
        );
      })}
    </div>
  );
}
