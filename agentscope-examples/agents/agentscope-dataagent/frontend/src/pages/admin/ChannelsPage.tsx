import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import AdminPageLayout from '../../components/admin/AdminPageLayout';
import { getToken } from '../../api/auth';
import {
  listChannels, listBindings, createBinding, updateBinding, deleteBinding,
  ChannelView, BindingView, EditableBinding, BindingMutationRequest,
} from '../../api/admin';

// ── Identity Links (embedded from AdminIdentityLinksPage) ─────────────
type IdentitySnapshot = Record<string, Record<string, string>>;

function IdentityLinksPanel() {
  const [snap, setSnap] = useState<IdentitySnapshot>({});
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    setLoading(true); setErr(null);
    try {
      const t = getToken() ?? '';
      const res = await fetch('/api/admin/identity-links', { headers: { Authorization: `Bearer ${t}` } });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      setSnap(await res.json());
    } catch (e: unknown) { setErr(e instanceof Error ? e.message : String(e)); }
    finally { setLoading(false); }
  }

  useEffect(() => { load(); }, []);

  const users = Object.entries(snap);
  const IS: Record<string, React.CSSProperties> = {
    refresh: { background: '#ffffff', border: '1px solid #d1d5db', color: '#475569', borderRadius: 8, padding: '7px 14px', cursor: 'pointer', fontSize: '0.86rem', fontWeight: 500 },
    intro: { color: '#475569', fontSize: '0.9rem', marginBottom: 22, lineHeight: 1.7 },
    card: { background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 12, padding: '1.25rem 1.5rem', marginBottom: 16, boxShadow: '0 1px 3px rgba(15,23,42,0.04)' },
    userId: { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '1rem', color: '#4f46e5', fontWeight: 600, marginBottom: 14 },
    table: { width: '100%', borderCollapse: 'collapse' as const, fontSize: '0.88rem' },
    th: { textAlign: 'left' as const, padding: '8px 12px', color: '#64748b', borderBottom: '1px solid #e5e7eb', fontWeight: 600, fontSize: '0.78rem', textTransform: 'uppercase' as const, letterSpacing: '0.04em' },
    td: { padding: '8px 12px', borderBottom: '1px solid #f1f5f9', color: '#334155', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.84rem' },
    err: { color: '#dc2626', fontSize: '0.9rem', background: '#fef2f2', border: '1px solid #fecaca', borderRadius: 10, padding: '12px 16px', marginBottom: 16 },
    empty: { color: '#94a3b8', fontSize: '0.92rem' },
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
        <button style={IS.refresh} onClick={load} disabled={loading}>{loading ? '…' : '↺ Refresh'}</button>
      </div>
      <p style={IS.intro}>
        Each user's identity on other channels (Slack, Discord, GitHub, …). Bound via{' '}
        <code>/dock_&lt;channel&gt; &lt;externalId&gt;</code> in chat. The runtime uses these links
        to route messages back through the correct channel for the same logical user.
      </p>
      {err && <div style={IS.err}>{err}</div>}
      {!loading && users.length === 0 && !err && <p style={IS.empty}>No identity links recorded yet.</p>}
      {users.map(([userId, links]) => (
        <div style={IS.card} key={userId}>
          <div style={IS.userId}>{userId}</div>
          <table style={IS.table}>
            <thead>
              <tr>
                <th style={IS.th}>Channel</th>
                <th style={IS.th}>External ID</th>
              </tr>
            </thead>
            <tbody>
              {Object.entries(links).map(([ch, ext]) => (
                <tr key={ch}>
                  <td style={IS.td}>{ch}</td>
                  <td style={{ ...IS.td, color: '#6d28d9' }}>{ext}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </div>
  );
}

const S: Record<string, React.CSSProperties> = {
  content: { maxWidth: 1200 },
  card: { background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 12, marginBottom: 20, overflow: 'hidden', boxShadow: '0 1px 3px rgba(15,23,42,0.04)' },
  cardHeader: {
    display: 'flex', alignItems: 'center', gap: 12,
    padding: '1rem 1.5rem', borderBottom: '1px solid #f1f5f9',
    cursor: 'pointer',
    background: '#f8fafc',
  },
  channelId: { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontWeight: 600, color: '#4f46e5', fontSize: '1rem' },
  channelLink: { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontWeight: 600, color: '#4f46e5', fontSize: '1rem', textDecoration: 'none', borderBottom: '1px dashed #4f46e5' },
  statusDot: {
    width: 10, height: 10, borderRadius: '50%',
    background: '#16a34a',
    flexShrink: 0,
    boxShadow: '0 0 0 3px rgba(22,163,74,0.15)',
  },
  badge: { borderRadius: 6, padding: '3px 10px', fontSize: '0.78rem', background: '#dbeafe', color: '#1d4ed8', fontWeight: 500 },
  metaRow: {
    display: 'flex', gap: 28, padding: '0.9rem 1.5rem',
    borderBottom: '1px solid #f1f5f9', fontSize: '0.88rem', color: '#475569', flexWrap: 'wrap',
  },
  metaItem: { display: 'flex', gap: 8, alignItems: 'center' },
  metaLabel: { color: '#94a3b8', fontWeight: 500 },
  bindingsSection: { padding: '1rem 1.5rem' },
  bindingHeader: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 },
  bindingLabel: { fontSize: '0.82rem', color: '#64748b', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.06em' },
  addBtn: { background: '#4f46e5', color: '#fff', border: 'none', borderRadius: 8, padding: '6px 16px', cursor: 'pointer', fontSize: '0.84rem', fontWeight: 600, boxShadow: '0 1px 3px rgba(79,70,229,0.25)' },
  table: { width: '100%', borderCollapse: 'collapse' as const, fontSize: '0.86rem' },
  th: { textAlign: 'left' as const, padding: '0.5rem 0.85rem', color: '#64748b', fontWeight: 600, borderBottom: '1px solid #e5e7eb', fontSize: '0.76rem', textTransform: 'uppercase' as const, letterSpacing: '0.04em' },
  td: { padding: '0.55rem 0.85rem', borderBottom: '1px solid #f1f5f9', color: '#475569', verticalAlign: 'top' as const },
  empty: { color: '#94a3b8', fontSize: '0.88rem', padding: '0.75rem 0' },
  refreshBtn: {
    background: '#ffffff',
    border: '1px solid #d1d5db',
    color: '#475569',
    borderRadius: 8,
    padding: '6px 14px',
    cursor: 'pointer',
    fontSize: '0.86rem',
    marginLeft: 12,
    fontWeight: 500,
  },
  err: { color: '#dc2626', fontSize: '0.92rem', padding: '14px 18px', background: '#fef2f2', borderRadius: 10, border: '1px solid #fecaca' },
  warn: { color: '#92400e', fontSize: '0.86rem', background: '#fffbeb', border: '1px solid #fcd34d', borderRadius: 8, padding: '8px 14px', marginBottom: 12 },
  tierBadgeBase: {
    display: 'inline-block', padding: '2px 9px', borderRadius: 999, fontSize: '0.76rem',
    background: '#eef2ff', color: '#4338ca', fontWeight: 600,
  } as React.CSSProperties,
  rowBtn: { background: '#eef2ff', border: '1px solid #c7d2fe', color: '#4338ca', borderRadius: 6, padding: '3px 10px', cursor: 'pointer', fontSize: '0.78rem', marginRight: 6, fontWeight: 500 },
  delBtn:  { background: '#ffffff', border: '1px solid #fecaca', color: '#dc2626', borderRadius: 6, padding: '3px 10px', cursor: 'pointer', fontSize: '0.78rem', fontWeight: 500 },
  modal:     { position: 'fixed' as const, inset: 0, background: 'rgba(15,23,42,0.4)', backdropFilter: 'blur(2px)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200 },
  modalBox:  { background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 14, padding: '1.6rem 1.75rem', width: 600, maxHeight: '88vh', overflowY: 'auto' as const, boxShadow: '0 24px 60px rgba(15,23,42,0.18), 0 4px 12px rgba(15,23,42,0.06)' },
  modalTitle: { fontSize: '1.1rem', fontWeight: 700, color: '#0f172a', marginBottom: 18 },
  label: { display: 'block', fontSize: '0.85rem', color: '#475569', fontWeight: 500, marginBottom: 6 },
  hint:  { display: 'block', fontSize: '0.78rem', color: '#94a3b8', marginTop: -6, marginBottom: 12 },
  input: { width: '100%', boxSizing: 'border-box' as const, padding: '8px 12px', background: '#ffffff', border: '1px solid #d1d5db', borderRadius: 7, color: '#0f172a', fontSize: '0.9rem', marginBottom: 12, outline: 'none' },
  select: { width: '100%', boxSizing: 'border-box' as const, padding: '8px 12px', background: '#ffffff', border: '1px solid #d1d5db', borderRadius: 7, color: '#0f172a', fontSize: '0.9rem', marginBottom: 12, outline: 'none' },
  row2: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 },
  btnRow: { display: 'flex', gap: 10, marginTop: 12 },
  saveBtn:   { background: '#4f46e5', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 22px', cursor: 'pointer', fontSize: '0.9rem', fontWeight: 600, boxShadow: '0 1px 3px rgba(79,70,229,0.25)' },
  cancelBtn: { background: '#ffffff', border: '1px solid #d1d5db', color: '#475569', borderRadius: 8, padding: '9px 18px', cursor: 'pointer', fontSize: '0.9rem', fontWeight: 500 },
};

// ── Binding form modal ────────────────────────────────────────────────
interface BindingFormProps {
  channelId: string;
  initial?: EditableBinding;
  onSaved: (msg: string) => void;
  onClose: () => void;
}

function BindingFormModal({ channelId, initial, onSaved, onClose }: BindingFormProps) {
  const isEdit = !!initial;
  const [agentId,      setAgentId]      = useState(initial?.agentId ?? '');
  const [peer,         setPeer]         = useState(initial?.peer ?? '');
  const [parentPeer,   setParentPeer]   = useState(initial?.parentPeer ?? '');
  const [guild,        setGuild]        = useState(initial?.guild ?? '');
  const [roles,        setRoles]        = useState((initial?.roles ?? []).join(', '));
  const [team,         setTeam]         = useState(initial?.team ?? '');
  const [account,      setAccount]      = useState(initial?.account ?? '');
  const [channel,      setChannel]      = useState(initial?.channel ?? '');
  const [sessionScope, setSessionScope] = useState(initial?.sessionScope ?? '');
  const [err,    setErr]    = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function submit() {
    setErr(null); setSaving(true);
    const req: BindingMutationRequest = {
      agentId,
      peer: peer || undefined,
      parentPeer: parentPeer || undefined,
      guild: guild || undefined,
      roles: roles.split(',').map(s => s.trim()).filter(Boolean),
      team: team || undefined,
      account: account || undefined,
      channel: channel || undefined,
      sessionScope: sessionScope || undefined,
    };
    if (req.roles && req.roles.length === 0) req.roles = undefined;
    try {
      const r = isEdit
        ? await updateBinding(channelId, initial!.index, req)
        : await createBinding(channelId, req);
      onSaved(r.message);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div style={S.modal} onClick={onClose}>
      <div style={S.modalBox} onClick={e => e.stopPropagation()}>
        <div style={S.modalTitle}>
          {isEdit ? `Edit Binding #${initial!.index} — ${channelId}` : `New Binding — ${channelId}`}
        </div>
        {err && <div style={{ ...S.warn, color: '#dc2626', borderColor: '#fecaca', background: '#fef2f2' }}>{err}</div>}

        <label style={S.label}>Agent ID *</label>
        <input style={S.input} value={agentId} onChange={e => setAgentId(e.target.value)} placeholder="support" />
        <span style={S.hint}>Required. The agent this binding routes to when it matches.</span>

        <div style={S.row2}>
          <div>
            <label style={S.label}>Peer (highest priority)</label>
            <input style={S.input} value={peer} onChange={e => setPeer(e.target.value)} placeholder="direct:u_42" />
          </div>
          <div>
            <label style={S.label}>Parent peer</label>
            <input style={S.input} value={parentPeer} onChange={e => setParentPeer(e.target.value)} placeholder="channel:c_ops" />
          </div>
        </div>

        <div style={S.row2}>
          <div>
            <label style={S.label}>Guild</label>
            <input style={S.input} value={guild} onChange={e => setGuild(e.target.value)} placeholder="ws-alpha" />
          </div>
          <div>
            <label style={S.label}>Roles (comma-separated, requires guild)</label>
            <input style={S.input} value={roles} onChange={e => setRoles(e.target.value)} placeholder="staff, admin" />
          </div>
        </div>

        <div style={S.row2}>
          <div>
            <label style={S.label}>Team</label>
            <input style={S.input} value={team} onChange={e => setTeam(e.target.value)} placeholder="t_engineering" />
          </div>
          <div>
            <label style={S.label}>Account</label>
            <input style={S.input} value={account} onChange={e => setAccount(e.target.value)} placeholder="acc_main" />
          </div>
        </div>

        <label style={S.label}>Channel (lowest priority)</label>
        <input style={S.input} value={channel} onChange={e => setChannel(e.target.value)} placeholder="chatui" />

        <label style={S.label}>Session scope override (optional)</label>
        <select style={S.select} value={sessionScope} onChange={e => setSessionScope(e.target.value)}>
          <option value="">(inherit from channel)</option>
          <option value="MAIN">MAIN</option>
          <option value="PER_PEER">PER_PEER</option>
          <option value="PER_CHANNEL_PEER">PER_CHANNEL_PEER</option>
          <option value="PER_ACCOUNT_CHANNEL_PEER">PER_ACCOUNT_CHANNEL_PEER</option>
        </select>

        <div style={S.btnRow}>
          <button style={S.saveBtn} disabled={saving || !agentId} onClick={submit}>
            {saving ? 'Saving…' : isEdit ? 'Update' : 'Add'}
          </button>
          <button style={S.cancelBtn} onClick={onClose}>Cancel</button>
        </div>
      </div>
    </div>
  );
}

// ── Per-channel card ──────────────────────────────────────────────────
function ChannelCard({ ch, onChanged }: { ch: ChannelView; onChanged: () => void }) {
  const [expanded, setExpanded] = useState(false);
  const [editing,  setEditing]  = useState<EditableBinding | 'new' | null>(null);
  const [editable, setEditable] = useState<EditableBinding[]>([]);
  const [warn,     setWarn]     = useState<string | null>(null);
  const [err,      setErr]      = useState<string | null>(null);
  const [loadingEditable, setLoadingEditable] = useState(false);

  async function loadEditable() {
    setLoadingEditable(true); setErr(null);
    try { setEditable(await listBindings(ch.channelId)); }
    catch (e: unknown) { setErr(e instanceof Error ? e.message : String(e)); }
    finally { setLoadingEditable(false); }
  }

  useEffect(() => { if (expanded) loadEditable(); }, [expanded]);

  async function onDel(b: EditableBinding) {
    if (!confirm(`Delete binding #${b.index} (→ ${b.agentId})? Restart required.`)) return;
    try {
      await deleteBinding(ch.channelId, b.index);
      setWarn(`Binding #${b.index} removed. Restart required.`);
      await loadEditable();
      onChanged();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }

  function cellVal(v: string | null | undefined) {
    return v ?? <span style={{ color: '#cbd5e1' }}>—</span>;
  }

  return (
    <div style={S.card}>
      <div style={S.cardHeader} onClick={() => setExpanded(e => !e)}>
        <div style={{ ...S.statusDot, background: ch.started ? '#16a34a' : '#dc2626', boxShadow: ch.started ? '0 0 0 3px rgba(22,163,74,0.15)' : '0 0 0 3px rgba(220,38,38,0.12)' }} title={ch.started ? 'Started' : 'Stopped'} />
        <Link to={`/admin/channels/${encodeURIComponent(ch.channelId)}`} onClick={e => e.stopPropagation()} style={S.channelLink}>
          {ch.channelId}
        </Link>
        <span style={S.badge}>{ch.dmScope}</span>
        <span style={{ color: '#94a3b8', fontSize: '0.86rem' }}>
          {ch.bindingCount} binding{ch.bindingCount !== 1 ? 's' : ''}
        </span>
        <span style={{ color: '#94a3b8', fontSize: '0.86rem', marginLeft: 'auto' }}>
          {ch.outboundQueueSize > 0 ? `⚡ ${ch.outboundQueueSize} outbound` : ''}
          {expanded ? ' ▾' : ' ▸'}
        </span>
      </div>

      <div style={S.metaRow}>
        <div style={S.metaItem}><span style={S.metaLabel}>defaultAgent</span>{ch.defaultAgentId ?? '—'}</div>
        <div style={S.metaItem}><span style={S.metaLabel}>status</span>{ch.started ? 'running' : 'stopped'}</div>
        <div style={S.metaItem}><span style={S.metaLabel}>outboundQueue</span>{ch.outboundQueueSize}</div>
      </div>

      {expanded && (
        <>
          {/* Live (running) bindings — read-only snapshot from the router */}
          <div style={S.bindingsSection}>
            <div style={S.bindingLabel}>Live bindings (active in router)</div>
            {ch.bindings.length === 0 ? (
              <div style={S.empty}>No live bindings — router uses default agent.</div>
            ) : (
              <table style={S.table}>
                <thead>
                  <tr>
                    <th style={S.th}>Agent</th>
                    <th style={S.th}>Peer</th>
                    <th style={S.th}>Guild</th>
                    <th style={S.th}>Channel</th>
                    <th style={S.th}>Session Scope</th>
                  </tr>
                </thead>
                <tbody>
                  {ch.bindings.map((b: BindingView, i: number) => (
                    <tr key={i}>
                      <td style={S.td}><span style={{ color: '#4f46e5', fontWeight: 500 }}>{cellVal(b.agentId)}</span></td>
                      <td style={S.td}>{cellVal(b.peerId)}</td>
                      <td style={S.td}>{cellVal(b.guildId)}</td>
                      <td style={S.td}>{cellVal(b.roomId)}</td>
                      <td style={S.td}>{cellVal(b.sessionScope)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>

          {/* Editable bindings — persist to agentscope.json (restart required) */}
          <div style={{ ...S.bindingsSection, borderTop: '1px solid #f1f5f9' }}>
            <div style={S.bindingHeader}>
              <div style={S.bindingLabel}>Configured bindings (agentscope.json)</div>
              <button style={S.addBtn} onClick={() => setEditing('new')}>+ Add</button>
            </div>
            {err  && <div style={{ ...S.warn, color: '#dc2626', background: '#fef2f2', borderColor: '#fecaca' }}>{err}</div>}
            {warn && <div style={S.warn}>⚠ {warn}</div>}
            {loadingEditable && <div style={S.empty}>Loading…</div>}
            {!loadingEditable && editable.length === 0 && (
              <div style={S.empty}>No bindings in agentscope.json. Add one with the button above.</div>
            )}
            {!loadingEditable && editable.length > 0 && (
              <table style={S.table}>
                <thead>
                  <tr>
                    <th style={S.th}>#</th>
                    <th style={S.th}>Tier</th>
                    <th style={S.th}>Agent</th>
                    <th style={S.th}>Match</th>
                    <th style={S.th}>Session</th>
                    <th style={S.th}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {editable.map(b => (
                    <tr key={b.index}>
                      <td style={S.td}>{b.index}</td>
                      <td style={S.td}><span style={S.tierBadgeBase}>{b.tier}</span></td>
                      <td style={S.td}><span style={{ color: '#4f46e5', fontWeight: 500 }}>{cellVal(b.agentId)}</span></td>
                      <td style={S.td}>
                        {summarizeMatch(b)}
                      </td>
                      <td style={S.td}>{cellVal(b.sessionScope)}</td>
                      <td style={S.td}>
                        <button style={S.rowBtn} onClick={() => setEditing(b)}>Edit</button>
                        <button style={S.delBtn} onClick={() => onDel(b)}>Delete</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            <div style={{ ...S.hint, marginTop: 10 }}>
              Changes to configured bindings are written to <code style={{ color: '#4f46e5', background: '#f1f5f9', padding: '1px 6px', borderRadius: 4 }}>agentscope.json</code> immediately
              but only take effect for the running router after a server restart.
            </div>
          </div>
        </>
      )}

      {editing && (
        <BindingFormModal
          channelId={ch.channelId}
          initial={editing === 'new' ? undefined : editing}
          onSaved={(msg) => { setEditing(null); setWarn(msg); loadEditable(); onChanged(); }}
          onClose={() => setEditing(null)}
        />
      )}
    </div>
  );
}

function summarizeMatch(b: EditableBinding): React.ReactNode {
  const parts: string[] = [];
  if (b.peer) parts.push(`peer=${b.peer}`);
  if (b.parentPeer) parts.push(`parentPeer=${b.parentPeer}`);
  if (b.guild) parts.push(`guild=${b.guild}`);
  if (b.roles?.length) parts.push(`roles=[${b.roles.join(',')}]`);
  if (b.team) parts.push(`team=${b.team}`);
  if (b.account) parts.push(`account=${b.account}`);
  if (b.channel) parts.push(`channel=${b.channel}`);
  if (!parts.length) return <span style={{ color: '#94a3b8' }}>(matches everything)</span>;
  return <span style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.82rem', color: '#475569' }}>{parts.join(' · ')}</span>;
}

// ── Page ──────────────────────────────────────────────────────────────
const CHANNELS_TABS = [
  { key: 'channels',  label: 'Channels',        icon: '📡' },
  { key: 'identities', label: 'Identity Links', icon: '🪪' },
];

export default function ChannelsPage() {
  const [tab,      setTab]      = useState<'channels' | 'identities'>('channels');
  const [channels, setChannels] = useState<ChannelView[]>([]);
  const [loading,  setLoading]  = useState(true);
  const [error,    setError]    = useState<string | null>(null);
  const [tick,     setTick]     = useState(0);

  async function load() {
    setLoading(true); setError(null);
    try { setChannels(await listChannels()); }
    catch (e) { setError(String(e)); }
    finally { setLoading(false); }
  }

  useEffect(() => { load(); }, [tick]);

  const tabs = CHANNELS_TABS.map(t =>
    t.key === 'channels' ? { ...t, badge: loading ? '…' : channels.length } : t
  );

  return (
    <>
      <AdminPageLayout
        tabs={tabs}
        activeTab={tab}
        onTabChange={k => setTab(k as 'channels' | 'identities')}
        bannerRight={
          tab === 'channels'
            ? <button style={S.refreshBtn} onClick={load} disabled={loading}>{loading ? '…' : '↺'}</button>
            : undefined
        }
      >
        {tab === 'channels' && (
          <>
            {error && <div style={S.err}>{error}</div>}
            {!loading && channels.length === 0 && !error && (
              <p style={{ color: '#94a3b8', fontSize: '0.92rem' }}>No channels registered.</p>
            )}
            {channels.map(ch => (
              <ChannelCard key={ch.channelId} ch={ch} onChanged={() => setTick(t => t + 1)} />
            ))}
          </>
        )}

        {tab === 'identities' && <IdentityLinksPanel />}
      </AdminPageLayout>
    </>
  );
}
