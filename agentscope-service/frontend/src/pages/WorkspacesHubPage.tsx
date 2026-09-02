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
import { useNavigate } from 'react-router-dom';
import { resolveApiErrorMessage } from '@/api/errors';
import { createWorkspace, deleteWorkspace, listWorkspaces, WorkspaceSummary } from '../api/workspaces';
import { useT } from '@/i18n';

export default function WorkspacesHubPage() {
  const t = useT();
  const navigate = useNavigate();
  const [items, setItems] = useState<WorkspaceSummary[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [creating, setCreating] = useState(false);

  async function reload() {
    try {
      setItems(await listWorkspaces());
      setErr(null);
    } catch (e: unknown) {
      setErr(resolveApiErrorMessage(e, t('managed.workspaces.loadFailed')));
    }
  }

  // Initial data load must not repeat when the locale changes.
  useEffect(() => {
    reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function onCreate() {
    if (!name.trim()) return;
    setCreating(true);
    try {
      const ws = await createWorkspace({ name: name.trim() });
      setName('');
      navigate(`/workspaces/${encodeURIComponent(ws.id)}`);
    } catch (e: unknown) {
      setErr(resolveApiErrorMessage(e, t('managed.workspaces.createFailed')));
    } finally {
      setCreating(false);
    }
  }

  return (
    <div style={{ padding: '36px 40px', maxWidth: 960 }}>
      <h1 style={{ margin: '0 0 8px', fontSize: '1.6rem', fontWeight: 700, color: '#0f172a' }}>
        {t('navigation.managed.workspaces')}
      </h1>
      <p style={{ margin: '0 0 24px', color: '#64748b', fontSize: '0.95rem', lineHeight: 1.55 }}>
        {t('managed.workspaces.description')}
      </p>

      <div style={{
        display: 'flex', gap: 10, marginBottom: 24, padding: 16,
        background: '#fff', border: '1px solid #e2e8f0', borderRadius: 12,
      }}>
        <input
          value={name}
          onChange={e => setName(e.target.value)}
          placeholder={t('managed.workspaces.newNamePlaceholder')}
          style={{
            flex: 1, padding: '10px 12px', border: '1px solid #cbd5e1', borderRadius: 8,
            fontSize: '0.95rem',
          }}
        />
        <button
          onClick={onCreate}
          disabled={creating || !name.trim()}
          style={{
            padding: '10px 18px', border: 'none', borderRadius: 8, cursor: 'pointer',
            background: '#6366f1', color: '#fff', fontWeight: 600,
          }}
        >
          {creating ? t('managed.workspaces.creating') : t('managed.common.create')}
        </button>
      </div>

      {err && <div style={{ color: '#dc2626', marginBottom: 12 }}>{err}</div>}

      <div style={{ display: 'grid', gap: 12 }}>
        {items.map(ws => (
          <div
            key={ws.id}
            style={{
              background: '#fff', border: '1px solid #e2e8f0', borderRadius: 12,
              padding: '16px 18px', display: 'flex', alignItems: 'center', gap: 12,
            }}
          >
            <button
              onClick={() => navigate(`/workspaces/${encodeURIComponent(ws.id)}`)}
              style={{
                flex: 1, textAlign: 'left', background: 'transparent', border: 'none',
                cursor: 'pointer', padding: 0,
              }}
            >
              <div style={{ fontWeight: 650, color: '#0f172a', fontSize: '1.05rem' }}>{ws.name}</div>
              <div style={{ color: '#64748b', fontSize: '0.85rem', marginTop: 4 }}>
                {ws.description || ws.id} · v{ws.version}
                {ws.agentsMdExists ? ' · AGENTS.md' : ''}
                {' · '}{t('managed.workspaces.skillCount', { count: ws.skillCount ?? 0 })}
                {' · '}{t('managed.workspaces.subagentCount', { count: ws.subagentCount ?? 0 })}
              </div>
            </button>
            <button
              onClick={async () => {
                if (!confirm(t('managed.workspaces.confirmDelete', { name: ws.name }))) return;
                try {
                  await deleteWorkspace(ws.id);
                  await reload();
                } catch (e: unknown) {
                  setErr(resolveApiErrorMessage(e, t('managed.workspaces.deleteFailed')));
                }
              }}
              style={{
                padding: '8px 12px', background: '#fff', border: '1px solid #fecaca',
                color: '#b91c1c', borderRadius: 8, cursor: 'pointer', fontSize: '0.85rem',
              }}
            >
              {t('common.delete')}
            </button>
          </div>
        ))}
        {items.length === 0 && !err && (
          <div style={{ color: '#94a3b8', padding: 24 }}>
            {t('managed.workspaces.empty')}
          </div>
        )}
      </div>
    </div>
  );
}
