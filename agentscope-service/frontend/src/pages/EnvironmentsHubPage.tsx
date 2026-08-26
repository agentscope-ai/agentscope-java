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
import {
  Environment,
  archiveEnvironment,
  createEnvironment,
  deleteEnvironment,
  listEnvironments,
  updateEnvironment,
} from '../api/environments';
import { HandsStatus, fetchHandsStatus } from '../api/hands';
import { type TranslationFunction, useI18n } from '@/i18n';

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
    background: '#ffffff', border: '1px solid #e2e8f0',
    borderRadius: 14, padding: '20px 22px',
    display: 'flex', flexDirection: 'column', gap: 10,
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
  badge: {
    padding: '3px 10px', borderRadius: 999, fontSize: '0.72rem', fontWeight: 600,
    background: '#f1f5f9', color: '#475569', border: '1px solid #e2e8f0',
  },
  archived: { background: '#fef3c7', color: '#92400e', border: '1px solid #fde68a' },
  rowBtn: {
    padding: '7px 14px', fontSize: '0.84rem', fontWeight: 500, borderRadius: 8, cursor: 'pointer',
    border: '1px solid #cbd5e1', background: '#ffffff', color: '#475569',
  },
  danger: { color: '#dc2626', borderColor: '#fca5a5' },
  formField: { display: 'block', fontSize: '0.85rem', color: '#475569', marginBottom: 6, fontWeight: 500 },
  input: {
    width: '100%', boxSizing: 'border-box', padding: '10px 12px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 8,
    color: '#0f172a', fontSize: '0.92rem',
  },
  err: { color: '#dc2626', fontSize: '0.95rem', marginBottom: 16 },
  modal: {
    position: 'fixed', inset: 0, background: 'rgba(15,23,42,0.45)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200,
  },
  modalBody: {
    background: '#ffffff', borderRadius: 14, padding: '28px 32px',
    width: '100%', maxWidth: 440, boxShadow: '0 20px 50px rgba(15,23,42,0.2)',
  },
};

function environmentTypeLabel(type: string, t: TranslationFunction): string {
  const labels: Record<string, Parameters<TranslationFunction>[0]> = {
    local: 'managed.environments.types.local',
    sandbox: 'managed.environments.types.sandbox',
    remote: 'managed.environments.types.remote',
    self_hosted: 'managed.environments.types.selfHosted',
  };
  return labels[type] ? t(labels[type]) : type;
}

