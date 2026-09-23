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
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useControlPlaneScope } from '@/app/ScopeContext';
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
import { AgentPicker } from '../components/AgentPicker';
import { weixin, type WeixinLinkFlow } from '@/api/weixin';

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
  const scope = useControlPlaneScope();
  const admin = scope.roles.some(r => ['developer', 'admin'].includes(r));
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [channels, setChannels] = useState<ChannelInfo[]>([]);
  const [types, setTypes] = useState<ChannelTypeSpec[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [creating, setCreating] = useState(params.get('create') === 'weixin');

  async function refresh() {
    setLoading(true);
    setErr(null);
    try {
      const [c, t] = await Promise.all([listChannels(), listChannelTypes()]);
      setChannels(c);
      setTypes(t);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void refresh(); }, []);

  async function toggleDisabled(c: ChannelInfo) {
    try {
      if (c.disabled) await enableChannel(c.channelId);
      else await disableChannel(c.channelId);
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }

  async function handleDelete(c: ChannelInfo) {
    if (!confirm(`Delete channel '${c.channelId}'? This removes its entry and all bindings.`)) return;
    try {
      await deleteChannel(c.channelId);
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }

  const sorted = useMemo(
    () => [...channels].sort((a, b) => a.channelId.localeCompare(b.channelId)),
    [channels],
  );



  return (
    <div className="console-page-legacy" style={S.root}>
      <div style={S.header}>
        <h1 style={S.title}>Channels</h1>
        {admin && <button style={S.primaryBtn} onClick={() => setCreating(true)}>＋ New channel</button>}
      </div>

      <p style={S.blurb}>
        Channels are external ingress connections for chat platforms and webhook providers. Configure
        credentials and routing here; Agent pages show the subset targeting that logical Agent.
      </p>

      {loading && <div style={{ color: '#64748b', fontSize: '0.95rem' }}>Loading…</div>}
      {err && <div style={S.err}>{err}</div>}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(320px,1fr))', gap: 18 }}>
        {sorted.map(c => {
          const tb = typeBadge(c.type ?? 'unknown');
          return (
            <div
              key={c.channelId}
              style={S.card}
              onClick={() => navigate(scope.scopedPath(`/agent-center/entrypoints/${encodeURIComponent(c.channelId)}`))}
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
                {c.dmScope && <span style={S.badge}>{c.dmScope}</span>}
                {c.defaultAgentId && (
                  <span style={{ ...S.badge, background: '#eef2ff', color: '#4338ca', borderColor: '#c7d2fe' }}>
                    default → {c.defaultAgentId}
                  </span>
                )}
                {c.disabled && (
                  <span style={{ ...S.badge, background: '#fee2e2', color: '#b91c1c', borderColor: '#fca5a5' }}>
                    disabled
                  </span>
                )}
                {!c.disabled && c.started && (
                  <span style={{ ...S.badge, background: '#dcfce7', color: '#166534', borderColor: '#86efac' }}>
                    running
                  </span>
                )}
              </div>
              {admin && <div
                style={{ display: 'flex', gap: 8, marginTop: 6 }}
                onClick={e => e.stopPropagation()}
              >
                <button style={S.rowBtn} onClick={() => c.type === 'weixin' ? navigate(scope.scopedPath(`/agent-center/entrypoints/${encodeURIComponent(c.channelId)}`)) : void toggleDisabled(c)}>
                  {c.type === 'weixin' ? '管理微信连接' : c.disabled ? 'Enable' : 'Disable'}
                </button>
                <button
                  style={{ ...S.rowBtn, color: '#dc2626', borderColor: '#fca5a5' }}
                  onClick={() => handleDelete(c)}
                >
                  Delete
                </button>
              </div>}
            </div>
          );
        })}
        {!loading && sorted.length === 0 && (
          <div style={{ color: '#94a3b8', fontSize: '0.92rem', fontStyle: 'italic' }}>
            No channels registered. Click + New channel to add one.
          </div>
        )}
      </div>

      {creating && !loading && admin && (
        <ChannelCreateDialog
          types={types}
          initialType={params.get('create') === 'weixin' ? 'weixin' : undefined}
          initialAgentId={params.get('agentId') || ''}
          onClose={() => setCreating(false)}
          onCreated={(id, link) => {
            setCreating(false);
            void refresh();
            navigate(scope.scopedPath(`/agent-center/entrypoints/${encodeURIComponent(id)}`), { state: link ? { weixinLink: { channelId: id, ...link } } : null });
          }}
        />
      )}
    </div>
  );
}

interface CreateProps {
  types: ChannelTypeSpec[];
  initialType?: string;
  initialAgentId?: string;
  onClose: () => void;
  onCreated: (channelId: string, link?: { flow?: WeixinLinkFlow; error?: string }) => void;
}

function ChannelCreateDialog({ types, initialType, initialAgentId, onClose, onCreated }: CreateProps) {
  const scope = useControlPlaneScope();
  const namespace = scope.namespaces.find(n => n.tenant === scope.tenant && n.name === scope.namespace);
  const [channelId, setChannelId] = useState('');
  const [type, setType] = useState(initialType ?? types[0]?.type ?? '');
  const [dmScope, setDmScope] = useState('PER_PEER');
  const [defaultAgentId, setDefaultAgentId] = useState(initialAgentId || '');
  const [creds, setCreds] = useState<Record<string, string>>(
    () => credentialsFromProperties(types[0], undefined),
  );
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [busy, setBusy] = useState(false);
  const [preparingQr, setPreparingQr] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const typeSpec = types.find((t) => t.type === type);

  function onTypeChange(next: string) {
    setType(next);
    setCreds(credentialsFromProperties(types.find((t) => t.type === next), undefined));
  }

  async function handleSave() {
    setErr(null);
    if (!channelId.trim()) { setErr('channelId is required'); return; }
    if (!type) { setErr('platform is required'); return; }
    if (type === 'weixin' && !defaultAgentId.trim()) { setErr('请选择接收微信私聊的默认 Agent。'); return; }
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
      if (type === 'weixin') {
        setPreparingQr(true);
        // Creation succeeded even if the provider is temporarily unavailable.
        // Continue to the saved Channel so retrying cannot create a duplicate.
        try { onCreated(created.channelId, { flow: await weixin.start(created.channelId) }); }
        catch (reason) { onCreated(created.channelId, { error: reason instanceof Error ? reason.message : '二维码暂时无法生成，请重试。' }); }
      } else onCreated(created.channelId);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
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
    <div style={scrim} onClick={() => { if (!busy) onClose(); }}>
      <div role="dialog" aria-modal="true" aria-labelledby="channel-create-title" style={modal} onClick={e => e.stopPropagation()}>
        <h3 id="channel-create-title" style={{ margin: '0 0 16px', fontSize: '1.15rem', color: '#0f172a', fontWeight: 700 }}>
          New channel
        </h3>

        <div className="grid gap-3.5 sm:grid-cols-2">
          <div>
            <label style={S.formField}>Channel id</label>
            <input
              style={S.input}
              aria-label="Channel id"
              value={channelId}
              onChange={e => setChannelId(e.target.value)}
              placeholder={type === 'weixin' ? 'e.g. weixin-personal' : 'e.g. dingtalk-sales'}
              disabled={busy}
            />
          </div>
          <div>
            <label style={S.formField}>Platform</label>
            <select aria-label="Platform" style={S.input} value={type} disabled={busy} onChange={e => onTypeChange(e.target.value)}>
              {types.length === 0 && <option value="">— no platforms —</option>}
              {types.map(t => <option key={t.type} value={t.type}>{t.label}</option>)}
            </select>
          </div>
          <div>
            <label style={S.formField}>Conversation isolation</label>
            <select style={S.input} value={dmScope} disabled={busy} onChange={e => setDmScope(e.target.value)}>
              {DM_SCOPES.map(s => (
                <option key={s} value={s}>
                  {s === 'PER_PEER' ? 'Per person' : 'Shared inbox'}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label style={S.formField}>Default Agent</label>
            <AgentPicker value={defaultAgentId} onChange={setDefaultAgentId} disabled={busy} required={type === 'weixin'} aria-label="Channel default Agent" />
          </div>
        </div>

        <div style={{ marginTop: 16 }}>
          {type === 'weixin' ? <div className="border-l-2 border-emerald-600 pl-4"><p className="text-sm font-medium text-emerald-900">使用微信扫码连接</p><p className="mt-1 text-sm leading-6 text-emerald-800">创建后会显示授权二维码。首次对话还需关联聊天账号。</p>{namespace && namespace.kind !== 'personal' && <p className="mt-2 text-sm text-amber-800">普通微信私聊仅支持个人空间。当前为共享空间，请在个人空间中创建 Channel 并选择自己的 Agent。</p>}</div> : <><label style={S.formField}>Credentials</label><PlatformCredentialsForm
            spec={typeSpec}
            values={creds}
            onChange={setCreds}
            showAdvanced={showAdvanced}
            onToggleAdvanced={() => setShowAdvanced((v) => !v)}
          /></>}
        </div>

        {err && <div style={{ color: '#dc2626', fontSize: '0.9rem', marginTop: 10 }}>{err}</div>}

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12, marginTop: 24 }}>
          <button style={S.rowBtn} onClick={onClose} disabled={busy}>Cancel</button>
          <button style={{ ...S.rowBtn, ...S.primaryBtn }} onClick={handleSave} disabled={busy}>
            {preparingQr ? '正在生成二维码…' : busy ? 'Creating…' : type === 'weixin' ? '创建并连接微信' : 'Create'}
          </button>
        </div>
      </div>
    </div>
  );
}
