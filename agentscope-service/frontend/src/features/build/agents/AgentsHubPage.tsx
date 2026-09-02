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
import { AgentDefinition, listAgents } from '../../../api/agents';
import { cloneAgent } from '../../../api/clone';
import { getUserId } from '../../../api/auth';
import { resolveApiErrorMessage } from '@/api/errors';
import {
  type TranslationFunction,
  type TranslationKey,
  useI18n,
} from '@/i18n';

type Filter = 'all' | 'mine' | 'shared' | 'global' | 'clone';

const FILTERS: { key: Filter; labelKey: TranslationKey }[] = [
  { key: 'all', labelKey: 'managed.agents.filters.all' },
  { key: 'mine', labelKey: 'managed.agents.filters.mine' },
  { key: 'shared', labelKey: 'managed.agents.filters.shared' },
  { key: 'global', labelKey: 'managed.agents.filters.global' },
  { key: 'clone', labelKey: 'agent.access.cloneOnly' },
];

function badgeFor(a: AgentDefinition, me: string, t: TranslationFunction) {
  if (a.scope === 'global') {
    return { label: t('agent.scope.global'), bg: '#eef2ff', fg: '#4338ca', bd: '#c7d2fe' };
  }
  if (a.ownerId === me) {
    return { label: t('managed.agents.owner'), bg: '#ecfdf5', fg: '#047857', bd: '#a7f3d0' };
  }
  if (a.tierForCurrentUser === 'CLONE') {
    return { label: `🔁 ${t('agent.access.cloneOnly')}`, bg: '#fef3c7', fg: '#92400e', bd: '#fde68a' };
  }
  return {
    label: t('managed.agents.sharedBy', { owner: a.ownerId ?? '—' }),
    bg: '#e0e7ff',
    fg: '#3730a3',
    bd: '#c7d2fe',
  };
}
function bucket(a: AgentDefinition, me: string): Filter[] {
  const tags: Filter[] = ['all'];
  if (a.scope === 'global') tags.push('global');
  else if (a.ownerId === me) tags.push('mine');
  else if (a.tierForCurrentUser === 'CLONE') tags.push('clone');
  else tags.push('shared');
  return tags;
}