export default function EnvironmentsHubPage() {
  const { locale, t } = useI18n();
  const [items, setItems] = useState<Environment[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<Environment | null>(null);
  const [name, setName] = useState('');
  const [type, setType] = useState('local');
  const [editName, setEditName] = useState('');
  const [editConfigText, setEditConfigText] = useState('{}');
  const [busyId, setBusyId] = useState<string | null>(null);
  const [hands, setHands] = useState<HandsStatus | null>(null);

  async function refresh() {
    setLoading(true);
    setErr(null);
    try {
      setItems(await listEnvironments());
      try {
        setHands(await fetchHandsStatus());
      } catch {
        setHands(null);
      }
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : t('managed.environments.loadFailed'));
    } finally {
      setLoading(false);
    }
  }

  // Initial data load must not repeat when the locale changes.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { void refresh(); }, []);

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    setBusyId('create');
    setErr(null);
    try {
      await createEnvironment({ name: name.trim(), type: type.trim() || 'local' });
      setCreating(false);
      setName('');
      setType('local');
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : t('managed.environments.createFailed'));
    } finally {
      setBusyId(null);
    }
  }

  function openEdit(env: Environment) {
    setEditing(env);
    setEditName(env.name);
    setEditConfigText(JSON.stringify(env.config ?? {}, null, 2));
    setErr(null);
  }

  async function handleUpdate(e: React.FormEvent) {
    e.preventDefault();
    if (!editing || !editName.trim()) return;
    let config: Record<string, unknown>;
    try {
      config = JSON.parse(editConfigText || '{}') as Record<string, unknown>;
    } catch {
      setErr(t('managed.environments.invalidJson'));
      return;
    }
    setBusyId(editing.id);
    setErr(null);
    try {
      await updateEnvironment(editing.id, { name: editName.trim(), config });
      setEditing(null);
      await refresh();
    } catch (ex: unknown) {
      setErr(ex instanceof Error ? ex.message : t('managed.environments.updateFailed'));
    } finally {
      setBusyId(null);
    }
  }

  async function handleArchive(id: string) {
    if (!confirm(t('managed.environments.confirmArchive'))) return;
    setBusyId(id);
    try {
      await archiveEnvironment(id);
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : t('managed.environments.archiveFailed'));
    } finally {
      setBusyId(null);
    }
  }

  async function handleDelete(id: string) {
    if (!confirm(t('managed.environments.confirmDelete'))) return;
    setBusyId(id);
    try {
      await deleteEnvironment(id);
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : t('managed.environments.deleteFailed'));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div style={S.root}>
      <div style={S.header}>
        <h1 style={S.title}>{t('navigation.managed.environments')}</h1>
        <button type="button" style={S.primaryBtn} onClick={() => setCreating(true)}>
          ＋ {t('managed.environments.new')}
        </button>
      </div>
      <p style={S.blurb}>
        {t('managed.environments.description')}
      </p>
      {hands && (
        <div style={{ ...S.card, marginBottom: 18 }}>
          <div style={{ fontWeight: 600 }}>{t('managed.environments.workerStatus')}</div>
          <div style={{ fontSize: '0.85rem', color: '#64748b' }}>
            {t('managed.environments.brain')} <code>{hands.brainInstanceId}</code>
            {' · '}{t('managed.environments.pendingWork', {
              count: new Intl.NumberFormat(locale).format(hands.pendingWorkItems),
            })}
            {' · '}{t('managed.environments.localSandboxes', {
              count: new Intl.NumberFormat(locale).format(hands.localSandboxRegistrySize),
            })}
          </div>
          <div style={{ fontSize: '0.82rem', color: '#94a3b8' }}>
            {t('managed.environments.workers')}{' '}
            {Object.keys(hands.workerHeartbeats).length === 0
              ? t('managed.common.none')
              : Object.entries(hands.workerHeartbeats)
                  .map(([id, ts]) => `${id}@${new Date(ts).toLocaleTimeString(locale)}`)
                  .join(', ')}
          </div>
        </div>
      )}
      {err && <div style={S.err}>{err}</div>}
      {loading && <div style={{ color: '#64748b' }}>{t('common.loading')}</div>}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(300px,1fr))', gap: 18 }}>
        {items.map(env => (
          <div key={env.id} style={S.card}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <span style={{ fontSize: '1.25rem' }}>🌐</span>
              <span style={{ fontWeight: 600, fontSize: '1.05rem', flex: 1 }}>{env.name}</span>
              <span style={{ ...S.badge, ...(env.archivedAt ? S.archived : {}) }}>
                {env.archivedAt
                  ? t('status.archived')
                  : environmentTypeLabel(env.type, t)}
              </span>
            </div>
            <div style={{ fontSize: '0.78rem', color: '#94a3b8', fontFamily: 'monospace' }}>{env.id}</div>
            <div style={{ display: 'flex', gap: 8, marginTop: 6, flexWrap: 'wrap' }}>
              {!env.archivedAt && (
                <button type="button" style={S.rowBtn} disabled={busyId === env.id} onClick={() => openEdit(env)}>
                  {t('common.actions.edit')}
                </button>
              )}
              {!env.archivedAt && (
                <button type="button" style={S.rowBtn} disabled={busyId === env.id} onClick={() => handleArchive(env.id)}>
                  {t('common.archive')}
                </button>
              )}
              <button type="button" style={{ ...S.rowBtn, ...S.danger }} disabled={busyId === env.id} onClick={() => handleDelete(env.id)}>
                {t('common.delete')}
              </button>
            </div>
          </div>
        ))}
        {!loading && items.length === 0 && (
          <div style={{ color: '#94a3b8', fontStyle: 'italic' }}>
            {t('managed.environments.empty')}
          </div>
        )}
      </div>

      {creating && (
        <div style={S.modal} onClick={() => setCreating(false)}>
          <div style={S.modalBody} onClick={e => e.stopPropagation()}>
            <h2 style={{ margin: '0 0 18px', fontSize: '1.2rem' }}>
              {t('managed.environments.new')}
            </h2>
            <form onSubmit={handleCreate}>
              <label style={S.formField}>{t('common.fields.name')}</label>
              <input
                style={{ ...S.input, marginBottom: 14 }}
                value={name}
                onChange={e => setName(e.target.value)}
                placeholder={t('managed.environments.namePlaceholder')}
                autoFocus
              />
              <label style={S.formField}>{t('managed.common.type')}</label>
              <select style={{ ...S.input, marginBottom: 20 }} value={type} onChange={e => setType(e.target.value)}>
                {['local', 'sandbox', 'remote', 'self_hosted'].map(value => (
                  <option key={value} value={value}>
                    {environmentTypeLabel(value, t)}
                  </option>
                ))}
              </select>
              {type === 'self_hosted' && (
                <p style={{ margin: '-14px 0 20px', color: '#64748b', fontSize: '0.82rem', lineHeight: 1.5 }}>
                  {t('managed.environments.selfHostedHint')}
                </p>
              )}
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
                <button type="button" style={S.rowBtn} onClick={() => setCreating(false)}>
                  {t('common.cancel')}
                </button>
                <button type="submit" style={S.primaryBtn} disabled={busyId === 'create'}>
                  {busyId === 'create'
                    ? t('managed.common.creating')
                    : t('managed.common.create')}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {editing && (
        <div style={S.modal} onClick={() => setEditing(null)}>
          <div style={S.modalBody} onClick={e => e.stopPropagation()}>
            <h2 style={{ margin: '0 0 18px', fontSize: '1.2rem' }}>
              {t('managed.environments.edit')}
            </h2>
            <form onSubmit={handleUpdate}>
              <label style={S.formField}>{t('common.fields.name')}</label>
              <input
                style={{ ...S.input, marginBottom: 14 }}
                value={editName}
                onChange={e => setEditName(e.target.value)}
                autoFocus
              />
              <label style={S.formField}>{t('managed.environments.typeReadOnly')}</label>
              <input style={{ ...S.input, marginBottom: 14, color: '#94a3b8' }} value={editing.type} disabled />
              <label style={S.formField}>{t('managed.environments.configJson')}</label>
              <textarea
                style={{ ...S.input, marginBottom: 20, minHeight: 140, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.82rem' }}
                value={editConfigText}
                onChange={e => setEditConfigText(e.target.value)}
              />
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
                <button type="button" style={S.rowBtn} onClick={() => setEditing(null)}>
                  {t('common.cancel')}
                </button>
                <button type="submit" style={S.primaryBtn} disabled={busyId === editing.id}>
                  {busyId === editing.id
                    ? t('common.saving')
                    : t('common.actions.save')}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
