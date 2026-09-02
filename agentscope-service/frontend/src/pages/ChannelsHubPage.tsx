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
import { Navigate, useNavigate } from 'react-router-dom';
import { resolveApiErrorMessage } from '@/api/errors';
import { isAdmin } from '../api/auth';
import {
  ChannelInfo,
  ChannelTypeSpec,
  ChannelUpsertRequest,
  createChannel,
  deleteChannel,
  disableChannel,
  enableChannel,
  listChannelTypes,
  listChannels,
} from '../api/channels';
import PlatformCredentialsForm, {
  credentialsFromProperties,
  propertiesFromCredentials,
} from '../components/PlatformCredentialsForm';
import { useT } from '@/i18n';

const S: Record<string, React.CSSProperties> = {
  root: { padding: '40px 44px', maxWidth: 1200 },
  header: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 },
  title: { margin: 0, fontSize: '1.75rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.02em' },
  blurb: { margin: '0 0 24px', color: '#64748b', fontSize: '1rem', lineHeight: 1.6, maxWidth: 760 },
  primaryBtn: {
    display: 'inline-flex', alignItems: 'center', gap: 8,
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none',
    borderRadius: 10, padding: '11px 20px', fontSize: '0.95rem', fontWeight: 600,
    cursor: 'pointer',
    boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  card: {
    position: 'relative', background: '#ffffff', border: '1px solid #e2e8f0',
    borderRadius: 14, padding: '20px 22px',
    display: 'flex', flexDirection: 'column', gap: 10,
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
    transition: 'transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease',
    cursor: 'pointer',
  },
  badge: {
    padding: '3px 10px', borderRadius: 999, fontSize: '0.72rem', fontWeight: 600,
    background: '#f1f5f9', color: '#475569', border: '1px solid #e2e8f0',
    whiteSpace: 'nowrap',
  },
  err: { color: '#dc2626', fontSize: '0.95rem', marginBottom: 16 },
  rowBtn: {
    padding: '7px 14px', fontSize: '0.84rem', fontWeight: 500, borderRadius: 8, cursor: 'pointer',
    border: '1px solid #cbd5e1', background: '#ffffff', color: '#475569',
  },
  formField: { display: 'block', fontSize: '0.85rem', color: '#475569', marginBottom: 6, fontWeight: 500 },
  input: {
    width: '100%', boxSizing: 'border-box', padding: '10px 12px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 8,
    color: '#0f172a', fontSize: '0.92rem',
  },
};

const DM_SCOPES = ['PER_PEER', 'MAIN'];

function typeBadge(type: string): { bg: string; fg: string; bd: string } {
  switch (type) {
    case 'chatui':   return { bg: '#eef2ff', fg: '#4338ca', bd: '#c7d2fe' };
    case 'dingtalk': return { bg: '#fef3c7', fg: '#92400e', bd: '#fcd34d' };
    case 'wecom':    return { bg: '#dcfce7', fg: '#166534', bd: '#86efac' };
    default:         return { bg: '#f1f5f9', fg: '#475569', bd: '#e2e8f0' };
  }
}

