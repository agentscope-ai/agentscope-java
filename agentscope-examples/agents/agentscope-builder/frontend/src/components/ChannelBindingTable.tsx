import React, { useEffect, useMemo, useState } from 'react';
import {
  ChannelInfo, AgentBinding, BindingTier, BindingCreateRequest,
  listChannels, listAgentBindings, addBinding, updateBinding, deleteBinding, setChannelDefault,
} from '../api/channels';

interface Props {
  agentId: string;
}

const TIERS: { value: BindingTier; label: string; field: keyof BindingCreateRequest | 'roles' }[] = [
  { value: 'peer',       label: 'Peer (most specific)', field: 'peer' },
  { value: 'parentPeer', label: 'Parent peer',          field: 'parentPeer' },
  { value: 'guildRoles', label: 'Guild + roles',        field: 'roles' },
  { value: 'guild',      label: 'Guild',                field: 'guild' },
  { value: 'team',       label: 'Team',                 field: 'team' },
  { value: 'account',    label: 'Account',              field: 'account' },
  { value: 'channel',    label: 'Channel (catch-all)',  field: 'channel' },
];

const SCOPES: { value: NonNullable<BindingCreateRequest['sessionScope']>; label: string }[] = [
  { value: 'MAIN',                       label: 'MAIN — single session per channel' },
  { value: 'PER_PEER',                   label: 'PER_PEER — one per sender' },
  { value: 'PER_CHANNEL_PEER',           label: 'PER_CHANNEL_PEER — one per channel+sender' },
  { value: 'PER_ACCOUNT_CHANNEL_PEER',   label: 'PER_ACCOUNT_CHANNEL_PEER — most specific' },
];

