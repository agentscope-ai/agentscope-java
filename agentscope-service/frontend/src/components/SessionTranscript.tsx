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
  ManagedSession,
  archiveManagedSession,
  deleteManagedSession,
  getManagedSession,
  restoreManagedSession,
  updateManagedSession,
} from '../api/managedSessions';
import { Environment, listEnvironments } from '../api/environments';
import { MemoryStore, listMemoryStores } from '../api/memoryStores';
import { Vault, listVaults } from '../api/vaults';
import { Link, useNavigate } from 'react-router-dom';
import SessionEventTimeline from './SessionEventTimeline';
import { type TranslationFunction, useT } from '@/i18n';

const S: Record<string, React.CSSProperties> = {
  root: { padding: '28px 32px', minWidth: 0, maxWidth: 1100 },
  bar: { display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 },
  title: { fontSize: '1.25rem', fontWeight: 700, color: '#0f172a', margin: 0, letterSpacing: '-0.01em' },
  back: {
    background: '#ffffff', border: '1px solid #e2e8f0', color: '#475569',
    padding: '7px 14px', borderRadius: 8, cursor: 'pointer', fontSize: '0.85rem', fontWeight: 500,
    textDecoration: 'none', display: 'inline-flex',
  },
  btn: {
    padding: '7px 14px', borderRadius: 8, cursor: 'pointer',
    fontSize: '0.85rem', fontWeight: 500, border: '1px solid #cbd5e1',
    background: '#ffffff', color: '#475569',
    textDecoration: 'none', display: 'inline-flex',
  },
  danger: { color: '#dc2626', borderColor: '#fca5a5' },
  primary: {
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none',
    boxShadow: '0 2px 6px rgba(99,102,241,0.25), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  meta: { fontSize: '0.82rem', color: '#94a3b8', fontFamily: 'monospace', marginBottom: 22 },
  err: { color: '#dc2626', fontSize: '0.9rem' },
  panel: {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 12,
    padding: '16px 18px', marginBottom: 18,
  },
  panelTitle: { fontSize: '0.95rem', fontWeight: 600, color: '#0f172a', margin: '0 0 6px' },
  hint: { fontSize: '0.78rem', color: '#94a3b8', marginBottom: 12 },
  field: { display: 'block', fontSize: '0.8rem', color: '#64748b', marginBottom: 4, fontWeight: 500 },
  input: {
    width: '100%', boxSizing: 'border-box', padding: '8px 10px', marginBottom: 10,
    border: '1px solid #cbd5e1', borderRadius: 8, fontSize: '0.88rem',
  },
  textarea: {
    width: '100%', boxSizing: 'border-box', padding: '8px 10px', marginBottom: 10,
    border: '1px solid #cbd5e1', borderRadius: 8, fontSize: '0.88rem', minHeight: 90,
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  },
  checkGrid: { display: 'grid', gap: 8, marginBottom: 12 },
  checkRow: { display: 'flex', gap: 10, alignItems: 'center', fontSize: '0.88rem', color: '#334155' },
};

function parseOverrides(raw: string | null | undefined): Record<string, unknown> {
  if (!raw) return {};
  try {
    const v = JSON.parse(raw);
    return v && typeof v === 'object' ? v as Record<string, unknown> : {};
  } catch {
    return {};
  }
}

function sessionStatusLabel(status: string, t: TranslationFunction): string {
  switch (status.toLowerCase()) {
    case 'created':
      return t('status.created');
    case 'running':
      return t('status.running');
    case 'idle':
      return t('status.idle');
    case 'requires_action':
      return t('status.requiresAction');
    case 'terminated':
      return t('status.terminated');
    case 'rescheduled':
      return t('status.rescheduled');
    case 'archived':
      return t('status.archived');
    default:
      return status;
  }
}

/**
 * Managed session details: mounts, overrides, event timeline, archive/restore/delete.
 */
export default function SessionTranscript({
  agentId,
  sessionId,
  onDeleted,
  embedded = false,
}: {
  agentId: string;
  sessionId: string;
  onDeleted?: () => void;
  /** When true (Details tab), hide outer back/title chrome. */
  embedded?: boolean;
}) {
  const t = useT();
  const tRef = useRef(t);
  const [managedSession, setManagedSession] = useState<ManagedSession | null>(null);
  const [environments, setEnvironments] = useState<Environment[]>([]);
  const [vaults, setVaults] = useState<Vault[]>([]);
  const [memoryStores, setMemoryStores] = useState<MemoryStore[]>([]);
  const [environmentId, setEnvironmentId] = useState('');
  const [vaultIds, setVaultIds] = useState<string[]>([]);
  const [memoryStoreIds, setMemoryStoreIds] = useState<string[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [system, setSystem] = useState('');
  const [model, setModel] = useState('');
  const [maxIters, setMaxIters] = useState('');
  const [savingOverrides, setSavingOverrides] = useState(false);
  const [savingMounts, setSavingMounts] = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    tRef.current = t;
  }, [t]);

  const archived = !!managedSession?.archivedAt;

  async function reload() {
    setErr(null);
    try {
      const [sess, envs, vs, ms] = await Promise.all([
        getManagedSession(sessionId),
        listEnvironments().catch(() => [] as Environment[]),
        listVaults().catch(() => [] as Vault[]),
        listMemoryStores().catch(() => [] as MemoryStore[]),
      ]);
      setManagedSession(sess);
      setEnvironments(envs.filter(e => !e.archivedAt));
      setVaults(vs);
      setMemoryStores(ms);
      setEnvironmentId(sess.environmentId || '');
      setVaultIds(sess.vaultIds ?? []);
      setMemoryStoreIds(sess.memoryStoreIds ?? []);
      const ov = parseOverrides(sess.agentOverridesJson);
      setSystem(typeof ov.system === 'string' ? ov.system : '');
      setModel(typeof ov.model === 'string' ? ov.model : '');
      setMaxIters(ov.maxIters != null ? String(ov.maxIters) : '');
    } catch (e: unknown) {
      setErr(
        e instanceof Error
          ? e.message
          : tRef.current('common.requestFailed'),
      );
    }
  }

  useEffect(() => {
    void reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId, sessionId]);

  async function handleArchiveManaged() {
    if (!confirm(t('session.details.confirmArchive'))) return;
    try {
      await archiveManagedSession(sessionId);
      await reload();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : t('common.requestFailed'));
    }
  }

  async function handleRestoreManaged() {
    try {
      await restoreManagedSession(sessionId);
      await reload();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : t('common.requestFailed'));
    }
  }

  async function handleDelete() {
    if (!confirm(t('session.details.confirmDelete'))) return;
    try {
      await deleteManagedSession(sessionId);
      if (onDeleted) onDeleted();
      else navigate(`/sessions?agentId=${encodeURIComponent(agentId)}`, { replace: true });
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : t('common.requestFailed'));
    }
  }

  async function handleSaveOverrides(e: React.FormEvent) {
    e.preventDefault();
    if (archived) return;
    setSavingOverrides(true);
    setErr(null);
    try {
      const agentOverrides: Record<string, unknown> = {
        system: system.trim() ? system : null,
        model: model.trim() ? model : null,
        maxIters: maxIters.trim() ? Number(maxIters) : null,
      };
      if (maxIters.trim() && Number.isNaN(Number(maxIters))) {
        throw new Error(t('session.validation.maxItersNumber'));
      }
      const updated = await updateManagedSession(sessionId, { agentOverrides });
      setManagedSession(updated);
    } catch (ex: unknown) {
      setErr(
        ex instanceof Error
          ? ex.message
          : t('session.details.saveOverridesFailed'),
      );
    } finally {
      setSavingOverrides(false);
    }
  }

  async function handleSaveMounts(e: React.FormEvent) {
    e.preventDefault();
    if (archived) return;
    setSavingMounts(true);
    setErr(null);
    try {
      if (!environmentId.trim()) {
        throw new Error(t('session.validation.environmentRequired'));
      }
      const updated = await updateManagedSession(sessionId, {
        environmentId: environmentId.trim(),
        vaultIds,
        memoryStoreIds,
      });
      setManagedSession(updated);
    } catch (ex: unknown) {
      setErr(
        ex instanceof Error ? ex.message : t('session.details.saveMountsFailed'),
      );
    } finally {
      setSavingMounts(false);
    }
  }

  return (
    <div style={S.root}>
      {!embedded && (
        <div style={S.bar}>
          <Link to={`/sessions?agentId=${encodeURIComponent(agentId)}`} style={S.back}>
            ← {t('common.back')}
          </Link>
          <h2 style={S.title}>{t('session.details.title')}</h2>
          <span style={{ flex: 1 }} />
          {!archived && (
            <Link
              to={`/sessions/${encodeURIComponent(sessionId)}`}
              style={{ ...S.btn, ...S.primary }}
              title={t('session.details.openChatTitle')}
            >
              ▶ {t('session.details.openChat')}
            </Link>
          )}
          {archived ? (
            <button type="button" style={S.btn} onClick={handleRestoreManaged}>
              {t('common.restore')}
            </button>
          ) : (
            <button type="button" style={S.btn} onClick={handleArchiveManaged}>
              {t('common.archive')}
            </button>
          )}
          <button type="button" style={{ ...S.btn, ...S.danger }} onClick={handleDelete}>
            {t('common.delete')}
          </button>
        </div>
      )}
      {embedded && (
        <div style={{ ...S.bar, marginBottom: 12 }}>
          <span style={{ flex: 1 }} />
          {archived ? (
            <button type="button" style={S.btn} onClick={handleRestoreManaged}>
              {t('common.restore')}
            </button>
          ) : (
            <button type="button" style={S.btn} onClick={handleArchiveManaged}>
              {t('common.archive')}
            </button>
          )}
          <button type="button" style={{ ...S.btn, ...S.danger }} onClick={handleDelete}>
            {t('common.delete')}
          </button>
        </div>
      )}
      <div style={S.meta}>
        {sessionId}
        {managedSession && ` · ${sessionStatusLabel(managedSession.status, t)}`}
        {archived && ` · ${t('status.archived')}`}
      </div>
      {err && <div style={S.err}>{err}</div>}

      <div style={S.panel}>
        <div style={S.panelTitle}>{t('session.details.mounts')}</div>
        <div style={S.hint}>
          {archived
            ? t('session.details.archivedMountsHint')
            : t('session.details.mountsHint')}
        </div>
        <form onSubmit={handleSaveMounts}>
          <label style={S.field}>{t('session.fields.environment')}</label>
          <select
            style={S.input}
            value={environmentId}
            onChange={e => setEnvironmentId(e.target.value)}
            disabled={archived}
          >
            <option value="">{t('session.fields.selectEnvironment')}</option>
            {environments.map(env => (
              <option key={env.id} value={env.id}>{env.name} ({env.type})</option>
            ))}
          </select>
          <label style={S.field}>{t('session.fields.vaults')}</label>
          <div style={S.checkGrid}>
            {vaults.length === 0 && (
              <div style={S.hint}>{t('session.details.noVaults')}</div>
            )}
            {vaults.map(v => {
              const on = vaultIds.includes(v.id);
              return (
                <label key={v.id} style={S.checkRow}>
                  <input
                    type="checkbox"
                    checked={on}
                    disabled={archived}
                    onChange={() => {
                      setVaultIds(prev => on ? prev.filter(id => id !== v.id) : [...prev, v.id]);
                    }}
                  />
                  <span>{v.displayName}</span>
                </label>
              );
            })}
          </div>
          <label style={S.field}>{t('session.fields.memoryStores')}</label>
          <div style={S.checkGrid}>
            {memoryStores.length === 0 && (
              <div style={S.hint}>{t('session.details.noMemoryStores')}</div>
            )}
            {memoryStores.map(m => {
              const on = memoryStoreIds.includes(m.id);
              return (
                <label key={m.id} style={S.checkRow}>
                  <input
                    type="checkbox"
                    checked={on}
                    disabled={archived}
                    onChange={() => {
                      setMemoryStoreIds(prev => on ? prev.filter(id => id !== m.id) : [...prev, m.id]);
                    }}
                  />
                  <span>{m.name}</span>
                </label>
              );
            })}
          </div>
          {!archived && (
            <button type="submit" style={{ ...S.btn, ...S.primary }} disabled={savingMounts}>
              {savingMounts ? t('common.saving') : t('session.details.saveMounts')}
            </button>
          )}
        </form>
      </div>

      <div style={S.panel}>
        <div style={S.panelTitle}>{t('session.details.overrides')}</div>
        <div style={S.hint}>{t('session.details.overridesHint')}</div>
        <form onSubmit={handleSaveOverrides}>
          <label style={S.field}>{t('session.fields.systemPrompt')}</label>
          <textarea
            style={S.textarea}
            value={system}
            onChange={e => setSystem(e.target.value)}
            placeholder={t('session.overrides.clearPlaceholder')}
            disabled={archived}
          />
          <label style={S.field}>{t('session.fields.model')}</label>
          <input
            style={S.input}
            value={model}
            onChange={e => setModel(e.target.value)}
            placeholder={t('session.placeholders.modelExample')}
            disabled={archived}
          />
          <label style={S.field}>{t('session.fields.maxIters')}</label>
          <input
            style={S.input}
            value={maxIters}
            onChange={e => setMaxIters(e.target.value)}
            placeholder={t('session.placeholders.maxItersExample')}
            disabled={archived}
          />
          {!archived && (
            <button type="submit" style={{ ...S.btn, ...S.primary }} disabled={savingOverrides}>
              {savingOverrides
                ? t('common.saving')
                : t('session.details.saveOverrides')}
            </button>
          )}
        </form>
      </div>

      <SessionEventTimeline managedSessionId={sessionId} />
    </div>
  );
}