export default function ChannelsHubPage() {
  const t = useT();
  const admin = isAdmin();
  const navigate = useNavigate();
  const [channels, setChannels] = useState<ChannelInfo[]>([]);
  const [types, setTypes] = useState<ChannelTypeSpec[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  async function refresh() {
    setLoading(true);
    setErr(null);
    try {
      const [c, t] = await Promise.all([listChannels(), listChannelTypes()]);
      setChannels(c);
      setTypes(t);
    } catch (e: unknown) {
      setErr(resolveApiErrorMessage(e, t('managed.common.requestFailed')));
    } finally {
      setLoading(false);
    }
  }

  // Initial data load must not repeat when the locale changes.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { void refresh(); }, []);

  async function toggleDisabled(c: ChannelInfo) {
    try {
      if (c.disabled) await enableChannel(c.channelId);
      else await disableChannel(c.channelId);
      await refresh();
    } catch (e: unknown) {
      setErr(resolveApiErrorMessage(e, t('managed.common.requestFailed')));
    }
  }

  async function handleDelete(c: ChannelInfo) {
    if (!confirm(t('managed.channels.confirmDelete', { channelId: c.channelId }))) return;
    try {
      await deleteChannel(c.channelId);
      await refresh();
    } catch (e: unknown) {
      setErr(resolveApiErrorMessage(e, t('managed.common.requestFailed')));
    }
  }

  const sorted = useMemo(
    () => [...channels].sort((a, b) => a.channelId.localeCompare(b.channelId)),
    [channels],
  );

  if (!admin) {
    return <Navigate to="/agents" replace />;
  }

  return (
    <div style={S.root}>
      <div style={S.header}>
        <h1 style={S.title}>{t('navigation.managed.channels')}</h1>
        <button style={S.primaryBtn} onClick={() => setCreating(true)}>
          ＋ {t('managed.channels.new')}
        </button>
      </div>

      <p style={S.blurb}>
        {t('managed.channels.description')}
      </p>

      {loading && (
        <div style={{ color: '#64748b', fontSize: '0.95rem' }}>
          {t('common.loading')}
        </div>
      )}
      {err && <div style={S.err}>{err}</div>}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(320px,1fr))', gap: 18 }}>
        {sorted.map(c => {
          const tb = typeBadge(c.type ?? 'unknown');
          return (
            <div
              key={c.channelId}
              style={S.card}
              onClick={() => navigate(`/channels/${encodeURIComponent(c.channelId)}`)}
              onMouseEnter={e => {
                e.currentTarget.style.transform = 'translateY(-2px)';
                e.currentTarget.style.boxShadow = '0 8px 24px rgba(15,23,42,0.08), 0 2px 6px rgba(15,23,42,0.04)';
                e.currentTarget.style.borderColor = '#c7d2fe';
              }}
              onMouseLeave={e => {
                e.currentTarget.style.transform = 'translateY(0)';
                e.currentTarget.style.boxShadow = '0 1px 3px rgba(15,23,42,0.04)';
                e.currentTarget.style.borderColor = '#e2e8f0';
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <span style={{ fontWeight: 600, fontSize: '1.05rem', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {c.channelId}
                </span>
                <span style={{ ...S.badge, background: tb.bg, color: tb.fg, borderColor: tb.bd }}>
                  {c.type}
                </span>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                {c.dmScope && (
                  <span style={S.badge}>
                    {c.dmScope === 'PER_PEER'
                      ? t('managed.agentChannels.perPerson')
                      : c.dmScope === 'MAIN'
                        ? t('managed.agentChannels.sharedInbox')
                        : c.dmScope}
                  </span>
                )}
                {c.defaultAgentId && (
                  <span style={{ ...S.badge, background: '#eef2ff', color: '#4338ca', borderColor: '#c7d2fe' }}>
                    {t('managed.channels.defaultAgent', {
                      agentId: c.defaultAgentId,
                    })}
                  </span>
                )}
                {c.disabled && (
                  <span style={{ ...S.badge, background: '#fee2e2', color: '#b91c1c', borderColor: '#fca5a5' }}>
                    {t('managed.channels.disabled')}
                  </span>
                )}
                {!c.disabled && c.started && (
                  <span style={{ ...S.badge, background: '#dcfce7', color: '#166534', borderColor: '#86efac' }}>
                    {t('status.running')}
                  </span>
                )}
              </div>
              <div
                style={{ display: 'flex', gap: 8, marginTop: 6 }}
                onClick={e => e.stopPropagation()}
              >
                <button style={S.rowBtn} onClick={() => toggleDisabled(c)}>
                  {c.disabled
                    ? t('managed.channels.enable')
                    : t('managed.channels.disable')}
                </button>
                <button
                  style={{ ...S.rowBtn, color: '#dc2626', borderColor: '#fca5a5' }}
                  onClick={() => handleDelete(c)}
                >
                  {t('common.delete')}
                </button>
              </div>
            </div>
          );
        })}
        {!loading && sorted.length === 0 && (
          <div style={{ color: '#94a3b8', fontSize: '0.92rem', fontStyle: 'italic' }}>
            {t('managed.channels.empty')}
          </div>
        )}
      </div>

      {creating && (
        <ChannelCreateDialog
          types={types}
          onClose={() => setCreating(false)}
          onCreated={(id) => {
            setCreating(false);
            void refresh();
            navigate(`/channels/${encodeURIComponent(id)}`);
          }}
        />
      )}
    </div>
  );
}

interface CreateProps {
  types: ChannelTypeSpec[];
  onClose: () => void;
  onCreated: (channelId: string) => void;
}

function ChannelCreateDialog({ types, onClose, onCreated }: CreateProps) {
  const t = useT();
  const [channelId, setChannelId] = useState('');
  const [type, setType] = useState(types[0]?.type ?? '');
  const [dmScope, setDmScope] = useState('PER_PEER');
  const [defaultAgentId, setDefaultAgentId] = useState('');
  const [creds, setCreds] = useState<Record<string, string>>(
    () => credentialsFromProperties(types[0], undefined),
  );
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const typeSpec = types.find((t) => t.type === type);

  function onTypeChange(next: string) {
    setType(next);
    setCreds(credentialsFromProperties(types.find((t) => t.type === next), undefined));
  }

  async function handleSave() {
    setErr(null);
    if (!channelId.trim()) {
      setErr(t('managed.channels.channelIdRequired'));
      return;
    }
    if (!type) {
      setErr(t('managed.channels.platformRequired'));
      return;
    }
    const req: ChannelUpsertRequest = {
      channelId: channelId.trim(),
      type,
      dmScope: dmScope || 'PER_PEER',
      defaultAgentId: defaultAgentId.trim() || null,
      properties: propertiesFromCredentials(typeSpec, creds, false),
    };
    setBusy(true);
    try {
      const created = await createChannel(req);
      onCreated(created.channelId);
    } catch (e: unknown) {
      setErr(resolveApiErrorMessage(e, t('managed.common.requestFailed')));
    } finally {
      setBusy(false);
    }
  }

  const scrim: React.CSSProperties = {
    position: 'fixed', inset: 0, background: 'rgba(15,23,42,0.45)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 50,
    backdropFilter: 'blur(2px)',
  };
  const modal: React.CSSProperties = {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 16,
    padding: '28px 30px', width: 680, maxWidth: '92vw', maxHeight: '90vh', overflow: 'auto',
    boxShadow: '0 24px 60px rgba(15,23,42,0.18), 0 4px 12px rgba(15,23,42,0.06)',
  };

  return (
    <div style={scrim} onClick={onClose}>
      <div style={modal} onClick={e => e.stopPropagation()}>
        <h3 style={{ margin: '0 0 16px', fontSize: '1.15rem', color: '#0f172a', fontWeight: 700 }}>
          {t('managed.channels.newIdentity')}
        </h3>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          <div>
            <label style={S.formField}>{t('managed.agentChannels.channelId')}</label>
            <input
              style={S.input}
              value={channelId}
              onChange={e => setChannelId(e.target.value)}
              placeholder={t('managed.channels.channelIdPlaceholder')}
            />
          </div>
          <div>
            <label style={S.formField}>{t('managed.agentChannels.platform')}</label>
            <select style={S.input} value={type} onChange={e => onTypeChange(e.target.value)}>
              {types.length === 0 && (
                <option value="">{t('managed.channels.noPlatforms')}</option>
              )}
              {types.map(t => <option key={t.type} value={t.type}>{t.label}</option>)}
            </select>
          </div>
          <div>
            <label style={S.formField}>{t('managed.agentChannels.isolation')}</label>
            <select style={S.input} value={dmScope} onChange={e => setDmScope(e.target.value)}>
              {DM_SCOPES.map(s => (
                <option key={s} value={s}>
                  {s === 'PER_PEER'
                    ? t('managed.agentChannels.perPerson')
                    : t('managed.agentChannels.sharedInbox')}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label style={S.formField}>{t('managed.channels.defaultAgentId')}</label>
            <input
              style={S.input}
              value={defaultAgentId}
              onChange={e => setDefaultAgentId(e.target.value)}
              placeholder={t('managed.channels.defaultAgentPlaceholder')}
            />
          </div>
        </div>

        <div style={{ marginTop: 16 }}>
          <label style={S.formField}>{t('managed.agentChannels.credentials')}</label>
          <PlatformCredentialsForm
            spec={typeSpec}
            values={creds}
            onChange={setCreds}
            showAdvanced={showAdvanced}
            onToggleAdvanced={() => setShowAdvanced((v) => !v)}
          />
        </div>

        {err && <div style={{ color: '#dc2626', fontSize: '0.9rem', marginTop: 10 }}>{err}</div>}

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12, marginTop: 24 }}>
          <button style={S.rowBtn} onClick={onClose} disabled={busy}>
            {t('common.cancel')}
          </button>
          <button style={{ ...S.rowBtn, ...S.primaryBtn }} onClick={handleSave} disabled={busy}>
            {busy ? t('managed.common.creating') : t('managed.common.create')}
          </button>
        </div>
      </div>
    </div>
  );
}
