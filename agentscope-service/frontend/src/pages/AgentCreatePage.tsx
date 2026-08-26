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
import { AgentCreateRequest, createAgent } from '../api/agents';
import { listEnvironments } from '../api/environments';
import { getWorkspace, listWorkspaces, WorkspaceSummary } from '../api/workspaces';
import { useT } from '@/i18n';

const S: Record<string, React.CSSProperties> = {
  page: { padding: '36px 40px', maxWidth: 880 },
  title: { margin: '0 0 24px', fontSize: '1.6rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.02em' },
  card: {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 14,
    padding: '28px 30px',
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
  fieldLabel: { display: 'block', fontSize: '0.88rem', color: '#475569', marginBottom: 8, fontWeight: 500 },
  input: {
    width: '100%', boxSizing: 'border-box', padding: '11px 14px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 9,
    color: '#0f172a', fontSize: '0.95rem',
  },
  textarea: {
    width: '100%', boxSizing: 'border-box', padding: '12px 14px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 9,
    color: '#0f172a', fontSize: '0.95rem',
    minHeight: 130, resize: 'vertical', lineHeight: 1.55,
  },
  row: { marginBottom: 20 },
  actions: { marginTop: 24, display: 'flex', gap: 12, alignItems: 'center' },
  btn: {
    padding: '11px 24px',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff',
    border: 'none', borderRadius: 9, cursor: 'pointer', fontSize: '0.95rem', fontWeight: 600,
    boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  btnDisabled: { background: '#e2e8f0', color: '#94a3b8', cursor: 'not-allowed', boxShadow: 'none' },
  cancel: {
    padding: '11px 20px', background: '#ffffff', color: '#475569',
    border: '1px solid #cbd5e1', borderRadius: 9, cursor: 'pointer', fontSize: '0.92rem', fontWeight: 500,
  },
  err: { color: '#dc2626', fontSize: '0.88rem' },
  hint: { fontSize: '0.8rem', color: '#94a3b8', marginTop: 6, lineHeight: 1.5 },
  tip: { fontSize: '0.88rem', color: '#64748b', marginBottom: 20, lineHeight: 1.55 },
};

export default function AgentCreatePage() {
  const t = useT();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [workspacePath, setWorkspacePath] = useState('');
  const [workspaceId, setWorkspaceId] = useState('');
  const [workspaces, setWorkspaces] = useState<WorkspaceSummary[]>([]);
  const [preview, setPreview] = useState<WorkspaceSummary | null>(null);
  const [defaultEnvironmentId, setDefaultEnvironmentId] = useState('');
  const [environments, setEnvironments] = useState<{ id: string; name: string; type: string }[]>([]);
  const [sysPrompt, setSysPrompt] = useState('');
  const [model, setModel] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    listWorkspaces().then(setWorkspaces).catch(() => undefined);
    listEnvironments().then(setEnvironments).catch(() => undefined);
  }, []);

  useEffect(() => {
    if (!workspaceId) {
      setPreview(null);
      return;
    }
    let cancelled = false;
    getWorkspace(workspaceId)
      .then(w => { if (!cancelled) setPreview(w); })
      .catch(() => { if (!cancelled) setPreview(null); });
    return () => { cancelled = true; };
  }, [workspaceId]);

  const canSubmit = !submitting && !!name.trim();

  async function handleSubmit() {
    setErr(null);
    setSubmitting(true);
    try {
      const req: AgentCreateRequest = {
        name: name.trim(),
        description: description.trim() || undefined,
        model: model.trim() || undefined,
        system: sysPrompt.trim() || undefined,
        workspacePath: workspacePath.trim() || undefined,
        workspaceId: workspaceId || undefined,
        defaultEnvironmentId: defaultEnvironmentId || undefined,
      };
      const created = await createAgent(req);
      navigate(`/agents/${encodeURIComponent(created.id)}/settings`, { replace: true });
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : t('managed.agentCreate.createFailed'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={S.page}>
      <h1 style={S.title}>{t('managed.agentCreate.title')}</h1>
      <div style={S.card}>
        <div style={S.tip}>
          {t('managed.agentCreate.description')}
        </div>

        <div style={S.row}>
          <label style={S.fieldLabel}>{t('managed.agentCreate.nameRequired')}</label>
          <input
            style={S.input}
            value={name}
            onChange={e => setName(e.target.value)}
            placeholder={t('managed.agentCreate.namePlaceholder')}
          />
        </div>

        <div style={S.row}>
          <label style={S.fieldLabel}>{t('managed.common.description')}</label>
          <input
            style={S.input}
            value={description}
            onChange={e => setDescription(e.target.value)}
            placeholder={t('managed.agentCreate.descriptionPlaceholder')}
          />
        </div>

        <div style={S.row}>
          <label style={S.fieldLabel}>{t('agent.workspace')}</label>
          <select
            style={S.input}
            value={workspaceId}
            onChange={e => setWorkspaceId(e.target.value)}
          >
            <option value="">{t('managed.agentCreate.privateWorkspace')}</option>
            {workspaces.map(w => (
              <option key={w.id} value={w.id}>{w.name}</option>
            ))}
          </select>
          <div style={S.hint}>
            {t('managed.agentCreate.workspaceHint')}
          </div>
          {preview && (
            <div style={{
              marginTop: 10, padding: '12px 14px', borderRadius: 10,
              background: '#f8fafc', border: '1px solid #e2e8f0',
              fontSize: '0.85rem', color: '#475569', lineHeight: 1.5,
            }}>
              {t('managed.agentCreate.inheritPrefix')}{' '}
              <strong>{preview.name}</strong> (v{preview.version}):{' '}
              {preview.agentsMdExists ? 'AGENTS.md · ' : ''}
              {t('managed.workspaces.skillCount', { count: preview.skillCount ?? 0 })}
              {' · '}
              {t('managed.workspaces.subagentCount', { count: preview.subagentCount ?? 0 })}
              {t('managed.common.period')}{' '}
              {t('managed.agentCreate.workspacePromptHint')}
            </div>
          )}
        </div>

        <div style={S.row}>
          <label style={S.fieldLabel}>
            {t('managed.agentCreate.defaultEnvironmentOptional')}
          </label>
          <select
            style={S.input}
            value={defaultEnvironmentId}
            onChange={e => setDefaultEnvironmentId(e.target.value)}
          >
            <option value="">{t('managed.agentCreate.defaultEnvironmentNone')}</option>
            {environments.map(env => (
              <option key={env.id} value={env.id}>{env.name} ({env.type})</option>
            ))}
          </select>
          <div style={S.hint}>
            {t('managed.agentCreate.defaultEnvironmentHint')}
          </div>
        </div>

        <div style={S.row}>
          <label style={S.fieldLabel}>
            {t('managed.agentCreate.workspacePathOptional')}
          </label>
          <input
            style={S.input}
            value={workspacePath}
            onChange={e => setWorkspacePath(e.target.value)}
            placeholder={t('managed.agentCreate.workspacePathPlaceholder')}
          />
          <div style={S.hint}>
            {t('managed.agentCreate.workspacePathHint')}
          </div>
        </div>

        <div style={S.row}>
          <label style={S.fieldLabel}>{t('common.fields.model')}</label>
          <input
            style={S.input}
            value={model}
            onChange={e => setModel(e.target.value)}
            placeholder={t('managed.agentCreate.modelPlaceholder')}
          />
          <div style={S.hint}>
            {t('managed.agentCreate.modelHint')}
          </div>
        </div>

        <div style={S.row}>
          <label style={S.fieldLabel}>{t('common.fields.systemPrompt')}</label>
          <textarea
            style={S.textarea}
            value={sysPrompt}
            onChange={e => setSysPrompt(e.target.value)}
            placeholder={t('managed.agentCreate.systemPromptPlaceholder')}
          />
        </div>

        <div style={S.actions}>
          <button
            style={{ ...S.btn, ...(canSubmit ? {} : S.btnDisabled) }}
            onClick={handleSubmit}
            disabled={!canSubmit}
          >
            {submitting
              ? t('managed.common.creating')
              : t('managed.agentCreate.create')}
          </button>
          <button style={S.cancel} onClick={() => navigate('/agents')}>
            {t('common.cancel')}
          </button>
          {err && <span style={S.err}>{err}</span>}
        </div>
      </div>
    </div>
  );
}
