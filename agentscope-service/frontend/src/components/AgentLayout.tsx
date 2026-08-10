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

import React, { useEffect, useState } from 'react';
import { Link, Outlet, useLocation, useNavigate, useParams } from 'react-router-dom';
import { AgentDefinition, getAgent, ShareTier } from '../api/agents';
import ShareAgentDialog from './ShareAgentDialog';

type TierMin = ShareTier;

const TABS: { key: string; label: string; icon: string; minTier: TierMin }[] = [
  { key: 'workspace', label: 'Workspace', icon: '📁', minTier: 'RUN'  },
  { key: 'skills',    label: 'Skills',    icon: '🧩', minTier: 'RUN'  },
  { key: 'tools',     label: 'Tools',     icon: '🛠️', minTier: 'RUN'  },
  { key: 'subagents', label: 'Subagents', icon: '🤝', minTier: 'RUN'  },
  { key: 'channels',  label: 'Channels',  icon: '📡', minTier: 'RUN'  },
  { key: 'settings',  label: 'Settings',  icon: '⚙️', minTier: 'RUN'  },
];

const TIER_RANK: Record<ShareTier, number> = { CLONE: 1, RUN: 2, EDIT: 3 };

function tierImplies(have: ShareTier | undefined | null, need: TierMin): boolean {
  if (!have) return false;
  return TIER_RANK[have] >= TIER_RANK[need];
}

