import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  WorkspaceSkillInfo,
  WorkspaceSkillDetail,
  listWorkspaceSkills,
  getWorkspaceSkill,
  upsertWorkspaceSkill,
  deleteWorkspaceSkill,
} from '../api/skills';

interface Props {
  agentId: string;
  refreshKey: number;
  onChange: () => void;
  onRequestBrowse: () => void;
}

const containerStyle: React.CSSProperties = {
  display: 'flex',
  height: '100%',
  minHeight: 0,
};
const sidebarStyle: React.CSSProperties = {
  width: 320,
  flexShrink: 0,
  borderRight: '1px solid #e2e8f0',
  background: '#ffffff',
  display: 'flex',
  flexDirection: 'column',
};
const sidebarHeaderStyle: React.CSSProperties = {
  padding: '14px 16px',
  borderBottom: '1px solid #f1f5f9',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 8,
};
const itemStyle = (active: boolean): React.CSSProperties => ({
  padding: '10px 16px',
  borderBottom: '1px solid #f1f5f9',
  cursor: 'pointer',
  background: active ? '#eef2ff' : 'transparent',
  borderLeft: active ? '3px solid #6366f1' : '3px solid transparent',
});
const detailStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  minWidth: 0,
  background: '#f8fafc',
};
const toolbarStyle: React.CSSProperties = {
  padding: '12px 18px',
  borderBottom: '1px solid #e2e8f0',
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  background: '#ffffff',
};
const buttonStyle: React.CSSProperties = {
  padding: '6px 14px',
  borderRadius: 8,
  border: '1px solid #c7d2fe',
  background: '#eef2ff',
  color: '#4338ca',
  fontSize: '0.85rem',
  fontWeight: 600,
  cursor: 'pointer',
};
const dangerButtonStyle: React.CSSProperties = {
  ...buttonStyle,
  border: '1px solid #fecaca',
  background: '#fef2f2',
  color: '#dc2626',
};
const editorStyle: React.CSSProperties = {
  flex: 1,
  padding: 16,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: '0.85rem',
  border: 'none',
  outline: 'none',
  resize: 'none',
  background: '#ffffff',
  color: '#0f172a',
};
const sourceBadgeStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 4,
  padding: '1px 7px',
  borderRadius: 999,
  fontSize: '0.68rem',
  fontWeight: 700,
  letterSpacing: '0.04em',
  textTransform: 'uppercase',
  background: '#fef3c7',
  color: '#b45309',
  border: '1px solid #fde68a',
};
// Custom (workspace-authored) skills get a distinct cooler badge so users can tell at a glance
// which entries are local edits vs. marketplace pulls — same visual rhythm as marketplace badges,
// just a different palette to avoid implying provenance.
const customBadgeStyle: React.CSSProperties = {
  ...sourceBadgeStyle,
  background: '#e0e7ff',
  color: '#4338ca',
  border: '1px solid #c7d2fe',
};

