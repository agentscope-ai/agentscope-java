import React, { useEffect, useMemo, useState } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router-dom';
import { isAdmin } from '../api/auth';
import {
  BindingConfigEntry,
  ChannelDetail,
  ChannelUpsertRequest,
  deleteChannel,
  disableChannel,
  enableChannel,
  getChannelDetail,
  listChannelTypes,
  updateChannel,
} from '../api/channels';

const DM_SCOPES = ['', 'MAIN', 'PER_PEER', 'PER_CHANNEL_PEER', 'PER_ACCOUNT_CHANNEL_PEER'];
const SCOPES = DM_SCOPES.filter(Boolean);

const S: Record<string, React.CSSProperties> = {
  root: { padding: '32px 36px', maxWidth: 1100 },
  backLink: {
    background: 'none', border: 'none', cursor: 'pointer', padding: 0,
    color: '#4f46e5', fontSize: '0.88rem', marginBottom: 12,
  },
  title: { margin: '0 0 6px', fontSize: '1.6rem', fontWeight: 700, color: '#0f172a' },
  subtle: { color: '#64748b', fontSize: '0.92rem' },
  section: {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 14,
    padding: '20px 22px', marginBottom: 16,
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
  sectionHead: { display: 'flex', alignItems: 'center', gap: 12, marginBottom: 14 },
  sectionTitle: { fontSize: '1.02rem', fontWeight: 600, color: '#0f172a', margin: 0 },
  field: { display: 'block', fontSize: '0.85rem', color: '#475569', marginBottom: 6, fontWeight: 500 },
  input: {
    width: '100%', boxSizing: 'border-box', padding: '10px 12px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 8,
    color: '#0f172a', fontSize: '0.92rem',
  },
  grid2: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 },
  badge: {
    padding: '3px 10px', borderRadius: 999, fontSize: '0.72rem', fontWeight: 600,
    background: '#f1f5f9', color: '#475569', border: '1px solid #e2e8f0',
  },
  btn: {
    padding: '8px 16px', fontSize: '0.86rem', fontWeight: 500, borderRadius: 8, cursor: 'pointer',
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
  ok: { color: '#16a34a', fontSize: '0.9rem', marginTop: 8 },
};

function describe(b: BindingConfigEntry): string {
  const parts: string[] = [];
  if (b.peer) parts.push(`peer=${b.peer}`);
  if (b.parentPeer) parts.push(`parentPeer=${b.parentPeer}`);
  if (b.guild) parts.push(`guild=${b.guild}`);
  if (b.roles && b.roles.length) parts.push(`roles=${b.roles.join('|')}`);
  if (b.team) parts.push(`team=${b.team}`);
  if (b.account) parts.push(`account=${b.account}`);
  return parts.join(', ') || '(catch-all)';
}

interface BindingForm {
  agentId: string;
  peer: string;
  parentPeer: string;
  guild: string;
  roles: string;
  team: string;
  account: string;
  sessionScope: string;
}

function emptyBindingForm(): BindingForm {
  return { agentId: '', peer: '', parentPeer: '', guild: '', roles: '', team: '', account: '', sessionScope: '' };
}

function bindingToForm(b: BindingConfigEntry): BindingForm {
  return {
    agentId: b.agentId ?? '',
    peer: b.peer ?? '',
    parentPeer: b.parentPeer ?? '',
    guild: b.guild ?? '',
    roles: (b.roles ?? []).join(', '),
    team: b.team ?? '',
    account: b.account ?? '',
    sessionScope: b.sessionScope ?? '',
  };
}

function formToBinding(f: BindingForm): BindingConfigEntry {
  const entry: BindingConfigEntry = { agentId: f.agentId.trim() };
  if (f.peer.trim()) entry.peer = f.peer.trim();
  if (f.parentPeer.trim()) entry.parentPeer = f.parentPeer.trim();
  if (f.guild.trim()) entry.guild = f.guild.trim();
  if (f.roles.trim()) entry.roles = f.roles.split(',').map(s => s.trim()).filter(Boolean);
  if (f.team.trim()) entry.team = f.team.trim();
  if (f.account.trim()) entry.account = f.account.trim();
  if (f.sessionScope) entry.sessionScope = f.sessionScope;
  return entry;
}

export default function ChannelDetailPage() {
  const admin = isAdmin();
  const { channelId = '' } = useParams<{ channelId: string }>();
  const navigate = useNavigate();
  const [detail, setDetail] = useState<ChannelDetail | null>(null);
  const [types, setTypes] = useState<string[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  const [type, setType] = useState('');
  const [dmScope, setDmScope] = useState('');
  const [defaultAgentId, setDefaultAgentId] = useState('');
  const [propsJson, setPropsJson] = useState('{\n}');
  const [bindings, setBindings] = useState<BindingConfigEntry[]>([]);

  const [editIdx, setEditIdx] = useState<number | null>(null);
  const [editForm, setEditForm] = useState<BindingForm | null>(null);

  async function load() {
    setErr(null);
    try {
      const [d, t] = await Promise.all([getChannelDetail(channelId), listChannelTypes()]);
      setDetail(d);
      setTypes(t);
      setType(d.type);
      setDmScope(d.dmScope ?? '');
      setDefaultAgentId(d.defaultAgentId ?? '');
      setPropsJson(d.properties ? JSON.stringify(d.properties, null, 2) : '{\n}');
      setBindings(d.bindings ?? []);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }

  useEffect(() => { void load(); /* eslint-disable-next-line */ }, [channelId]);

  async function persist(overrides?: Partial<ChannelUpsertRequest>) {
    setErr(null);
    setInfo(null);
    let parsedProps: Record<string, unknown> | null = null;
    const body = (overrides?.properties as Record<string, unknown> | null | undefined) ?? undefined;
    if (body === undefined) {
      if (propsJson.trim().length > 0) {
        try {
          const v = JSON.parse(propsJson);
          if (v && typeof v === 'object' && !Array.isArray(v)) parsedProps = v as Record<string, unknown>;
          else throw new Error('properties must be a JSON object');
        } catch (e: unknown) {
          setErr(`properties JSON invalid: ${e instanceof Error ? e.message : String(e)}`);
          return;
        }
      }
    } else {
      parsedProps = body;
    }
    const req: ChannelUpsertRequest = {
      type: overrides?.type ?? type,
      dmScope: overrides?.dmScope !== undefined ? overrides.dmScope : (dmScope || null),
      defaultAgentId: overrides?.defaultAgentId !== undefined ? overrides.defaultAgentId : (defaultAgentId.trim() || null),
      properties: parsedProps,
      bindings: overrides?.bindings ?? bindings,
    };
    try {
      const updated = await updateChannel(channelId, req);
      setDetail(updated);
      setBindings(updated.bindings ?? []);
      setInfo('Saved. Channel hot-reloaded.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }

  async function toggleDisabled() {
    if (!detail) return;
    try {
      if (detail.disabled) await enableChannel(channelId);
      else await disableChannel(channelId);
      await load();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }

  async function handleDeleteChannel() {
    if (!confirm(`Delete channel '${channelId}'? This removes its entry and all bindings.`)) return;
    try {
      await deleteChannel(channelId);
      navigate('/channels');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }

  function startEditBinding(i: number) {
    setEditIdx(i);
    setEditForm(bindingToForm(bindings[i]));
  }

  function startAddBinding() {
    setEditIdx(bindings.length);
    setEditForm(emptyBindingForm());
  }

  async function saveBinding() {
    if (editForm == null || editIdx == null) return;
    if (!editForm.agentId.trim()) { setErr('agentId is required'); return; }
    const next = [...bindings];
    const entry = formToBinding(editForm);
    if (editIdx >= bindings.length) next.push(entry);
    else next[editIdx] = entry;
    setBindings(next);
    setEditIdx(null);
    setEditForm(null);
    await persist({ bindings: next });
  }

  async function deleteBindingAt(i: number) {
    if (!confirm(`Delete binding #${i} (${describe(bindings[i])})?`)) return;
    const next = bindings.filter((_, idx) => idx !== i);
    setBindings(next);
    await persist({ bindings: next });
  }

  const status = useMemo(() => {
    if (!detail) return '';
    if (detail.disabled) return 'disabled';
    return detail.started ? 'running' : 'stopped';
  }, [detail]);

  if (!admin) {
    return <Navigate to="/agents" replace />;
  }

  if (!detail && !err) {
    return <div style={S.root}>Loading…</div>;
  }

  return (
    <div style={S.root}>
      <button style={S.backLink} onClick={() => navigate('/channels')}>← All channels</button>
      <h1 style={S.title}>{channelId}</h1>
      <div style={S.subtle}>Channel-scoped configuration. Saving any field hot-reloads the channel in place.</div>

      {err && <div style={{ ...S.err, marginTop: 16 }}>{err}</div>}
      {info && <div style={{ ...S.ok, marginTop: 16 }}>{info}</div>}

      {detail && (
        <>
          <div style={{ ...S.section, marginTop: 18 }}>
            <div style={S.sectionHead}>
              <h2 style={S.sectionTitle}>Configuration</h2>
              <span style={S.badge}>{status}</span>
              <span style={{ flex: 1 }} />
              <button style={S.btn} onClick={toggleDisabled}>
                {detail.disabled ? 'Enable' : 'Disable'}
              </button>
              <button style={{ ...S.btn, color: '#dc2626', borderColor: '#fca5a5' }} onClick={handleDeleteChannel}>
                Delete
              </button>
            </div>
            <div style={S.grid2}>
              <div>
                <label style={S.field}>Type</label>
                <select style={S.input} value={type} onChange={e => setType(e.target.value)}>
                  {types.map(t => <option key={t} value={t}>{t}</option>)}
                </select>
              </div>
              <div>
                <label style={S.field}>DM scope</label>
                <select style={S.input} value={dmScope} onChange={e => setDmScope(e.target.value)}>
                  <option value="">— default —</option>
                  {SCOPES.map(s => <option key={s} value={s}>{s}</option>)}
                </select>
              </div>
              <div style={{ gridColumn: '1 / span 2' }}>
                <label style={S.field}>Default agent id (used when no binding matches)</label>
                <input
                  style={S.input}
                  value={defaultAgentId}
                  onChange={e => setDefaultAgentId(e.target.value)}
                  placeholder="e.g. default"
                />
              </div>
            </div>
            <div style={{ marginTop: 14 }}>
              <label style={S.field}>Properties (JSON object, type-specific)</label>
              <textarea
                style={{ ...S.input, fontFamily: 'monospace', minHeight: 180, resize: 'vertical' }}
                value={propsJson}
                onChange={e => setPropsJson(e.target.value)}
              />
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 16 }}>
              <button style={{ ...S.btn, ...S.btnPrimary }} onClick={() => persist()}>Save configuration</button>
            </div>
          </div>

          <div style={S.section}>
            <div style={S.sectionHead}>
              <h2 style={S.sectionTitle}>Bindings</h2>
              <span style={S.subtle}>({bindings.length})</span>
              <span style={{ flex: 1 }} />
              <button style={{ ...S.btn, ...S.btnPrimary }} onClick={startAddBinding}>+ Add binding</button>
            </div>
            {bindings.length === 0 ? (
              <div style={{ fontSize: '0.85rem', color: '#94a3b8' }}>
                No bindings. Inbound messages will route to <code>defaultAgentId</code> if set.
              </div>
            ) : bindings.map((b, i) => (
              <div key={i} style={S.bindingRow}>
                <span style={{ ...S.badge, background: '#eef2ff', color: '#4338ca', borderColor: '#c7d2fe' }}>
                  → {b.agentId}
                </span>
                <span style={{ flex: 1, fontFamily: 'monospace', fontSize: '0.86rem', color: '#475569' }}>
                  {describe(b)}
                </span>
                {b.sessionScope && <span style={S.badge}>{b.sessionScope}</span>}
                <button style={S.btn} onClick={() => startEditBinding(i)}>Edit</button>
                <button
                  style={{ ...S.btn, color: '#dc2626', borderColor: '#fca5a5' }}
                  onClick={() => deleteBindingAt(i)}
                >Delete</button>
              </div>
            ))}
          </div>
        </>
      )}

      {editForm && editIdx != null && (
        <BindingDialog
          form={editForm}
          isNew={editIdx >= bindings.length}
          onChange={setEditForm}
          onCancel={() => { setEditForm(null); setEditIdx(null); }}
          onSave={saveBinding}
        />
      )}
    </div>
  );
}

interface DialogProps {
  form: BindingForm;
  isNew: boolean;
  onChange: (f: BindingForm) => void;
  onCancel: () => void;
  onSave: () => void;
}

function BindingDialog({ form, isNew, onChange, onCancel, onSave }: DialogProps) {
  const scrim: React.CSSProperties = {
    position: 'fixed', inset: 0, background: 'rgba(15,23,42,0.45)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 50,
    backdropFilter: 'blur(2px)',
  };
  const modal: React.CSSProperties = {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 16,
    padding: '28px 30px', width: 620, maxWidth: '92vw',
    boxShadow: '0 24px 60px rgba(15,23,42,0.18), 0 4px 12px rgba(15,23,42,0.06)',
  };

  return (
    <div style={scrim} onClick={onCancel}>
      <div style={modal} onClick={e => e.stopPropagation()}>
        <h3 style={{ margin: '0 0 16px', fontSize: '1.15rem', color: '#0f172a', fontWeight: 700 }}>
          {isNew ? 'Add binding' : 'Edit binding'}
        </h3>
        <p style={{ ...S.subtle, margin: '0 0 14px' }}>
          Fill the most-specific selector you need; leave others blank. Routing tries
          peer → parentPeer → guild+roles → guild → team → account in that order.
        </p>

        <div style={S.grid2}>
          <div>
            <label style={S.field}>Target agent id</label>
            <input
              style={S.input}
              value={form.agentId}
              onChange={e => onChange({ ...form, agentId: e.target.value })}
              placeholder="e.g. support-bot"
            />
          </div>
          <div>
            <label style={S.field}>Session scope (optional)</label>
            <select
              style={S.input}
              value={form.sessionScope}
              onChange={e => onChange({ ...form, sessionScope: e.target.value })}
            >
              <option value="">— inherit channel —</option>
              {SCOPES.map(s => <option key={s} value={s}>{s}</option>)}
            </select>
          </div>
          <div>
            <label style={S.field}>peer</label>
            <input style={S.input} value={form.peer} onChange={e => onChange({ ...form, peer: e.target.value })} />
          </div>
          <div>
            <label style={S.field}>parentPeer</label>
            <input style={S.input} value={form.parentPeer} onChange={e => onChange({ ...form, parentPeer: e.target.value })} />
          </div>
          <div>
            <label style={S.field}>guild</label>
            <input style={S.input} value={form.guild} onChange={e => onChange({ ...form, guild: e.target.value })} />
          </div>
          <div>
            <label style={S.field}>roles (comma-separated)</label>
            <input style={S.input} value={form.roles} onChange={e => onChange({ ...form, roles: e.target.value })} />
          </div>
          <div>
            <label style={S.field}>team</label>
            <input style={S.input} value={form.team} onChange={e => onChange({ ...form, team: e.target.value })} />
          </div>
          <div>
            <label style={S.field}>account</label>
            <input style={S.input} value={form.account} onChange={e => onChange({ ...form, account: e.target.value })} />
          </div>
        </div>

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12, marginTop: 24 }}>
          <button style={S.btn} onClick={onCancel}>Cancel</button>
          <button style={{ ...S.btn, ...S.btnPrimary }} onClick={onSave}>{isNew ? 'Create' : 'Save'}</button>
        </div>
      </div>
    </div>
  );
}