export default function AgentLayout() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const [agent, setAgent] = useState<AgentDefinition | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [shareOpen, setShareOpen] = useState(false);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    setErr(null);
    getAgent(id)
      .then(a => { if (!cancelled) setAgent(a); })
      .catch(e => { if (!cancelled) setErr(e.message); });
    return () => { cancelled = true; };
  }, [id]);

  if (!id) return <div style={{ padding: 32 }}>Missing agent id.</div>;

  const tier = agent?.tierForCurrentUser ?? null;
  const isClonyOnly = tier === 'CLONE';
  const isGlobal = agent?.scope === 'global';
  const canEdit = !isGlobal && tier === 'EDIT';

  const activeTab =
    TABS.find(t => location.pathname.startsWith(`/agents/${id}/${t.key}`))?.key ?? 'settings';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={{
        padding: '22px 32px 0', borderBottom: '1px solid #e2e8f0',
        background: '#ffffff', flexShrink: 0,
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 16 }}>
          <span style={{
            display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
            width: 44, height: 44, borderRadius: 12,
            background: 'linear-gradient(135deg,#eef2ff 0%,#e0e7ff 100%)',
            border: '1px solid #c7d2fe',
            fontSize: '1.5rem',
          }}>🤖</span>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 3, minWidth: 0 }}>
            <span style={{ fontSize: '1.2rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.01em' }}>
              {agent?.name ?? id}
            </span>
            {agent?.description && (
              <span style={{ fontSize: '0.88rem', color: '#64748b' }}>{agent.description}</span>
            )}
          </div>
          {agent && (
            <span style={{
              marginLeft: 6, padding: '4px 12px', borderRadius: 999, fontSize: '0.74rem',
              fontWeight: 600, letterSpacing: '0.02em',
              background: agent.scope === 'global' ? '#eef2ff' : '#f1f5f9',
              color: agent.scope === 'global' ? '#4338ca' : '#475569',
              border: agent.scope === 'global' ? '1px solid #c7d2fe' : '1px solid #e2e8f0',
            }}>
              {agent.scope}
            </span>
          )}
          {agent?.workspaceId && (
            <Link
              to={`/workspaces/${encodeURIComponent(agent.workspaceId)}`}
              style={{
                marginLeft: 4, padding: '4px 12px', borderRadius: 999, fontSize: '0.74rem',
                fontWeight: 600, letterSpacing: '0.02em', textDecoration: 'none',
                background: '#ecfdf5', color: '#047857', border: '1px solid #a7f3d0',
              }}
              title={agent.workspaceId}
            >
              📁 Workspace
            </Link>
          )}
          {tier && tier !== 'EDIT' && agent && !isGlobal && (
            <span style={{
              padding: '4px 10px', borderRadius: 999, fontSize: '0.72rem', fontWeight: 600,
              background: '#dcfce7', color: '#15803d', border: '1px solid #bbf7d0',
            }}>
              {tier === 'RUN' ? 'shared · can run' : '🔁 clone-only'}
            </span>
          )}
          <span style={{ flex: 1 }} />
          {canEdit && (
            <button
              onClick={() => setShareOpen(true)}
              style={{
                padding: '8px 14px', background: '#ffffff', color: '#4338ca',
                border: '1px solid #c7d2fe', borderRadius: 8, cursor: 'pointer',
                fontSize: '0.88rem', fontWeight: 600,
              }}
            >
              ↗ Share
            </button>
          )}
          {err && <span style={{ marginLeft: 12, color: '#dc2626', fontSize: '0.85rem' }}>{err}</span>}
        </div>

        <div
          style={{
            marginBottom: 14,
            padding: '10px 14px',
            borderRadius: 10,
            border: '1px solid #e2e8f0',
            background: '#f8fafc',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 12,
          }}
        >
          <div>
            <div style={{ fontSize: '0.82rem', fontWeight: 600, color: '#0f172a' }}>Runtime</div>
            <div style={{ fontSize: '0.78rem', color: '#64748b' }}>
              Open this agent in Operate if a data plane has registered under the same name.
            </div>
          </div>
          <button
            onClick={() => navigate(`/operate/agents/${encodeURIComponent(agent?.name || id)}?namespace=default`)}
            style={{
              padding: '7px 12px',
              background: '#ffffff',
              color: '#4338ca',
              border: '1px solid #c7d2fe',
              borderRadius: 8,
              cursor: 'pointer',
              fontSize: '0.82rem',
              fontWeight: 600,
              whiteSpace: 'nowrap',
            }}
          >
            View in Operate →
          </button>
        </div>

        {isClonyOnly ? (
          <div style={{ paddingBottom: 12, color: '#64748b', fontSize: '0.9rem' }}>
            You have <strong>CLONE-only</strong> access. Use the Clone button on the agents hub to copy this agent into your namespace.
          </div>
        ) : (
          <div style={{ display: 'flex', gap: 4 }}>
            {TABS.map(t => {
              const allowed =
                isGlobal ? (t.minTier !== 'EDIT')
                : tierImplies(tier, t.minTier);
              if (!allowed) return null;
              const active = activeTab === t.key;
              return (
                <button
                  key={t.key}
                  onClick={() => navigate(`/agents/${encodeURIComponent(id)}/${t.key}`)}
                  style={{
                    display: 'flex', alignItems: 'center', gap: 8,
                    background: 'transparent', border: 'none',
                    borderBottom: `2px solid ${active ? '#6366f1' : 'transparent'}`,
                    padding: '12px 18px', cursor: 'pointer',
                    fontSize: '0.92rem',
                    color: active ? '#0f172a' : '#64748b',
                    fontWeight: active ? 600 : 500,
                    marginBottom: -1,
                  }}
                  onMouseEnter={e => { if (!active) e.currentTarget.style.color = '#0f172a'; }}
                  onMouseLeave={e => { if (!active) e.currentTarget.style.color = '#64748b'; }}
                >
                  <span>{t.icon}</span> {t.label}
                </button>
              );
            })}
          </div>
        )}
      </div>

      <div style={{ flex: 1, overflow: 'auto', background: '#f8fafc' }}>
        {isClonyOnly ? (
          <div style={{ padding: '60px 44px', maxWidth: 720 }}>
            <div style={{
              background: '#ffffff', border: '1px solid #fde68a', borderRadius: 14,
              padding: '28px 32px', boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
            }}>
              <div style={{
                display: 'inline-flex', alignItems: 'center', gap: 8,
                padding: '4px 10px', borderRadius: 999,
                background: '#fef3c7', color: '#92400e', border: '1px solid #fde68a',
                fontSize: '0.78rem', fontWeight: 600, marginBottom: 14,
              }}>🔁 Clone-only</div>
              <h2 style={{ margin: '0 0 8px', fontSize: '1.4rem', color: '#0f172a' }}>{agent?.name}</h2>
              <p style={{ margin: '0 0 18px', color: '#64748b', fontSize: '0.95rem', lineHeight: 1.6 }}>
                {agent?.description || 'The owner has granted you Clone access. Copy this agent into your own namespace to run or edit it.'}
              </p>
              <button
                onClick={() => navigate(`/agents?clone=${encodeURIComponent(id)}`)}
                style={{
                  padding: '11px 22px',
                  background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
                  color: '#ffffff', border: 'none', borderRadius: 9, cursor: 'pointer',
                  fontSize: '0.95rem', fontWeight: 600,
                  boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
                }}
              >
                🔁 Clone agent
              </button>
            </div>
          </div>
        ) : (
          <Outlet context={{
            agentId: id,
            agent,
            refreshAgent: () => getAgent(id).then(setAgent).catch(() => undefined),
          }} />
        )}
      </div>

      {shareOpen && agent && (
        <ShareAgentDialog agent={agent} onClose={() => setShareOpen(false)} />
      )}
    </div>
  );
}
