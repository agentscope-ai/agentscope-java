import React, { useCallback, useEffect, useState } from 'react';
import {
  AgentCreateRequest,
  AgentDefinition,
  createAgent,
  deleteAgent,
  listAgents,
  updateAgent,
} from '../../api/agents';
import { listSessions, SessionView } from '../../api/sessions';

interface Props {
  selectedAgentId: string | null;
  onSelectAgent: (agentId: string | null, sessionKey?: string) => void;
  userId: string;
  refreshTick?: number; // increment to force refresh
}

// ── Styles ──────────────────────────────────────────────────────────

const s: Record<string, React.CSSProperties> = {
  sidebar: {
    width: 260,
    background: '#ffffff',
    borderRight: '1px solid #e5e7eb',
    display: 'flex',
    flexDirection: 'column',
    flexShrink: 0,
    overflow: 'hidden',
  },
  scrollArea: {
    flex: 1,
    overflowY: 'auto',
    paddingBottom: '0.75rem',
  },
  sectionHeader: {
    padding: '1rem 1rem 0.5rem',
    fontSize: '0.74rem',
    color: '#94a3b8',
    letterSpacing: '0.1em',
    textTransform: 'uppercase' as const,
    fontWeight: 700,
    display: 'flex',
    alignItems: 'center',
    gap: 8,
  },
  addBtn: {
    background: 'transparent',
    border: 'none',
    color: '#4f46e5',
    cursor: 'pointer',
    fontSize: '1.1rem',
    padding: '0 4px',
    lineHeight: 1,
    marginLeft: 'auto',
    fontWeight: 600,
  },
  divider: { height: 1, background: '#e5e7eb', margin: '0.6rem 0.75rem' },
  emptyHint: { padding: '0.4rem 1rem', color: '#94a3b8', fontSize: '0.85rem' },
  errorHint: { padding: '0.4rem 1rem', color: '#dc2626', fontSize: '0.85rem' },
  newConvBtn: {
    display: 'block',
    width: '100%',
    background: 'transparent',
    border: 'none',
    color: '#4f46e5',
    fontSize: '0.88rem',
    cursor: 'pointer',
    padding: '0.5rem 1rem',
    textAlign: 'left' as const,
    fontWeight: 500,
  },
};

function agentItemStyle(active: boolean): React.CSSProperties {
  return {
    padding: '0.55rem 0.85rem',
    cursor: 'pointer',
    borderRadius: 8,
    margin: '2px 0.4rem',
    background: active ? '#eef2ff' : 'transparent',
    color: active ? '#4338ca' : '#475569',
    fontSize: '0.92rem',
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    transition: 'background 0.12s, color 0.12s',
    fontWeight: active ? 600 : 500,
  };
}

function sessionItemStyle(active: boolean): React.CSSProperties {
  return {
    padding: '0.55rem 0.85rem 0.55rem 1rem',
    cursor: 'pointer',
    borderRadius: 8,
    margin: '2px 0.4rem',
    background: active ? '#eef2ff' : 'transparent',
    color: active ? '#4338ca' : '#475569',
    fontSize: '0.88rem',
    display: 'flex',
    flexDirection: 'column',
    gap: 2,
    borderLeft: active ? '3px solid #4f46e5' : '3px solid transparent',
    transition: 'background 0.12s',
  };
}

function scopeBadgeStyle(scope: 'global' | 'user'): React.CSSProperties {
  return {
    fontSize: '0.7rem',
    borderRadius: 4,
    padding: '1px 6px',
    background: scope === 'global' ? '#dbeafe' : '#dcfce7',
    color: scope === 'global' ? '#1d4ed8' : '#15803d',
    flexShrink: 0,
    fontWeight: 600,
  };
}

// ── Helpers ──────────────────────────────────────────────────────────

function timeAgo(ms: number): string {
  if (!ms) return '';
  const secs = Math.floor((Date.now() - ms) / 1000);
  if (secs < 60) return 'just now';
  if (secs < 3600) return `${Math.floor(secs / 60)}m ago`;
  if (secs < 86400) return `${Math.floor(secs / 3600)}h ago`;
  return `${Math.floor(secs / 86400)}d ago`;
}

/** Converts gateway agentId back to user-facing agentId */
function resolveDisplayAgentId(gatewayAgentId: string, userId: string): string {
  const prefix = `uca-${userId}-`;
  if (gatewayAgentId.startsWith(prefix)) {
    return gatewayAgentId.slice(prefix.length);
  }
  return gatewayAgentId;
}

function agentLabel(agents: AgentDefinition[], agentId: string, userId: string): string {
  const displayId = resolveDisplayAgentId(agentId, userId);
  const found = agents.find(a => a.id === displayId);
  return found?.name ?? displayId;
}

// ── Create/Edit Modal ─────────────────────────────────────────────

interface ModalProps {
  initial?: AgentDefinition;
  onSave: (req: AgentCreateRequest) => Promise<void>;
  onClose: () => void;
}