const S: Record<string, React.CSSProperties> = {
  root: { padding: '28px 32px', maxWidth: 1100 },
  title: { margin: '0 0 18px', fontSize: '1.4rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.01em' },
  banner: {
    padding: '12px 16px', background: '#fef3c7', border: '1px solid #fcd34d',
    color: '#92400e', fontSize: '0.88rem', borderRadius: 10, marginBottom: 20,
  },
  section: {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 14,
    padding: '20px 22px', marginBottom: 16,
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
  channelHead: { display: 'flex', alignItems: 'center', gap: 12, marginBottom: 14 },
  channelName: { fontSize: '1.05rem', fontWeight: 600, color: '#0f172a' },
  badge: {
    padding: '3px 10px', borderRadius: 999, fontSize: '0.76rem', fontWeight: 500,
    background: '#f1f5f9', color: '#475569', border: '1px solid #e2e8f0',
  },
  defaultMark: {
    padding: '3px 10px', borderRadius: 999, fontSize: '0.76rem', fontWeight: 600,
    background: '#eef2ff', color: '#4338ca', border: '1px solid #c7d2fe',
  },
  btnSm: {
    padding: '7px 14px', fontSize: '0.84rem', fontWeight: 500, borderRadius: 8, cursor: 'pointer',
    border: '1px solid #cbd5e1', background: '#ffffff', color: '#475569',
  },
  btnPrimary: {
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none',
    boxShadow: '0 1px 4px rgba(99,102,241,0.3), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  bindingRow: {
    display: 'flex', alignItems: 'center', gap: 12,
    padding: '10px 12px', borderRadius: 9, fontSize: '0.9rem', color: '#334155',
    background: '#f8fafc', marginBottom: 6, border: '1px solid #f1f5f9',
  },
  err: { color: '#dc2626', fontSize: '0.9rem', marginTop: 8 },
  formGrid: {
    display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14, marginTop: 14,
  },
  fieldLabel: {
    display: 'block', fontSize: '0.85rem', color: '#475569', marginBottom: 6, fontWeight: 500,
  },
  input: {
    width: '100%', boxSizing: 'border-box', padding: '10px 12px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 8,
    color: '#0f172a', fontSize: '0.92rem',
  },
};

interface FormState {
  channelId: string;
  tier: BindingTier;
  peer: string;
  parentPeer: string;
  guild: string;
  roles: string;
  team: string;
  account: string;
  channel: string;
  sessionScope: '' | NonNullable<BindingCreateRequest['sessionScope']>;
}

function emptyForm(channelId: string): FormState {
  return {
    channelId, tier: 'peer',
    peer: '', parentPeer: '', guild: '', roles: '', team: '', account: '', channel: '',
    sessionScope: '',
  };
}

function formToReq(f: FormState): BindingCreateRequest {
  const req: BindingCreateRequest = { channelId: f.channelId, tier: f.tier };
  if (f.peer) req.peer = f.peer;
  if (f.parentPeer) req.parentPeer = f.parentPeer;
  if (f.guild) req.guild = f.guild;
  if (f.roles) req.roles = f.roles.split(',').map(s => s.trim()).filter(Boolean);
  if (f.team) req.team = f.team;
  if (f.account) req.account = f.account;
  if (f.channel) req.channel = f.channel;
  if (f.sessionScope) req.sessionScope = f.sessionScope;
  return req;
}

function bindingToForm(b: AgentBinding): FormState {
  return {
    channelId: b.channelId,
    tier: b.tier,
    peer: b.peer ?? '',
    parentPeer: b.parentPeer ?? '',
    guild: b.guild ?? '',
    roles: (b.roles ?? []).join(', '),
    team: b.team ?? '',
    account: b.account ?? '',
    channel: b.channel ?? '',
    sessionScope: b.sessionScope ?? '',
  };
}

function describe(b: AgentBinding): string {
  if (b.tier === 'peer' && b.peer) return `peer = ${b.peer}`;
  if (b.tier === 'parentPeer' && b.parentPeer) return `parentPeer = ${b.parentPeer}`;
  if (b.tier === 'guildRoles') return `guild = ${b.guild ?? '*'}, roles = ${(b.roles ?? []).join('|') || '*'}`;
  if (b.tier === 'guild' && b.guild) return `guild = ${b.guild}`;
  if (b.tier === 'team' && b.team) return `team = ${b.team}`;
  if (b.tier === 'account' && b.account) return `account = ${b.account}`;
  if (b.tier === 'channel') return `channel = ${b.channel ?? b.channelId}`;
  return b.tier;
}

export default function ChannelBindingTable({ agentId }: Props) {
  const [channels, setChannels] = useState<ChannelInfo[]>([]);
  const [bindings, setBindings] = useState<AgentBinding[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [editing, setEditing] = useState<{ form: FormState; index: number | null } | null>(null);
  const [dirty, setDirty] = useState(false);

  async function reload() {
    setErr(null);
    try {
      const [c, b] = await Promise.all([listChannels(), listAgentBindings(agentId)]);
      setChannels(c);
      setBindings(b);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to load');
    }
  }

  useEffect(() => { reload(); /* eslint-disable-next-line */ }, [agentId]);

  const byChannel = useMemo(() => {
    const m: Record<string, AgentBinding[]> = {};
    for (const b of bindings) {
      (m[b.channelId] ??= []).push(b);
    }
    return m;
  }, [bindings]);

  async function handleSave() {
    if (!editing) return;
    const req = formToReq(editing.form);
    try {
      if (editing.index == null) {
        await addBinding(agentId, req);
      } else {
        await updateBinding(agentId, editing.form.channelId, editing.index, req);
      }
      setEditing(null);
      setDirty(true);
      reload();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Save failed');
    }
  }

  async function handleDelete(b: AgentBinding) {
    if (!confirm(`Delete binding (${describe(b)})?`)) return;
    try {
      await deleteBinding(agentId, b.channelId, b.index);
      setDirty(true);
      reload();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Delete failed');
    }
  }

  async function handleSetDefault(channelId: string) {
    try {
      await setChannelDefault(agentId, channelId);
      setDirty(true);
      reload();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed');
    }
  }

  return (
    <div style={S.root}>
      <h2 style={S.title}>Channels</h2>
      {dirty && (
        <div style={S.banner}>
          ⚠ Channel adapters that do not support live config swap will pick up binding changes on
          next restart. Check the server log for the per-channel status emitted by
          BindingPersistence.
        </div>
      )}
      {err && <div style={{ ...S.err, marginBottom: 8 }}>{err}</div>}
      {channels.length === 0 && <div style={{ color: '#94a3b8', fontSize: '0.85rem' }}>No channels registered.</div>}
      {channels.map(c => {
        const isDefault = c.defaultAgentId === agentId;
        const myBindings = byChannel[c.channelId] ?? [];
        return (
          <div key={c.channelId} style={S.section}>
            <div style={S.channelHead}>
              <span style={S.channelName}>{c.channelId}</span>
              <span style={S.badge}>{c.dmScope}</span>
              {!c.started && <span style={{ ...S.badge, color: '#dc2626' }}>stopped</span>}
              {isDefault && <span style={S.defaultMark}>default</span>}
              <span style={{ flex: 1 }} />
              {!isDefault && (
                <button style={S.btnSm} onClick={() => handleSetDefault(c.channelId)}>Set as default</button>
              )}
              <button
                style={{ ...S.btnSm, ...S.btnPrimary }}
                onClick={() => setEditing({ form: emptyForm(c.channelId), index: null })}
              >+ Add binding</button>
            </div>
            {myBindings.length === 0 ? (
              <div style={{ fontSize: '0.78rem', color: '#94a3b8' }}>No bindings on this channel.</div>
            ) : myBindings.map(b => (
              <div key={`${b.channelId}-${b.index}`} style={S.bindingRow}>
                <span style={{ ...S.badge, background: '#eef2ff', color: '#4338ca', borderColor: '#c7d2fe' }}>{b.tier}</span>
                <span style={{ flex: 1, fontFamily: 'monospace', fontSize: '0.86rem', color: '#475569' }}>{describe(b)}</span>
                {b.sessionScope && <span style={S.badge}>{b.sessionScope}</span>}
                <button style={S.btnSm} onClick={() => setEditing({ form: bindingToForm(b), index: b.index })}>Edit</button>
                <button style={{ ...S.btnSm, color: '#dc2626', borderColor: '#fca5a5' }} onClick={() => handleDelete(b)}>Delete</button>
              </div>
            ))}
          </div>
        );
      })}

      {editing && (
        <BindingDialog
          state={editing.form}
          isNew={editing.index == null}
          onChange={form => setEditing({ ...editing, form })}
          onCancel={() => setEditing(null)}
          onSave={handleSave}
        />
      )}
    </div>
  );
}

interface DialogProps {
  state: FormState;
  isNew: boolean;
  onChange: (s: FormState) => void;
  onCancel: () => void;
  onSave: () => void;
}

const D: Record<string, React.CSSProperties> = {
  scrim: {
    position: 'fixed', inset: 0, background: 'rgba(15,23,42,0.45)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 50,
    backdropFilter: 'blur(2px)',
  },
  modal: {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 16,
    padding: '28px 30px', width: 600, maxWidth: '92vw',
    boxShadow: '0 24px 60px rgba(15,23,42,0.18), 0 4px 12px rgba(15,23,42,0.06)',
  },
  title: { margin: '0 0 16px', fontSize: '1.15rem', color: '#0f172a', fontWeight: 700, letterSpacing: '-0.01em' },
  actions: { display: 'flex', justifyContent: 'flex-end', gap: 12, marginTop: 24 },
};

function BindingDialog({ state, isNew, onChange, onCancel, onSave }: DialogProps) {
  const tierField = TIERS.find(t => t.value === state.tier)?.field;

  return (
    <div style={D.scrim} onClick={onCancel}>
      <div style={D.modal} onClick={e => e.stopPropagation()}>
        <h3 style={D.title}>{isNew ? 'Add binding' : 'Edit binding'} on {state.channelId}</h3>

        <div style={S.formGrid}>
          <div>
            <label style={S.fieldLabel}>Tier (evaluation order)</label>
            <select
              style={S.input}
              value={state.tier}
              onChange={e => onChange({ ...state, tier: e.target.value as BindingTier })}
            >
              {TIERS.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
            </select>
          </div>
          <div>
            <label style={S.fieldLabel}>Session scope (optional)</label>
            <select
              style={S.input}
              value={state.sessionScope}
              onChange={e => onChange({ ...state, sessionScope: e.target.value as FormState['sessionScope'] })}
            >
              <option value="">— inherit channel —</option>
              {SCOPES.map(s => <option key={s.value} value={s.value}>{s.label}</option>)}
            </select>
          </div>
        </div>

        {tierField === 'peer' && (
          <div style={{ marginTop: 10 }}>
            <label style={S.fieldLabel}>Peer ID</label>
            <input style={S.input} value={state.peer} onChange={e => onChange({ ...state, peer: e.target.value })} placeholder="e.g. user-42" />
          </div>
        )}
        {tierField === 'parentPeer' && (
          <div style={{ marginTop: 10 }}>
            <label style={S.fieldLabel}>Parent peer</label>
            <input style={S.input} value={state.parentPeer} onChange={e => onChange({ ...state, parentPeer: e.target.value })} />
          </div>
        )}
        {tierField === 'roles' && (
          <div style={S.formGrid}>
            <div>
              <label style={S.fieldLabel}>Guild</label>
              <input style={S.input} value={state.guild} onChange={e => onChange({ ...state, guild: e.target.value })} />
            </div>
            <div>
              <label style={S.fieldLabel}>Roles (comma-separated)</label>
              <input style={S.input} value={state.roles} onChange={e => onChange({ ...state, roles: e.target.value })} placeholder="admin, support" />
            </div>
          </div>
        )}
        {tierField === 'guild' && (
          <div style={{ marginTop: 10 }}>
            <label style={S.fieldLabel}>Guild</label>
            <input style={S.input} value={state.guild} onChange={e => onChange({ ...state, guild: e.target.value })} />
          </div>
        )}
        {tierField === 'team' && (
          <div style={{ marginTop: 10 }}>
            <label style={S.fieldLabel}>Team</label>
            <input style={S.input} value={state.team} onChange={e => onChange({ ...state, team: e.target.value })} />
          </div>
        )}
        {tierField === 'account' && (
          <div style={{ marginTop: 10 }}>
            <label style={S.fieldLabel}>Account</label>
            <input style={S.input} value={state.account} onChange={e => onChange({ ...state, account: e.target.value })} />
          </div>
        )}
        {tierField === 'channel' && (
          <div style={{ marginTop: 10 }}>
            <label style={S.fieldLabel}>Channel match (leave blank for catch-all)</label>
            <input style={S.input} value={state.channel} onChange={e => onChange({ ...state, channel: e.target.value })} placeholder={state.channelId} />
          </div>
        )}

        <div style={D.actions}>
          <button style={S.btnSm} onClick={onCancel}>Cancel</button>
          <button style={{ ...S.btnSm, ...S.btnPrimary }} onClick={onSave}>{isNew ? 'Create' : 'Save'}</button>
        </div>
      </div>
    </div>
  );
}