export default function SkillsWorkspacePanel({
  agentId,
  refreshKey,
  onChange,
  onRequestBrowse,
}: Props) {
  const [allSkills, setAllSkills] = useState<WorkspaceSkillInfo[]>([]);
  const [selectedDir, setSelectedDir] = useState<string | null>(null);
  const [detail, setDetail] = useState<WorkspaceSkillDetail | null>(null);
  const [draft, setDraft] = useState<string>('');
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // The skills page surfaces every workspace skill — both marketplace-installed and
  // workspace-authored ("custom") entries — so a refresh accurately reflects on-disk state.
  // Each row carries an origin badge so users can tell at a glance which entries are local
  // edits and which were pulled from a marketplace.
  const skills = allSkills;

  const refreshList = useCallback(async () => {
    try {
      const list = await listWorkspaceSkills(agentId);
      setAllSkills(list);
      if (selectedDir && !list.some(s => s.dirName === selectedDir)) {
        setSelectedDir(list.length > 0 ? list[0].dirName : null);
      } else if (!selectedDir && list.length > 0) {
        setSelectedDir(list[0].dirName);
      }
    } catch (e) {
      setError((e as Error).message);
    }
  }, [agentId, selectedDir]);

  useEffect(() => {
    refreshList();
  }, [agentId, refreshKey]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!selectedDir) {
      setDetail(null);
      setDraft('');
      setDirty(false);
      return;
    }
    let cancelled = false;
    getWorkspaceSkill(agentId, selectedDir)
      .then(d => {
        if (cancelled) return;
        setDetail(d);
        setDraft(d.markdown);
        setDirty(false);
      })
      .catch(e => {
        if (!cancelled) setError((e as Error).message);
      });
    return () => {
      cancelled = true;
    };
  }, [agentId, selectedDir]);

  const handleSave = async () => {
    if (!selectedDir || !dirty) return;
    setBusy(true);
    setError(null);
    try {
      await upsertWorkspaceSkill(agentId, selectedDir, draft);
      setDirty(false);
      await refreshList();
      onChange();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const handleDelete = async () => {
    if (!selectedDir) return;
    // Phrase the confirmation by origin so users understand the local impact: marketplace skills
    // get the "Uninstall" language they're used to, custom skills are framed as a Delete.
    const isMarketplace = selectedSkill?.origin === 'marketplace';
    const verb = isMarketplace ? 'Uninstall' : 'Delete';
    if (!window.confirm(`${verb} skill "${selectedDir}"? This removes the entire directory.`)) return;
    setBusy(true);
    setError(null);
    try {
      await deleteWorkspaceSkill(agentId, selectedDir);
      setSelectedDir(null);
      await refreshList();
      onChange();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const resourceList = useMemo(() => {
    if (!detail || !detail.resources) return [];
    return Object.keys(detail.resources).sort();
  }, [detail]);

  const selectedSkill = useMemo(
    () => skills.find(s => s.dirName === selectedDir) ?? null,
    [skills, selectedDir],
  );

  return (
    <div style={containerStyle}>
      <div style={sidebarStyle}>
        <div style={sidebarHeaderStyle}>
          <span style={{ fontSize: '0.85rem', fontWeight: 600, color: '#0f172a' }}>
            Installed Skills ({skills.length})
          </span>
          <button onClick={onRequestBrowse} style={buttonStyle} disabled={busy}>
            + Install
          </button>
        </div>
        <div style={{ flex: 1, overflowY: 'auto' }}>
          {skills.length === 0 ? (
            <div style={{ padding: 16, color: '#94a3b8', fontSize: '0.85rem' }}>
              <div style={{ fontWeight: 600, color: '#475569', marginBottom: 6 }}>
                No skills yet.
              </div>
              Click <b>+ Install</b> to pull from a marketplace, or author a skill manually under
              <code> workspace/skills/&lt;name&gt;/SKILL.md </code>via the Workspace file tree —
              both show up here.
            </div>
          ) : (
            skills.map(s => (
              <div
                key={s.dirName}
                style={itemStyle(selectedDir === s.dirName)}
                onClick={() => setSelectedDir(s.dirName)}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <div
                    style={{
                      fontSize: '0.9rem',
                      fontWeight: 600,
                      color: '#0f172a',
                      flex: 1,
                      minWidth: 0,
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {s.name || s.dirName}
                  </div>
                  {s.marketplace?.repoType ? (
                    <span style={sourceBadgeStyle} title={s.marketplace.repoLocation}>
                      {s.marketplace.repoType}
                    </span>
                  ) : (
                    // Custom (workspace-authored) skills get a "custom" badge so they aren't
                    // visually mistaken for an unbadged marketplace entry — the same visual slot
                    // is reused so list items stay aligned regardless of origin.
                    <span style={customBadgeStyle} title="Authored in this workspace">
                      custom
                    </span>
                  )}
                </div>
                {s.description && (
                  <div
                    style={{
                      fontSize: '0.78rem',
                      color: '#64748b',
                      marginTop: 4,
                      lineHeight: 1.4,
                      display: '-webkit-box',
                      WebkitLineClamp: 2,
                      WebkitBoxOrient: 'vertical',
                      overflow: 'hidden',
                    }}
                  >
                    {s.description}
                  </div>
                )}
                <div
                  style={{
                    marginTop: 6,
                    display: 'flex',
                    gap: 8,
                    fontSize: '0.72rem',
                    color: '#94a3b8',
                  }}
                >
                  <span>{(s.sizeBytes / 1024).toFixed(1)} KB</span>
                  {s.resourceCount > 0 && <span>{s.resourceCount} resources</span>}
                  {s.hasReferences && <span>references/</span>}
                  {s.hasScripts && <span>scripts/</span>}
                </div>
              </div>
            ))
          )}
        </div>
      </div>
      <div style={detailStyle}>
        {!selectedDir ? (
          <div style={{ padding: 24, color: '#94a3b8' }}>Select a skill to view or edit.</div>
        ) : !detail ? (
          <div style={{ padding: 24, color: '#94a3b8' }}>Loading…</div>
        ) : (
          <>
            <div style={toolbarStyle}>
              <span style={{ fontSize: '0.95rem', fontWeight: 600 }}>{selectedDir}/SKILL.md</span>
              {selectedSkill?.marketplace ? (
                <span
                  style={{ fontSize: '0.78rem', color: '#64748b' }}
                  title={`Installed from ${selectedSkill.marketplace.repoType}: ${selectedSkill.marketplace.repoLocation}`}
                >
                  · from <b>{selectedSkill.marketplace.repoType}</b>
                  {selectedSkill.marketplace.originalName &&
                    selectedSkill.marketplace.originalName !== selectedDir &&
                    ` (orig: ${selectedSkill.marketplace.originalName})`}
                </span>
              ) : (
                selectedSkill && (
                  <span
                    style={{ fontSize: '0.78rem', color: '#64748b' }}
                    title="Authored directly in this workspace (no marketplace lineage)"
                  >
                    · <b>custom</b>
                  </span>
                )
              )}
              <span style={{ flex: 1 }} />
              {dirty && <span style={{ fontSize: '0.78rem', color: '#d97706' }}>unsaved</span>}
              <button
                onClick={handleSave}
                style={{
                  ...buttonStyle,
                  background: dirty ? '#6366f1' : '#e0e7ff',
                  color: dirty ? '#ffffff' : '#6366f1',
                  border: dirty ? '1px solid #6366f1' : '1px solid #c7d2fe',
                }}
                disabled={!dirty || busy}
              >
                Save
              </button>
              <button onClick={handleDelete} style={dangerButtonStyle} disabled={busy}>
                {selectedSkill?.origin === 'marketplace' ? 'Uninstall' : 'Delete'}
              </button>
            </div>
            <textarea
              value={draft}
              onChange={e => {
                setDraft(e.target.value);
                setDirty(e.target.value !== detail.markdown);
              }}
              style={editorStyle}
              spellCheck={false}
            />
            {resourceList.length > 0 && (
              <div
                style={{
                  borderTop: '1px solid #e2e8f0',
                  background: '#ffffff',
                  padding: '10px 16px',
                  maxHeight: 140,
                  overflowY: 'auto',
                }}
              >
                <div
                  style={{
                    fontSize: '0.75rem',
                    fontWeight: 600,
                    color: '#475569',
                    marginBottom: 6,
                    textTransform: 'uppercase',
                    letterSpacing: '0.04em',
                  }}
                >
                  Resources ({resourceList.length})
                </div>
                {resourceList.map(r => (
                  <div
                    key={r}
                    style={{
                      fontFamily: 'ui-monospace, monospace',
                      fontSize: '0.8rem',
                      color: '#334155',
                      padding: '2px 0',
                    }}
                  >
                    {r}
                  </div>
                ))}
              </div>
            )}
          </>
        )}
        {error && (
          <div
            style={{
              padding: '8px 16px',
              color: '#dc2626',
              fontSize: '0.85rem',
              background: '#fef2f2',
              borderTop: '1px solid #fecaca',
            }}
          >
            {error}
          </div>
        )}
      </div>
    </div>
  );
}