function AgentModal({ initial, onSave, onClose }: ModalProps) {
  const [name, setName] = useState(initial?.name ?? '');
  const [id, setId] = useState(initial?.id ?? '');
  const [description, setDescription] = useState(initial?.description ?? '');
  const [sysPrompt, setSysPrompt] = useState(initial?.sysPrompt ?? '');
  const [maxIters, setMaxIters] = useState(initial?.maxIters != null ? String(initial.maxIters) : '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  async function handleSave() {
    if (!name.trim()) { setError('Name is required'); return; }
    setSaving(true); setError('');
    try {
      const req: AgentCreateRequest = {
        name: name.trim(),
        description: description.trim() || undefined,
        sysPrompt: sysPrompt.trim() || undefined,
        maxIters: maxIters.trim() ? parseInt(maxIters.trim(), 10) : undefined,
      };
      if (!initial && id.trim()) req.id = id.trim();
      await onSave(req);
      onClose();
    } catch (e: unknown) {
      setError(String(e));
    } finally {
      setSaving(false);
    }
  }

  const overlay: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(15,23,42,0.4)', backdropFilter: 'blur(2px)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 100 };
  const modal: React.CSSProperties = { background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 14, padding: '1.75rem', width: 500, maxHeight: '90vh', overflowY: 'auto', boxShadow: '0 24px 60px rgba(15,23,42,0.18), 0 4px 12px rgba(15,23,42,0.06)' };
  const input: React.CSSProperties = { width: '100%', background: '#ffffff', border: '1px solid #d1d5db', borderRadius: 8, color: '#0f172a', fontSize: '0.92rem', padding: '0.55rem 0.85rem', outline: 'none', boxSizing: 'border-box' };
  const label: React.CSSProperties = { display: 'block', color: '#475569', fontSize: '0.85rem', marginBottom: 6, marginTop: 14, fontWeight: 500 };

  return (
    <div style={overlay} onClick={e => e.target === e.currentTarget && onClose()}>
      <div style={modal}>
        <h3 style={{ color: '#0f172a', margin: '0 0 1.25rem', fontSize: '1.15rem', fontWeight: 700 }}>
          {initial ? 'Edit Agent' : 'Create Custom Agent'}
        </h3>
        {!initial && (
          <><label style={label}>ID (optional)</label>
          <input style={input} value={id} onChange={e => setId(e.target.value)} placeholder="auto-generated" /></>
        )}
        <label style={label}>Name *</label>
        <input style={input} value={name} onChange={e => setName(e.target.value)} placeholder="Display name" />
        <label style={label}>Description</label>
        <input style={input} value={description} onChange={e => setDescription(e.target.value)} placeholder="Short description" />
        <label style={label}>System Prompt</label>
        <textarea style={{ ...input, resize: 'vertical', minHeight: 110, fontFamily: 'inherit', lineHeight: 1.6 }} value={sysPrompt} onChange={e => setSysPrompt(e.target.value)} placeholder="You are a helpful assistant..." />
        <label style={label}>Max Iterations</label>
        <input style={{ ...input, width: 100 }} type="number" min={1} max={100} value={maxIters} onChange={e => setMaxIters(e.target.value)} placeholder="default" />
        {error && <div style={{ color: '#dc2626', fontSize: '0.88rem', marginTop: 12 }}>{error}</div>}
        <div style={{ display: 'flex', gap: 10, marginTop: 20, justifyContent: 'flex-end' }}>
          <button onClick={onClose} style={{ background: '#ffffff', border: '1px solid #d1d5db', color: '#475569', borderRadius: 8, padding: '8px 18px', cursor: 'pointer', fontWeight: 500, fontSize: '0.9rem' }}>Cancel</button>
          <button onClick={handleSave} disabled={saving} style={{ background: '#4f46e5', border: 'none', color: '#fff', borderRadius: 8, padding: '8px 22px', cursor: 'pointer', fontWeight: 600, fontSize: '0.9rem', boxShadow: '0 1px 3px rgba(79,70,229,0.25)' }}>
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Main sidebar ──────────────────────────────────────────────────

export default function AgentSidebar({ selectedAgentId, onSelectAgent, userId, refreshTick }: Props) {
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [sessions, setSessions] = useState<SessionView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [editAgent, setEditAgent] = useState<AgentDefinition | null>(null);

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const [agentData, sessionData] = await Promise.all([
        listAgents(),
        listSessions(100),
      ]);
      setAgents(agentData);
      setSessions(sessionData.filter(s => s.kind === 'MAIN'));
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load, refreshTick]);

  // Auto-refresh sessions every 20s
  useEffect(() => {
    const id = setInterval(() => {
      listSessions(100).then(data => setSessions(data.filter(s => s.kind === 'MAIN'))).catch(() => {});
    }, 20_000);
    return () => clearInterval(id);
  }, []);

  async function handleCreate(req: AgentCreateRequest) {
    await createAgent(req);
    await load();
  }

  async function handleUpdate(req: AgentCreateRequest) {
    if (!editAgent) return;
    await updateAgent(editAgent.id, req);
    await load();
  }

  async function handleDelete(agent: AgentDefinition, e: React.MouseEvent) {
    e.stopPropagation();
    if (!confirm(`Delete agent "${agent.name}"?`)) return;
    await deleteAgent(agent.id);
    if (selectedAgentId === agent.id) onSelectAgent(null);
    await load();
  }

  const globalAgents = agents.filter(a => a.scope === 'global');
  const userAgents = agents.filter(a => a.scope === 'user');

  // Sort sessions by last activity desc
  const sortedSessions = [...sessions].sort((a, b) => b.lastActivityMs - a.lastActivityMs);

  function isSessionActive(sess: SessionView): boolean {
    const displayId = resolveDisplayAgentId(sess.agentId, userId);
    return selectedAgentId === displayId;
  }

  function handleSessionClick(sess: SessionView) {
    const displayId = resolveDisplayAgentId(sess.agentId, userId);
    onSelectAgent(displayId === 'default' || displayId === sess.agentId ? displayId : displayId, sess.sessionKey);
  }

  return (
    <div style={s.sidebar}>
      <div style={s.scrollArea}>

        {/* ── Available Agents ─────────────────── */}
        <div style={s.sectionHeader}>
          <span>Agents</span>
        </div>

        {/* Default / global agents */}
        <div style={agentItemStyle(selectedAgentId === null)} onClick={() => onSelectAgent(null)}>
          <span style={{ flex: 1 }}>Default</span>
        </div>

        {loading && <div style={s.emptyHint}>Loading…</div>}
        {error && <div style={s.errorHint}>{error}</div>}

        {globalAgents.map(agent => (
          <div key={agent.id}>
            <div
              style={agentItemStyle(selectedAgentId === agent.id)}
              onClick={() => onSelectAgent(agent.id)}
              title={agent.description || agent.id}
            >
              <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{agent.name}</span>
              <span style={scopeBadgeStyle('global')}>global</span>
            </div>
            {selectedAgentId === agent.id && agent.tools && agent.tools.length > 0 && (
              <div style={{ padding: '4px 12px 8px 20px', display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                {agent.tools.map(t => (
                  <span key={t} style={{ fontSize: '0.72rem', background: '#f1f5f9', color: '#475569', borderRadius: 4, padding: '2px 7px', border: '1px solid #e5e7eb' }}>
                    {t}
                  </span>
                ))}
              </div>
            )}
          </div>
        ))}

        <div style={s.divider} />

        {/* User-custom agents */}
        <div style={s.sectionHeader}>
          <span>My Agents</span>
          <button style={s.addBtn} title="Create custom agent" onClick={() => setShowCreate(true)}>+</button>
        </div>

        {userAgents.length === 0 && !loading && (
          <div style={s.emptyHint}>No custom agents yet.</div>
        )}

        {userAgents.map(agent => (
          <div
            key={agent.id}
            style={agentItemStyle(selectedAgentId === agent.id)}
            onClick={() => onSelectAgent(agent.id)}
            title={agent.description || agent.id}
          >
            <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{agent.name}</span>
            <div style={{ display: 'flex', gap: 4, flexShrink: 0, alignItems: 'center' }}>
              <span style={scopeBadgeStyle('user')}>mine</span>
              <button style={{ background: 'transparent', border: 'none', color: '#4f46e5', cursor: 'pointer', fontSize: '0.82rem', padding: '0 3px' }} title="Edit" onClick={e => { e.stopPropagation(); setEditAgent(agent); }}>✎</button>
              <button style={{ background: 'transparent', border: 'none', color: '#dc2626', cursor: 'pointer', fontSize: '0.82rem', padding: '0 3px' }} title="Delete" onClick={e => handleDelete(agent, e)}>✕</button>
            </div>
          </div>
        ))}

        <div style={s.divider} />

        {/* ── My Conversations (Channels) ───────── */}
        <div style={s.sectionHeader}>
          <span>My Conversations</span>
        </div>

        {sortedSessions.length === 0 && !loading && (
          <div style={s.emptyHint}>Start a conversation above.</div>
        )}

        {sortedSessions.map(sess => {
          const label = agentLabel(agents, sess.agentId, userId);
          const active = isSessionActive(sess);
          return (
            <div
              key={sess.sessionKey}
              style={sessionItemStyle(active)}
              onClick={() => handleSessionClick(sess)}
              title={sess.sessionKey}
            >
              <span style={{ fontSize: '0.9rem', color: active ? '#4338ca' : '#0f172a', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontWeight: active ? 600 : 500 }}>
                {label}
              </span>
              <span style={{ fontSize: '0.78rem', color: '#94a3b8' }}>
                {timeAgo(sess.lastActivityMs)}
              </span>
            </div>
          );
        })}

        {sessions.length > 0 && (
          <button style={s.newConvBtn} onClick={() => onSelectAgent(null)}>
            + New conversation
          </button>
        )}
      </div>

      {showCreate && <AgentModal onSave={handleCreate} onClose={() => setShowCreate(false)} />}
      {editAgent && <AgentModal initial={editAgent} onSave={handleUpdate} onClose={() => setEditAgent(null)} />}
    </div>
  );
}
