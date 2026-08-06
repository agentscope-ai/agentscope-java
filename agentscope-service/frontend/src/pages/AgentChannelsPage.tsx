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
import { Link, useOutletContext } from 'react-router-dom';
import {
  AgentPresence,
  ChannelTypeSpec,
  PresenceUpsertRequest,
  createAgentPresence,
  deleteAgentPresence,
  listAgentPresences,
  listChannelTypes,
  resolveCallbackUrl,
  updateAgentPresence,
} from '../api/channels';
import PlatformCredentialsForm, {
  credentialsFromProperties,
  propertiesFromCredentials,
} from '../components/PlatformCredentialsForm';
import ChannelBindingTable from '../components/ChannelBindingTable';

const S: Record<string, React.CSSProperties> = {
  root: { padding: '28px 32px', maxWidth: 1100 },
  title: { margin: '0 0 8px', fontSize: '1.4rem', fontWeight: 700, color: '#0f172a' },
  subtle: { color: '#64748b', fontSize: '0.92rem', marginBottom: 20 },
  section: {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 14,
    padding: '20px 22px', marginBottom: 16,
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
  row: {
    display: 'flex', alignItems: 'center', gap: 12, padding: '12px 14px',
    borderRadius: 10, border: '1px solid #f1f5f9', background: '#f8fafc', marginBottom: 8,
  },
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
  },
  err: { color: '#dc2626', fontSize: '0.9rem', marginTop: 8 },
  field: { display: 'block', fontSize: '0.85rem', color: '#475569', marginBottom: 6, fontWeight: 500 },
  input: {
    width: '100%', boxSizing: 'border-box', padding: '10px 12px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 8,
    color: '#0f172a', fontSize: '0.92rem',
  },
  grid2: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 },
};

