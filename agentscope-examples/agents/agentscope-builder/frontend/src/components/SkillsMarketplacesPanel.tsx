import React, { useCallback, useEffect, useState } from 'react';
import {
  MarketplaceSummary,
  MarketplaceWriteRequest,
  MarketSkillBrief,
  MarketSkillDetail,
  createMarketplace,
  deleteMarketplace,
  getMarketplaceSkill,
  listMarketplaces,
  listMarketplaceSkills,
  updateMarketplace,
} from '../api/marketplaces';
import { installFromMarketplace } from '../api/skills';
import MarketplaceEditorDialog from './MarketplaceEditorDialog';

interface Props {
  /** When omitted, install buttons are hidden — used by the standalone /marketplaces page. */
  agentId?: string;
  onInstalled?: () => void;
}

const containerStyle: React.CSSProperties = {
  padding: 18,
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
  height: '100%',
  overflowY: 'auto',
  background: '#f8fafc',
};
const cardStyle: React.CSSProperties = {
  background: '#ffffff',
  border: '1px solid #e2e8f0',
  borderRadius: 12,
  overflow: 'hidden',
};
const cardHeaderStyle = (open: boolean): React.CSSProperties => ({
  padding: '14px 18px',
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  cursor: 'pointer',
  background: open ? '#f8fafc' : '#ffffff',
  borderBottom: open ? '1px solid #e2e8f0' : 'none',
});
const typeBadge = (type: string): React.CSSProperties => {
  const colors: Record<string, { bg: string; fg: string; border: string }> = {
    git: { bg: '#fef3c7', fg: '#b45309', border: '#fde68a' },
    nacos: { bg: '#dcfce7', fg: '#15803d', border: '#bbf7d0' },
  };
  const c = colors[type.toLowerCase()] ?? { bg: '#f1f5f9', fg: '#475569', border: '#e2e8f0' };
  return {
    padding: '3px 10px',
    borderRadius: 999,
    fontSize: '0.72rem',
    fontWeight: 700,
    letterSpacing: '0.05em',
    textTransform: 'uppercase',
    background: c.bg,
    color: c.fg,
    border: `1px solid ${c.border}`,
  };
};
const skillRowStyle: React.CSSProperties = {
  padding: '10px 18px',
  borderTop: '1px solid #f1f5f9',
  display: 'flex',
  alignItems: 'center',
  gap: 12,
};
const installButtonStyle: React.CSSProperties = {
  padding: '6px 14px',
  borderRadius: 8,
  border: '1px solid #6366f1',
  background: '#6366f1',
  color: '#ffffff',
  fontSize: '0.82rem',
  fontWeight: 600,
  cursor: 'pointer',
};
const previewButtonStyle: React.CSSProperties = {
  padding: '6px 12px',
  borderRadius: 8,
  border: '1px solid #cbd5e1',
  background: '#ffffff',
  color: '#475569',
  fontSize: '0.82rem',
  fontWeight: 500,
  cursor: 'pointer',
};
const addButtonStyle: React.CSSProperties = {
  padding: '8px 14px',
  borderRadius: 8,
  border: '1px solid #6366f1',
  background: '#6366f1',
  color: '#ffffff',
  fontSize: '0.85rem',
  fontWeight: 600,
  cursor: 'pointer',
};
const iconButtonStyle: React.CSSProperties = {
  background: 'transparent',
  border: 'none',
  padding: 6,
  fontSize: '0.95rem',
  cursor: 'pointer',
  color: '#64748b',
  borderRadius: 6,
  lineHeight: 1,
};

interface MarketplaceState {
  loading: boolean;
  loaded: boolean;
  error: string | null;
  skills: MarketSkillBrief[];
}

