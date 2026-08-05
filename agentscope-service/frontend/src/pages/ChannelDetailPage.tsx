/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import React, { useEffect, useMemo, useState } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router-dom';
import { isAdmin } from '../api/auth';
import {
  BindingConfigEntry,
  ChannelDetail,
  ChannelTypeSpec,
  ChannelUpsertRequest,
  deleteChannel,
  disableChannel,
  enableChannel,
  getChannelDetail,
  listChannelTypes,
  resolveCallbackUrl,
  updateChannel,
} from '../api/channels';
import PlatformCredentialsForm, {
  credentialsFromProperties,
  propertiesFromCredentials,
} from '../components/PlatformCredentialsForm';

const DM_SCOPES = ['MAIN', 'PER_PEER'];

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
  callout: {
    marginTop: 14, padding: '12px 14px', borderRadius: 10,
    background: '#f8fafc', border: '1px solid #e2e8f0', fontSize: '0.86rem', color: '#334155',
  },
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
  const [types, setTypes] = useState<ChannelTypeSpec[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  const [type, setType] = useState('');
  const [dmScope, setDmScope] = useState('PER_PEER');
  const [defaultAgentId, setDefaultAgentId] = useState('');
  const [creds, setCreds] = useState<Record<string, string>>({});
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [bindings, setBindings] = useState<BindingConfigEntry[]>([]);

  const [editIdx, setEditIdx] = useState<number | null>(null);
  const [editForm, setEditForm] = useState<BindingForm | null>(null);

  const typeSpec = useMemo(() => types.find((t) => t.type === type), [types, type]);
  const callbackUrl = useMemo(
    () => resolveCallbackUrl(typeSpec, channelId),
    [typeSpec, channelId],
  );

  async function load() {
    setErr(null);
    try {
      const [d, t] = await Promise.all([getChannelDetail(channelId), listChannelTypes()]);
      setDetail(d);
      setTypes(t);
      setType(d.type);
      setDmScope(d.dmScope ?? 'PER_PEER');
      setDefaultAgentId(d.defaultAgentId ?? '');
      const spec = t.find((x) => x.type === d.type);
      setCreds(credentialsFromProperties(spec, d.properties));
      setBindings(d.bindings ?? []);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }

  useEffect(() => { void load(); /* eslint-disable-next-line */ }, [channelId]);

  function onTypeChange(next: string) {
    if (next === type) return;
    if (!confirm('Switching platform clears the credential form. Continue?')) return;
    setType(next);
    const spec = types.find((t) => t.type === next);
    setCreds(credentialsFromProperties(spec, undefined));
  }

  async function persist(overrides?: Partial<ChannelUpsertRequest>) {
    setErr(null);
    setInfo(null);
    const props = overrides?.properties
      ?? propertiesFromCredentials(typeSpec, creds, true);
    const req: ChannelUpsertRequest = {
      type: overrides?.type ?? type,
      dmScope: overrides?.dmScope !== undefined ? overrides.dmScope : (dmScope || 'PER_PEER'),
      defaultAgentId: overrides?.defaultAgentId !== undefined
        ? overrides.defaultAgentId
        : (defaultAgentId.trim() || null),
      properties: props,
      bindings: overrides?.bindings ?? bindings,
    };
    try {
      const updated = await updateChannel(channelId, req);
      setDetail(updated);
      setBindings(updated.bindings ?? []);
      const spec = types.find((x) => x.type === updated.type);
      setCreds(credentialsFromProperties(spec, updated.properties));
      setInfo('Saved. Scheduler will pick up changes on the next refresh.');
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
      <div style={S.subtle}>IM identity configuration. Credentials switch with the selected platform.</div>

      {err && <div style={{ ...S.err, marginTop: 16 }}>{err}</div>}
      {info && <div style={{ ...S.ok, marginTop: 16 }}>{info}</div>}

      {detail && (
        <>
          <div style={{ ...S.section, marginTop: 18 }}>
            <div style={S.sectionHead}>
              <h2 style={S.sectionTitle}>Configuration</h2>
              <span style={S.badge}>{status}</span>
              {detail.lastError ? <span style={{ ...S.badge, color: '#dc2626' }}>{detail.lastError}</span> : null}
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
                <label style={S.field}>Platform</label>
                <select style={S.input} value={type} onChange={e => onTypeChange(e.target.value)}>
                  {types.map(t => <option key={t.type} value={t.type}>{t.label}</option>)}
                </select>
              </div>
              <div>
                <label style={S.field}>Conversation isolation</label>
                <select style={S.input} value={dmScope} onChange={e => setDmScope(e.target.value)}>
                  {DM_SCOPES.map(s => (
                    <option key={s} value={s}>
                      {s === 'PER_PEER' ? 'Per person (PER_PEER)' : 'Shared inbox (MAIN)'}
                    </option>
                  ))}
                </select>
              </div>
              <div style={{ gridColumn: '1 / span 2' }}>
                <label style={S.field}>Default agent id</label>
                <input
                  style={S.input}
                  value={defaultAgentId}
                  onChange={e => setDefaultAgentId(e.target.value)}
                  placeholder="e.g. default"
                />
              </div>
            </div>
            <div style={{ marginTop: 18 }}>
              <h3 style={{ ...S.sectionTitle, fontSize: '0.95rem', marginBottom: 10 }}>Credentials</h3>
              <PlatformCredentialsForm
                spec={typeSpec}
                values={creds}
                onChange={setCreds}
                showAdvanced={showAdvanced}
                onToggleAdvanced={() => setShowAdvanced((v) => !v)}
              />
            </div>
            {callbackUrl ? (
              <div style={S.callout}>
                <strong>Callback / webhook URL</strong>
                <div style={{ fontFamily: 'monospace', marginTop: 6, wordBreak: 'break-all' }}>{callbackUrl}</div>
                <div style={{ ...S.subtle, marginTop: 6 }}>Paste this into the platform developer console.</div>
              </div>
            ) : null}
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 16 }}>
              <button style={{ ...S.btn, ...S.btnPrimary }} onClick={() => persist()}>Save configuration</button>
            </div>
          </div>

          <div style={S.section}>
            <div style={S.sectionHead}>
              <h2 style={S.sectionTitle}>Transfer rules</h2>
              <span style={S.subtle}>({bindings.length})</span>
              <span style={{ flex: 1 }} />
              <button style={{ ...S.btn, ...S.btnPrimary }} onClick={startAddBinding}>+ Add rule</button>
            </div>
            <div style={{ ...S.subtle, marginBottom: 12 }}>
              Route specific peers / groups to another agent. Leave selectors blank for catch-all.
            </div>
            {bindings.length === 0 ? (
              <div style={{ fontSize: '0.85rem', color: '#94a3b8' }}>
                No rules. Inbound messages route to <code>defaultAgentId</code>.
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
          {isNew ? 'Add transfer rule' : 'Edit transfer rule'}
        </h3>
        <p style={{ ...S.subtle, margin: '0 0 14px' }}>
          Fill the most-specific selector (e.g. a DingTalk staff id as peer). Leave others blank.
        </p>

        <div style={S.grid2}>
          <div>
            <label style={S.field}>Hand off to agent</label>
            <input
              style={S.input}
              value={form.agentId}
              onChange={e => onChange({ ...form, agentId: e.target.value })}
              placeholder="e.g. support-bot"
            />
          </div>
          <div>
            <label style={S.field}>Isolation override</label>
            <select
              style={S.input}
              value={form.sessionScope}
              onChange={e => onChange({ ...form, sessionScope: e.target.value })}
            >
              <option value="">— inherit channel —</option>
              {DM_SCOPES.map(s => <option key={s} value={s}>{s}</option>)}
            </select>
          </div>
          <div>
            <label style={S.field}>peer (e.g. direct:staffId)</label>
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