export default function AgentChannelsPage() {
  const { agentId } = useOutletContext<{ agentId: string }>();
  const [presences, setPresences] = useState<AgentPresence[]>([]);
  const [types, setTypes] = useState<ChannelTypeSpec[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [edit, setEdit] = useState<AgentPresence | null>(null);

  async function load() {
    setErr(null);
    try {
      const [p, t] = await Promise.all([listAgentPresences(agentId), listChannelTypes()]);
      setPresences(p);
      setTypes(t);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }

  useEffect(() => { void load(); /* eslint-disable-next-line */ }, [agentId]);

  return (
    <div style={S.root}>
      <h1 style={S.title}>Connect IM</h1>
      <p style={S.subtle}>
        Give this agent an identity on DingTalk / Feishu / WeCom / GitHub / GitLab.
        Each identity is one bot account; conversations default to per-person isolation.
      </p>

      {err && <div style={S.err}>{err}</div>}

      <div style={S.section}>
        <div style={{ display: 'flex', alignItems: 'center', marginBottom: 14 }}>
          <h2 style={{ margin: 0, fontSize: '1.05rem' }}>IM identities</h2>
          <span style={{ flex: 1 }} />
          <button style={{ ...S.btn, ...S.btnPrimary }} onClick={() => setShowCreate(true)}>
            + Connect platform
          </button>
        </div>

        {presences.length === 0 ? (
          <div style={{ color: '#94a3b8', fontSize: '0.9rem' }}>
            No IM identity yet. Connect a platform so people can chat with this agent.
          </div>
        ) : (
          presences.map((p) => {
            const spec = types.find((t) => t.type === p.platform);
            const cb = p.callbackUrl
              ? (typeof window !== 'undefined' ? `${window.location.origin}${p.callbackUrl}` : p.callbackUrl)
              : resolveCallbackUrl(spec, p.channelId);
            return (
              <div key={p.channelId} style={S.row}>
                <span style={{ fontWeight: 600, flex: 1 }}>{p.channelId}</span>
                <span style={S.badge}>{spec?.label ?? p.platform}</span>
                <span style={S.badge}>
                  {p.isolation === 'shared' ? 'shared inbox' : 'per person'}
                </span>
                <span style={{
                  ...S.badge,
                  background: p.started ? '#dcfce7' : '#f1f5f9',
                  color: p.started ? '#166534' : '#475569',
                }}>
                  {p.enabled ? (p.started ? 'connected' : 'stopped') : 'disabled'}
                </span>
                <Link style={{ ...S.btn, textDecoration: 'none' }} to={`/channels/${encodeURIComponent(p.channelId)}`}>
                  Advanced
                </Link>
                <button style={S.btn} onClick={() => setEdit(p)}>Edit</button>
                <button
                  style={{ ...S.btn, color: '#dc2626', borderColor: '#fca5a5' }}
                  onClick={async () => {
                    if (!confirm(`Disconnect ${p.channelId}?`)) return;
                    try {
                      await deleteAgentPresence(agentId, p.channelId);
                      await load();
                    } catch (e: unknown) {
                      setErr(e instanceof Error ? e.message : String(e));
                    }
                  }}
                >
                  Disconnect
                </button>
                {cb ? (
                  <div style={{ width: '100%', fontSize: '0.78rem', color: '#64748b', fontFamily: 'monospace' }}>
                    {cb}
                  </div>
                ) : null}
                {p.lastError ? (
                  <div style={{ width: '100%', fontSize: '0.78rem', color: '#dc2626' }}>{p.lastError}</div>
                ) : null}
              </div>
            );
          })
        )}
      </div>

      {(showCreate || edit) && (
        <PresenceDialog
          agentId={agentId}
          types={types}
          initial={edit}
          onClose={() => { setShowCreate(false); setEdit(null); }}
          onSaved={async () => {
            setShowCreate(false);
            setEdit(null);
            await load();
          }}
        />
      )}

      <div style={{ marginTop: 28 }}>
        <h2 style={{ fontSize: '1.05rem', marginBottom: 8 }}>Transfer rules (advanced)</h2>
        <p style={S.subtle}>Route specific peers or groups to another agent while keeping this identity.</p>
        <ChannelBindingTable agentId={agentId} />
      </div>
    </div>
  );
}

function PresenceDialog({
  agentId,
  types,
  initial,
  onClose,
  onSaved,
}: {
  agentId: string;
  types: ChannelTypeSpec[];
  initial: AgentPresence | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const isEdit = initial != null;
  const [platform, setPlatform] = useState(initial?.platform ?? types[0]?.type ?? '');
  const [channelId, setChannelId] = useState(initial?.channelId ?? '');
  const [isolation, setIsolation] = useState<'per_person' | 'shared'>(
    initial?.isolation ?? 'per_person',
  );
  const [enabled, setEnabled] = useState(initial?.enabled ?? true);
  const typeSpec = useMemo(() => types.find((t) => t.type === platform), [types, platform]);
  const [creds, setCreds] = useState<Record<string, string>>(() =>
    credentialsFromProperties(
      types.find((t) => t.type === (initial?.platform ?? types[0]?.type)),
      initial?.credentials,
    ),
  );
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  function onPlatformChange(next: string) {
    if (isEdit) return;
    setPlatform(next);
    setCreds(credentialsFromProperties(types.find((t) => t.type === next), undefined));
    if (!channelId || channelId.startsWith((platform || '') + '-')) {
      setChannelId(`${next}-${agentId}`);
    }
  }

  async function save() {
    setErr(null);
    const req: PresenceUpsertRequest = {
      channelId: channelId.trim() || undefined,
      platform,
      isolation,
      enabled,
      credentials: propertiesFromCredentials(typeSpec, creds, isEdit),
    };
    setBusy(true);
    try {
      if (isEdit && initial) {
        await updateAgentPresence(agentId, initial.channelId, req);
      } else {
        await createAgentPresence(agentId, req);
      }
      onSaved();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const scrim: React.CSSProperties = {
    position: 'fixed', inset: 0, background: 'rgba(15,23,42,0.45)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 50,
  };
  const modal: React.CSSProperties = {
    background: '#fff', borderRadius: 16, padding: 28, width: 680, maxWidth: '92vw',
    maxHeight: '90vh', overflow: 'auto',
  };

  return (
    <div style={scrim} onClick={onClose}>
      <div style={modal} onClick={(e) => e.stopPropagation()}>
        <h3 style={{ marginTop: 0 }}>{isEdit ? 'Edit IM identity' : 'Connect platform'}</h3>
        <div style={S.grid2}>
          <div>
            <label style={S.field}>Platform</label>
            <select
              style={S.input}
              value={platform}
              disabled={isEdit}
              onChange={(e) => onPlatformChange(e.target.value)}
            >
              {types.map((t) => (
                <option key={t.type} value={t.type}>{t.label}</option>
              ))}
            </select>
          </div>
          <div>
            <label style={S.field}>Channel id</label>
            <input
              style={S.input}
              value={channelId}
              disabled={isEdit}
              onChange={(e) => setChannelId(e.target.value)}
              placeholder={`${platform || 'dingtalk'}-${agentId}`}
            />
          </div>
          <div>
            <label style={S.field}>Conversation isolation</label>
            <select
              style={S.input}
              value={isolation}
              onChange={(e) => setIsolation(e.target.value as 'per_person' | 'shared')}
            >
              <option value="per_person">Per person — each chatter has their own memory</option>
              <option value="shared">Shared inbox — everyone shares one conversation</option>
            </select>
          </div>
          <div>
            <label style={S.field}>Enabled</label>
            <select
              style={S.input}
              value={enabled ? 'yes' : 'no'}
              onChange={(e) => setEnabled(e.target.value === 'yes')}
            >
              <option value="yes">Yes</option>
              <option value="no">No</option>
            </select>
          </div>
        </div>
        <div style={{ marginTop: 16 }}>
          <label style={S.field}>Credentials</label>
          <PlatformCredentialsForm
            spec={typeSpec}
            values={creds}
            onChange={setCreds}
            showAdvanced={showAdvanced}
            onToggleAdvanced={() => setShowAdvanced((v) => !v)}
          />
        </div>
        {err && <div style={S.err}>{err}</div>}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12, marginTop: 20 }}>
          <button style={S.btn} onClick={onClose} disabled={busy}>Cancel</button>
          <button style={{ ...S.btn, ...S.btnPrimary }} onClick={save} disabled={busy}>
            {busy ? 'Saving…' : isEdit ? 'Save' : 'Connect'}
          </button>
        </div>
      </div>
    </div>
  );
}