function describeLocation(mp: MarketplaceSummary): string {
  const props = mp.properties ?? {};
  if (mp.type === 'git') {
    const remote = typeof props.remoteUrl === 'string' ? props.remoteUrl : '';
    const branch = typeof props.branch === 'string' && props.branch ? `#${props.branch}` : '';
    return remote ? `${remote}${branch}` : '(no remote URL)';
  }
  if (mp.type === 'nacos') {
    const addr = typeof props.serverAddr === 'string' ? props.serverAddr : '';
    const ns = typeof props.namespaceId === 'string' && props.namespaceId ? props.namespaceId : 'public';
    return addr ? `${addr} / ${ns}` : '(no server)';
  }
  return '';
}

export default function SkillsMarketplacesPanel({ agentId, onInstalled }: Props) {
  const [marketplaces, setMarketplaces] = useState<MarketplaceSummary[]>([]);
  const [topError, setTopError] = useState<string | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);
  const [mpState, setMpState] = useState<Record<string, MarketplaceState>>({});
  const [previewing, setPreviewing] = useState<MarketSkillDetail | null>(null);
  const [installing, setInstalling] = useState<string | null>(null);
  const [editorMode, setEditorMode] = useState<'create' | 'edit' | null>(null);
  const [editorInitial, setEditorInitial] = useState<MarketplaceSummary | undefined>(undefined);
  const [refreshTick, setRefreshTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    listMarketplaces()
      .then(list => {
        if (cancelled) return;
        setMarketplaces(list);
        setTopError(null);
        if (list.length > 0 && openId == null) setOpenId(list[0].id);
      })
      .catch(e => {
        if (!cancelled) setTopError((e as Error).message);
      });
    return () => {
      cancelled = true;
    };
    // openId intentionally excluded — we only want to set the default on initial load / explicit refresh
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshTick]);

  const ensureLoaded = useCallback(
    async (id: string, force = false) => {
      if (!force && (mpState[id]?.loaded || mpState[id]?.loading)) return;
      setMpState(prev => ({
        ...prev,
        [id]: { loading: true, loaded: false, error: null, skills: [] },
      }));
      try {
        const skills = await listMarketplaceSkills(id);
        setMpState(prev => ({
          ...prev,
          [id]: { loading: false, loaded: true, error: null, skills },
        }));
      } catch (e) {
        setMpState(prev => ({
          ...prev,
          [id]: {
            loading: false,
            loaded: true,
            error: (e as Error).message,
            skills: [],
          },
        }));
      }
    },
    [mpState],
  );

  useEffect(() => {
    if (openId != null) {
      ensureLoaded(openId);
    }
  }, [openId, ensureLoaded]);

  const handleInstall = async (marketplaceId: string, skillName: string) => {
    if (!agentId) return;
    setInstalling(`${marketplaceId}/${skillName}`);
    try {
      const result = await installFromMarketplace(agentId, { marketplaceId, skillName });
      if (result.status === 'conflict') {
        const overwrite = window.confirm(
          `A workspace skill named "${result.conflictName}" already exists. Overwrite?`,
        );
        if (!overwrite) return;
        const r2 = await installFromMarketplace(agentId, {
          marketplaceId,
          skillName,
          overwrite: true,
        });
        if (r2.status === 'installed') onInstalled?.();
        return;
      }
      onInstalled?.();
    } catch (e) {
      window.alert(`Install failed: ${(e as Error).message}`);
    } finally {
      setInstalling(null);
    }
  };

  const handlePreview = async (marketplaceId: string, skillName: string) => {
    try {
      const detail = await getMarketplaceSkill(marketplaceId, skillName);
      setPreviewing(detail);
    } catch (e) {
      window.alert(`Preview failed: ${(e as Error).message}`);
    }
  };

  const handleSave = async (req: MarketplaceWriteRequest) => {
    if (editorMode === 'edit') {
      await updateMarketplace(req.id, req);
    } else {
      await createMarketplace(req);
    }
    setEditorMode(null);
    setEditorInitial(undefined);
    // Force refresh of the saved entry's skill list on next open
    setMpState(prev => {
      const next = { ...prev };
      delete next[req.id];
      return next;
    });
    setOpenId(req.id);
    setRefreshTick(t => t + 1);
  };

  const handleDelete = async (id: string) => {
    if (!window.confirm(`Delete marketplace "${id}"? Installed skills are not affected.`)) {
      return;
    }
    try {
      await deleteMarketplace(id);
      if (openId === id) setOpenId(null);
      setMpState(prev => {
        const next = { ...prev };
        delete next[id];
        return next;
      });
      setRefreshTick(t => t + 1);
    } catch (e) {
      window.alert(`Delete failed: ${(e as Error).message}`);
    }
  };

  return (
    <div style={containerStyle}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
        }}
      >
        <div style={{ flex: 1, fontSize: '0.78rem', color: '#64748b' }}>
          Marketplaces are shared across all agents on this host.
        </div>
        <button
          onClick={() => {
            setEditorMode('create');
            setEditorInitial(undefined);
          }}
          style={addButtonStyle}
        >
          + Add Marketplace
        </button>
      </div>
      {topError && (
        <div
          style={{
            padding: 12,
            background: '#fef2f2',
            border: '1px solid #fecaca',
            borderRadius: 8,
            color: '#dc2626',
            fontSize: '0.85rem',
          }}
        >
          {topError}
        </div>
      )}
      {!topError && marketplaces.length === 0 && (
        <div
          style={{
            padding: 18,
            background: '#ffffff',
            border: '1px dashed #cbd5e1',
            borderRadius: 12,
            color: '#64748b',
            fontSize: '0.9rem',
          }}
        >
          <div style={{ fontWeight: 600, marginBottom: 6, color: '#0f172a' }}>
            No marketplaces configured.
          </div>
          Click <strong>+ Add Marketplace</strong> to register a git repository or Nacos namespace
          and start browsing its skills.
        </div>
      )}
      {marketplaces.map(mp => {
        const state = mpState[mp.id];
        const open = openId === mp.id;
        const location = describeLocation(mp);
        return (
          <div key={mp.id} style={cardStyle}>
            <div style={cardHeaderStyle(open)} onClick={() => setOpenId(open ? null : mp.id)}>
              <span style={typeBadge(mp.type)}>{mp.type}</span>
              <div style={{ minWidth: 0, flex: 1 }}>
                <div
                  style={{
                    fontSize: '0.9rem',
                    fontWeight: 600,
                    color: '#0f172a',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                  title={mp.id}
                >
                  {mp.id}
                </div>
                <div
                  style={{
                    fontSize: '0.75rem',
                    color: '#94a3b8',
                    marginTop: 2,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                  title={location}
                >
                  {location}
                  {state?.loaded && ` · ${state.skills.length} skills`}
                </div>
              </div>
              <button
                title="Edit"
                onClick={e => {
                  e.stopPropagation();
                  setEditorMode('edit');
                  setEditorInitial(mp);
                }}
                style={iconButtonStyle}
              >
                ⚙
              </button>
              <button
                title="Delete"
                onClick={e => {
                  e.stopPropagation();
                  handleDelete(mp.id);
                }}
                style={{ ...iconButtonStyle, color: '#dc2626' }}
              >
                🗑
              </button>
              <span style={{ color: '#94a3b8', fontSize: '0.85rem' }}>{open ? '▼' : '▶'}</span>
            </div>
            {open && (
              <div>
                {state?.loading && (
                  <div style={{ padding: 16, color: '#94a3b8', fontSize: '0.85rem' }}>
                    Loading skills…
                  </div>
                )}
                {state?.error && (
                  <div
                    style={{
                      padding: 12,
                      color: '#dc2626',
                      fontSize: '0.85rem',
                      background: '#fef2f2',
                    }}
                  >
                    {state.error}
                  </div>
                )}
                {state?.loaded && state.skills.length === 0 && !state.error && (
                  <div style={{ padding: 16, color: '#94a3b8', fontSize: '0.85rem' }}>
                    No skills found in this marketplace.
                  </div>
                )}
                {state?.skills.map(s => {
                  const key = `${mp.id}/${s.name}`;
                  return (
                    <div key={s.name} style={skillRowStyle}>
                      <div style={{ minWidth: 0, flex: 1 }}>
                        <div style={{ fontSize: '0.9rem', fontWeight: 600, color: '#0f172a' }}>
                          {s.name}
                          {s.version && (
                            <span
                              style={{
                                marginLeft: 8,
                                fontSize: '0.72rem',
                                color: '#94a3b8',
                                fontWeight: 500,
                              }}
                            >
                              v{s.version}
                            </span>
                          )}
                        </div>
                        {s.description && (
                          <div
                            style={{
                              fontSize: '0.8rem',
                              color: '#64748b',
                              marginTop: 4,
                              lineHeight: 1.4,
                            }}
                          >
                            {s.description}
                          </div>
                        )}
                      </div>
                      <button
                        onClick={() => handlePreview(mp.id, s.name)}
                        style={previewButtonStyle}
                      >
                        Preview
                      </button>
                      {agentId && (
                        <button
                          onClick={() => handleInstall(mp.id, s.name)}
                          disabled={installing === key}
                          style={{
                            ...installButtonStyle,
                            opacity: installing === key ? 0.6 : 1,
                            cursor: installing === key ? 'wait' : 'pointer',
                          }}
                        >
                          {installing === key ? 'Installing…' : 'Install'}
                        </button>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}
      {previewing && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(15,23,42,0.55)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 50,
          }}
          onClick={() => setPreviewing(null)}
        >
          <div
            style={{
              background: '#ffffff',
              borderRadius: 12,
              padding: 24,
              maxWidth: '720px',
              width: '90%',
              maxHeight: '80vh',
              overflowY: 'auto',
              boxShadow: '0 20px 60px rgba(15,23,42,0.25)',
            }}
            onClick={e => e.stopPropagation()}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                marginBottom: 16,
              }}
            >
              <h3 style={{ margin: 0, fontSize: '1.05rem', flex: 1 }}>{previewing.name}</h3>
              <button onClick={() => setPreviewing(null)} style={previewButtonStyle}>
                Close
              </button>
            </div>
            {previewing.description && (
              <div style={{ fontSize: '0.85rem', color: '#475569', marginBottom: 12 }}>
                {previewing.description}
              </div>
            )}
            <pre
              style={{
                background: '#0f172a',
                color: '#e2e8f0',
                padding: 14,
                borderRadius: 8,
                fontSize: '0.78rem',
                lineHeight: 1.5,
                overflow: 'auto',
                maxHeight: '50vh',
                whiteSpace: 'pre-wrap',
              }}
            >
              {previewing.markdown}
            </pre>
            {Object.keys(previewing.resources ?? {}).length > 0 && (
              <div style={{ marginTop: 14 }}>
                <div
                  style={{
                    fontSize: '0.75rem',
                    fontWeight: 700,
                    color: '#475569',
                    textTransform: 'uppercase',
                    letterSpacing: '0.05em',
                    marginBottom: 8,
                  }}
                >
                  Resources
                </div>
                {Object.keys(previewing.resources).map(k => (
                  <div
                    key={k}
                    style={{
                      fontFamily: 'ui-monospace, monospace',
                      fontSize: '0.78rem',
                      color: '#334155',
                      padding: '2px 0',
                    }}
                  >
                    {k}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
      {editorMode && (
        <MarketplaceEditorDialog
          mode={editorMode}
          initial={editorInitial}
          onSave={handleSave}
          onCancel={() => {
            setEditorMode(null);
            setEditorInitial(undefined);
          }}
        />
      )}
    </div>
  );
}