export default function AgentsHubPage() {
  const { locale, t } = useI18n();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<Filter>('all');
  const [busyId, setBusyId] = useState<string | null>(null);
  const me = getUserId();

  async function refresh() {
    setLoading(true);
    try {
      const list = await listAgents();
      setAgents(list);
    } catch (e: unknown) {
      setErr(resolveApiErrorMessage(e, t('managed.common.requestFailed')));
    } finally {
      setLoading(false);
    }
  }

  // Initial data load must not repeat when the locale changes.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { void refresh(); }, []);

  // ?clone=<id> deep-link from CLONE-only landing card
  const queuedClone = searchParams.get('clone');
  // Clone only when the deep-link state changes, not when translated labels change.
  useEffect(() => {
    if (!queuedClone || loading) return;
    const src = agents.find(a => a.id === queuedClone);
    if (!src) return;
    setSearchParams({}, { replace: true });
    void handleClone(src);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [queuedClone, loading, agents]);

  async function handleClone(src: AgentDefinition) {
    setBusyId(src.id);
    setErr(null);
    try {
      const clone = await cloneAgent(src.id, {});
      await refresh();
      navigate(`/agents/${encodeURIComponent(clone.id)}/chat`);
    } catch (e: unknown) {
      setErr(resolveApiErrorMessage(e, t('managed.agents.cloneFailed')));
    } finally {
      setBusyId(null);
    }
  }

  const visible = useMemo(() => {
    return agents.filter(a => bucket(a, me).includes(filter));
  }, [agents, filter, me]);

  return (
    <div style={{ padding: '40px 44px', maxWidth: 1200 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
        <h1 style={{ margin: 0, fontSize: '1.75rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.02em' }}>
          {t('navigation.managed.agents')}
        </h1>
        <button
          onClick={() => navigate('/agents/new')}
          style={{
            display: 'inline-flex', alignItems: 'center', gap: 8,
            background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
            color: '#ffffff', border: 'none',
            borderRadius: 10, padding: '11px 20px', fontSize: '0.95rem', fontWeight: 600,
            cursor: 'pointer',
            boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
          }}
        >＋ {t('managed.agentCreate.title')}</button>
      </div>

      <p style={{ margin: '0 0 24px', color: '#64748b', fontSize: '1rem', lineHeight: 1.6, maxWidth: 720 }}>
        {t('managed.agents.description', {
          owner: me || t('auth.guest'),
        })}
      </p>

      <div style={{ display: 'flex', gap: 8, marginBottom: 24, flexWrap: 'wrap' }}>
        {FILTERS.map(f => {
          const active = filter === f.key;
          const count = agents.filter(a => bucket(a, me).includes(f.key)).length;
          return (
            <button
              key={f.key}
              onClick={() => setFilter(f.key)}
              style={{
                padding: '7px 14px', borderRadius: 999, cursor: 'pointer',
                fontSize: '0.85rem', fontWeight: 600,
                background: active ? '#0f172a' : '#ffffff',
                color: active ? '#ffffff' : '#475569',
                border: `1px solid ${active ? '#0f172a' : '#e2e8f0'}`,
                display: 'inline-flex', alignItems: 'center', gap: 6,
              }}
            >
              {t(f.labelKey)}
              <span style={{
                fontSize: '0.72rem', fontWeight: 600,
                padding: '1px 7px', borderRadius: 999,
                background: active ? 'rgba(255,255,255,0.18)' : '#f1f5f9',
                color: active ? '#ffffff' : '#64748b',
              }}>{new Intl.NumberFormat(locale).format(count)}</span>
            </button>
          );
        })}
      </div>

      {loading && (
        <div style={{ color: '#64748b', fontSize: '0.95rem' }}>
          {t('common.loading')}
        </div>
      )}
      {err && <div style={{ color: '#dc2626', fontSize: '0.95rem', marginBottom: 16 }}>{err}</div>}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(300px,1fr))', gap: 18 }}>
        {visible.map(a => {
          const b = badgeFor(a, me, t);
          const tier = a.tierForCurrentUser;
          const canOpen = a.scope === 'global' || tier === 'RUN' || tier === 'EDIT';
          const canClone = tier === 'CLONE' || tier === 'RUN' || tier === 'EDIT';
          return (
            <div
              key={a.id}
              style={{
                position: 'relative',
                background: '#ffffff', border: '1px solid #e2e8f0',
                borderRadius: 14, padding: '20px 22px',
                display: 'flex', flexDirection: 'column', gap: 10,
                boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
                transition: 'transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease',
                cursor: canOpen ? 'pointer' : 'default',
              }}
              onClick={() => {
                if (canOpen) navigate(`/agents/${encodeURIComponent(a.id)}/chat`);
                else if (canClone) navigate(`/agents/${encodeURIComponent(a.id)}/chat`);
              }}
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
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <span style={{
                  display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                  width: 40, height: 40, borderRadius: 10,
                  background: 'linear-gradient(135deg,#eef2ff 0%,#e0e7ff 100%)',
                  border: '1px solid #c7d2fe', fontSize: '1.25rem', flexShrink: 0,
                }}>🤖</span>
                <span style={{ fontWeight: 600, fontSize: '1.05rem', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {a.name}
                </span>
                <span style={{
                  padding: '3px 10px', borderRadius: 999,
                  fontSize: '0.7rem', fontWeight: 600, letterSpacing: '0.02em',
                  background: b.bg, color: b.fg, border: `1px solid ${b.bd}`,
                  whiteSpace: 'nowrap',
                }}>{b.label}</span>
              </div>
              <div style={{ fontSize: '0.88rem', color: '#64748b', minHeight: 40, lineHeight: 1.5 }}>
                {a.description || (
                  <em style={{ color: '#94a3b8' }}>
                    {t('managed.common.noDescription')}
                  </em>
                )}
              </div>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 4 }}>
                <span style={{ fontSize: '0.76rem', color: '#94a3b8', fontFamily: 'monospace' }}>
                  {a.id}
                </span>
                {canClone && (
                  <button
                    onClick={e => { e.stopPropagation(); void handleClone(a); }}
                    disabled={busyId === a.id}
                    style={{
                      background: 'transparent', color: '#64748b',
                      border: '1px solid #e2e8f0', borderRadius: 8,
                      padding: '4px 10px', cursor: 'pointer',
                      fontSize: '0.78rem', fontWeight: 500,
                    }}
                    title={t('managed.agents.cloneTitle')}
                  >
                    {busyId === a.id ? '⏳' : `🔁 ${t('agent.clone')}`}
                  </button>
                )}
              </div>
            </div>
          );
        })}
        {!loading && visible.length === 0 && (
          <div style={{ color: '#94a3b8', fontSize: '0.92rem', fontStyle: 'italic' }}>
            {t('managed.agents.empty')}
          </div>
        )}
      </div>
    </div>
  );
}
