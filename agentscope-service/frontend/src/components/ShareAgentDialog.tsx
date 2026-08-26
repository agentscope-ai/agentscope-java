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

import React, { useEffect, useRef, useState } from 'react';
import {
  AgentDefinition,
  AgentShareGrant,
  GranteeType,
  ShareTier,
} from '../api/agents';
import { addShare, listShares, revokeShare } from '../api/shares';
import { AdminUserView, listUsers } from '../api/admin';
import { isAdmin } from '../api/auth';
import { type TranslationFunction, type TranslationKey, useT } from '../i18n';

const S: Record<string, React.CSSProperties> = {
  scrim: {
    position: 'fixed', inset: 0, background: 'rgba(15,23,42,0.45)',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    zIndex: 100,
  },
  modal: {
    width: 720, maxWidth: '94vw', maxHeight: '88vh', overflow: 'auto',
    background: '#ffffff', borderRadius: 16,
    boxShadow: '0 24px 60px rgba(15,23,42,0.25)',
    display: 'flex', flexDirection: 'column',
  },
  header: {
    padding: '20px 28px', borderBottom: '1px solid #e2e8f0',
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
  },
  title: { margin: 0, fontSize: '1.15rem', fontWeight: 700, color: '#0f172a' },
  body: { padding: '20px 28px', display: 'flex', flexDirection: 'column', gap: 22 },
  sectionLabel: {
    fontSize: '0.78rem', color: '#94a3b8', fontWeight: 700,
    textTransform: 'uppercase', letterSpacing: '0.1em', marginBottom: 12,
  },
  grantRow: {
    display: 'flex', alignItems: 'center', gap: 12,
    padding: '10px 14px', border: '1px solid #e2e8f0', borderRadius: 10,
    background: '#f8fafc',
  },
  granteeChip: {
    padding: '3px 9px', borderRadius: 999, fontSize: '0.72rem', fontWeight: 600,
    background: '#eef2ff', color: '#4338ca', border: '1px solid #c7d2fe',
  },
  granteeText: { fontWeight: 600, color: '#0f172a', fontFamily: 'monospace', fontSize: '0.92rem' },
  iconBtn: {
    background: 'transparent', border: '1px solid #e2e8f0', borderRadius: 8,
    padding: '4px 10px', cursor: 'pointer', color: '#64748b', fontSize: '0.85rem',
  },
  workspaceRow: {
    display: 'flex', alignItems: 'center', gap: 12,
    padding: '12px 14px', border: '1px solid #ede9fe', borderRadius: 10,
    background: 'linear-gradient(135deg,#faf5ff 0%,#eef2ff 100%)',
  },
  input: {
    flex: 1, padding: '10px 12px', boxSizing: 'border-box',
    border: '1px solid #cbd5e1', borderRadius: 9, fontSize: '0.92rem', color: '#0f172a',
  },
  primaryBtn: {
    padding: '10px 18px',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none', borderRadius: 9, cursor: 'pointer',
    fontSize: '0.92rem', fontWeight: 600,
    boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  ghostBtn: {
    padding: '10px 16px', background: '#ffffff', color: '#475569',
    border: '1px solid #cbd5e1', borderRadius: 9, cursor: 'pointer',
    fontSize: '0.92rem', fontWeight: 500,
  },
  tierChips: { display: 'flex', gap: 6 },
  error: { color: '#dc2626', fontSize: '0.88rem', marginTop: 8 },
  empty: { color: '#94a3b8', fontSize: '0.9rem', fontStyle: 'italic' },
};

function tierBadgeStyle(tier: string): React.CSSProperties {
  const map: Record<ShareTier, { bg: string; fg: string; bd: string }> = {
    CLONE: { bg: '#fef3c7', fg: '#92400e', bd: '#fde68a' },
    RUN:   { bg: '#dcfce7', fg: '#15803d', bd: '#bbf7d0' },
    EDIT:  { bg: '#ede9fe', fg: '#6d28d9', bd: '#ddd6fe' },
  };
  const colors = map[tier as ShareTier] ?? { bg: '#f1f5f9', fg: '#64748b', bd: '#e2e8f0' };
  return {
    padding: '3px 10px', borderRadius: 999, fontSize: '0.72rem', fontWeight: 600,
    letterSpacing: '0.02em',
    background: colors.bg, color: colors.fg, border: `1px solid ${colors.bd}`,
  };
}

function tierChipStyle(active: boolean): React.CSSProperties {
  return {
    padding: '5px 12px', borderRadius: 999, fontSize: '0.78rem', fontWeight: 600,
    cursor: 'pointer', userSelect: 'none',
    background: active ? '#0f172a' : '#ffffff',
    color: active ? '#ffffff' : '#475569',
    border: `1px solid ${active ? '#0f172a' : '#cbd5e1'}`,
  };
}

const WORKSPACE_ID = '*';
const TIER_LABELS: Record<ShareTier, TranslationKey> = {
  CLONE: 'agent.shareDialog.tier.clone',
  RUN: 'agent.shareDialog.tier.run',
  EDIT: 'agent.shareDialog.tier.edit',
};

function tierLabel(t: TranslationFunction, tier: string): string {
  const key = TIER_LABELS[tier as ShareTier];
  return key ? t(key) : tier;
}

interface Props {
  agent: AgentDefinition;
  onClose: () => void;
}

export default function ShareAgentDialog({ agent, onClose }: Props) {
  const t = useT();
  const tRef = useRef(t);
  const [grants, setGrants] = useState<AgentShareGrant[]>([]);
  const [users, setUsers] = useState<AdminUserView[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const [newGrantee, setNewGrantee] = useState('');
  const [newTier, setNewTier] = useState<ShareTier>('RUN');
  const [busy, setBusy] = useState(false);

  const admin = isAdmin();

  useEffect(() => {
    tRef.current = t;
  }, [t]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    Promise.all([
      listShares(agent.id),
      admin ? listUsers().catch(() => [] as AdminUserView[]) : Promise.resolve([] as AdminUserView[]),
    ])
      .then(([gs, us]) => {
        if (cancelled) return;
        setGrants(gs);
        setUsers(us);
      })
      .catch(e => {
        if (!cancelled) {
          setErr(e instanceof Error ? e.message : tRef.current('agent.shareDialog.error.load'));
        }
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [agent.id, admin]);

  const workspaceGrant = grants.find(g => g.granteeType === 'WORKSPACE');
  const userGrants = grants.filter(g => g.granteeType === 'USER');

  async function setWorkspaceTier(tier: ShareTier | null) {
    setBusy(true);
    setErr(null);
    try {
      if (tier === null) {
        if (workspaceGrant) {
          await revokeShare(agent.id, 'WORKSPACE', WORKSPACE_ID);
        }
      } else {
        await addShare(agent.id, { granteeType: 'WORKSPACE', granteeId: WORKSPACE_ID, tier });
      }
      setGrants(await listShares(agent.id));
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : t('agent.shareDialog.error.updateWorkspace'));
    } finally {
      setBusy(false);
    }
  }

  async function addUserGrant() {
    const granteeId = newGrantee.trim();
    if (!granteeId) {
      setErr(t('agent.shareDialog.error.pickUser'));
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      await addShare(agent.id, { granteeType: 'USER', granteeId, tier: newTier });
      setGrants(await listShares(agent.id));
      setNewGrantee('');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : t('agent.shareDialog.error.addGrant'));
    } finally {
      setBusy(false);
    }
  }

  async function revoke(granteeType: GranteeType, granteeId: string) {
    setBusy(true);
    setErr(null);
    try {
      await revokeShare(agent.id, granteeType, granteeId);
      setGrants(await listShares(agent.id));
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : t('agent.shareDialog.error.revoke'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={S.scrim} onClick={onClose}>
      <div style={S.modal} onClick={e => e.stopPropagation()}>
        <div style={S.header}>
          <h2 style={S.title}>↗ {t('agent.shareDialog.title', { name: agent.name })}</h2>
          <button style={S.iconBtn} onClick={onClose}>{t('agent.shareDialog.close')}</button>
        </div>

        <div style={S.body}>
          {loading ? (
            <div style={S.empty}>{t('agent.shareDialog.loading')}</div>
          ) : (
            <>
              <section>
                <div style={S.sectionLabel}>{t('agent.shareDialog.entireWorkspace')}</div>
                <div style={S.workspaceRow}>
                  <span style={{ flex: 1, fontSize: '0.92rem', color: '#475569' }}>
                    {t('agent.shareDialog.everyUser')}
                  </span>
                  <div style={S.tierChips}>
                    {(['none', 'CLONE', 'RUN', 'EDIT'] as const).map(opt => {
                      const active =
                        (opt === 'none' && !workspaceGrant) ||
                        (workspaceGrant?.tier === opt);
                      return (
                        <span
                          key={opt}
                          style={tierChipStyle(active)}
                          onClick={() => {
                            if (busy) return;
                            void setWorkspaceTier(opt === 'none' ? null : opt);
                          }}
                        >
                          {opt === 'none'
                            ? t('agent.shareDialog.tier.none')
                            : tierLabel(t, opt)}
                        </span>
                      );
                    })}
                  </div>
                </div>
              </section>

              <section>
                <div style={S.sectionLabel}>{t('agent.shareDialog.addUser')}</div>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                  <input
                    style={S.input}
                    list={admin ? 'share-user-list' : undefined}
                    placeholder={admin
                      ? t('agent.shareDialog.pickOrTypeUserId')
                      : t('agent.shareDialog.enterUserId')}
                    value={newGrantee}
                    onChange={e => setNewGrantee(e.target.value)}
                  />
                  {admin && (
                    <datalist id="share-user-list">
                      {users
                        .filter(u => u.userId !== agent.ownerId)
                        .map(u => (
                          <option key={u.userId} value={u.userId}>
                            {u.username}
                          </option>
                        ))}
                    </datalist>
                  )}
                  <div style={S.tierChips}>
                    {(['CLONE', 'RUN', 'EDIT'] as ShareTier[]).map(tier => (
                      <span
                        key={tier}
                        style={tierChipStyle(newTier === tier)}
                        onClick={() => setNewTier(tier)}
                      >
                        {tierLabel(t, tier)}
                      </span>
                    ))}
                  </div>
                  <button
                    style={busy ? { ...S.primaryBtn, opacity: 0.6 } : S.primaryBtn}
                    onClick={addUserGrant}
                    disabled={busy}
                  >
                    {t('agent.shareDialog.add')}
                  </button>
                </div>
              </section>

              <section>
                <div style={S.sectionLabel}>{t('agent.shareDialog.grantedUsers')}</div>
                {userGrants.length === 0 ? (
                  <div style={S.empty}>{t('agent.shareDialog.noUserGrants')}</div>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                    {userGrants.map(g => (
                      <div key={`${g.granteeType}:${g.granteeId}`} style={S.grantRow}>
                        <span style={S.granteeChip}>{t('agent.shareDialog.user')}</span>
                        <span style={S.granteeText}>{g.granteeId}</span>
                        <span style={{ flex: 1 }} />
                        <span style={tierBadgeStyle(g.tier)}>
                          {tierLabel(t, g.tier)}
                        </span>
                        <button
                          style={S.iconBtn}
                          onClick={() => revoke('USER', g.granteeId)}
                          disabled={busy}
                          title={t('agent.shareDialog.revoke')}
                        >
                          ✕
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </section>

              {err && <div style={S.error}>{err}</div>}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
