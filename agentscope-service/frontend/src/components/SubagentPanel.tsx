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

import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  SubagentInfo,
  SubagentUpsertRequest,
  listSubagents,
  upsertSubagent,
  createFromAgent,
  deleteSubagent,
} from '../api/subagents';
import { AgentDefinition, listAgents } from '../api/agents';
import { readFile } from '../api/workspace';
import { resolveApiErrorMessage } from '@/api/errors';
import { type TranslationFunction, useT } from '../i18n';

interface Props {
  agentId: string;
  onChanged?: () => void;
  readOnly?: boolean;
}

const S: Record<string, React.CSSProperties> = {
  root: {
    flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0, minHeight: 0,
    background: '#ffffff',
  },
  header: {
    padding: '14px 20px', borderBottom: '1px solid #e2e8f0',
    display: 'flex', alignItems: 'center', gap: 10, flexShrink: 0,
  },
  title: {
    flex: 1, fontSize: '0.78rem', color: '#94a3b8', fontWeight: 700,
    textTransform: 'uppercase' as const, letterSpacing: '0.1em',
  },
  btn: {
    padding: '7px 14px', background: '#f8fafc', border: '1px solid #e2e8f0',
    color: '#475569', borderRadius: 8, cursor: 'pointer', fontSize: '0.82rem', fontWeight: 500,
  },
  primaryBtn: {
    padding: '7px 14px',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none', borderRadius: 8, cursor: 'pointer',
    fontSize: '0.82rem', fontWeight: 600,
    boxShadow: '0 1px 3px rgba(99,102,241,0.3)',
  },
  dangerBtn: {
    padding: '7px 14px', background: '#ffffff', color: '#dc2626',
    border: '1px solid #fca5a5', borderRadius: 8, cursor: 'pointer',
    fontSize: '0.82rem', fontWeight: 500,
  },
  scroll: { flex: 1, overflowY: 'auto' as const, padding: '12px 16px' },
  card: {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 12,
    padding: '16px 20px', marginBottom: 10, cursor: 'pointer',
    boxShadow: '0 1px 2px rgba(15,23,42,0.04)',
  },
  cardActive: { borderColor: '#818cf8', background: '#eef2ff' },
  cardName: { fontWeight: 600, fontSize: '0.95rem', color: '#1e293b' },
  cardDesc: { fontSize: '0.85rem', color: '#64748b', marginTop: 4 },
  badge: {
    display: 'inline-block', padding: '2px 8px', borderRadius: 6,
    fontSize: '0.75rem', fontWeight: 600, marginLeft: 8,
  },
  badgeShared: { background: '#dbeafe', color: '#2563eb' },
  badgeIsolated: { background: '#f1f5f9', color: '#64748b' },
  badgeModel: { background: '#fef3c7', color: '#92400e' },
  empty: { padding: 40, textAlign: 'center' as const, color: '#94a3b8', fontSize: '0.92rem' },
  err: { padding: 14, color: '#dc2626', fontSize: '0.88rem' },
  fieldLabel: {
    display: 'block', fontSize: '0.85rem', fontWeight: 500,
    color: '#475569', marginBottom: 6,
  },
  input: {
    width: '100%', boxSizing: 'border-box' as const, padding: '10px 12px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 8,
    color: '#0f172a', fontSize: '0.92rem',
  },
  textarea: {
    width: '100%', boxSizing: 'border-box' as const, padding: '10px 12px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 8,
    color: '#0f172a', fontSize: '0.92rem', lineHeight: 1.55,
    minHeight: 100, resize: 'vertical' as const,
  },
  row: { marginBottom: 14 },
  formActions: { display: 'flex', gap: 10, marginTop: 18 },
  overlay: {
    position: 'fixed' as const, inset: 0, background: 'rgba(15,23,42,0.4)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000,
  },
  dialog: {
    background: '#ffffff', borderRadius: 14, padding: '24px 28px',
    width: 480, maxHeight: '80vh', display: 'flex', flexDirection: 'column' as const,
    boxShadow: '0 10px 40px rgba(15,23,42,0.2)',
  },
  dialogTitle: { fontSize: '1.05rem', fontWeight: 700, color: '#1e293b', marginBottom: 16 },
  searchInput: {
    width: '100%', boxSizing: 'border-box' as const, padding: '10px 14px',
    background: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: 9,
    fontSize: '0.92rem', marginBottom: 12,
  },
  dialogList: { flex: 1, overflowY: 'auto' as const, maxHeight: 320 },
  agentRow: {
    padding: '12px 14px', borderRadius: 9, cursor: 'pointer',
    border: '1px solid transparent', marginBottom: 4,
  },
  agentRowHover: { background: '#f8fafc', borderColor: '#e2e8f0' },
  agentName: { fontWeight: 600, fontSize: '0.92rem', color: '#1e293b' },
  agentDesc: { fontSize: '0.82rem', color: '#64748b', marginTop: 2 },
  status: { fontSize: '0.82rem', marginTop: 6 },
  ok: { color: '#059669' },
  errText: { color: '#dc2626' },
  inlineRow: { display: 'flex', gap: 12 },
  inlineField: { flex: 1 },
  radio: { display: 'flex', gap: 16, alignItems: 'center' },
  radioLabel: { display: 'flex', alignItems: 'center', gap: 4, fontSize: '0.9rem', color: '#334155', cursor: 'pointer' },
};

type View = 'list' | 'edit' | 'picker';

interface FormState {
  name: string;
  description: string;
  model: string;
  maxIters: string;
  tools: string;
  workspaceMode: 'isolated' | 'shared';
  workspacePath: string;
  inlineBody: string;
}

function emptyForm(): FormState {
  return { name: '', description: '', model: '', maxIters: '', tools: '', workspaceMode: 'isolated', workspacePath: '', inlineBody: '' };
}

function formFromInfo(info: SubagentInfo, body: string): FormState {
  return {
    name: info.name,
    description: info.description,
    model: info.model ?? '',
    maxIters: info.maxIters != null ? String(info.maxIters) : '',
    tools: info.tools?.join(', ') ?? '',
    workspaceMode: info.workspaceMode,
    workspacePath: info.workspacePath ?? '',
    inlineBody: body,
  };
}

function workspaceModeLabel(t: TranslationFunction, mode: string): string {
  if (mode === 'shared') return t('subagent.workspaceMode.sharedShort');
  if (mode === 'isolated') return t('subagent.workspaceMode.isolated');
  return mode;
}

export default function SubagentPanel({ agentId, onChanged, readOnly = false }: Props) {
  const t = useT();
  const tRef = useRef(t);
  const [view, setView] = useState<View>('list');
  const [items, setItems] = useState<SubagentInfo[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const [form, setForm] = useState<FormState>(emptyForm());
  const [isNew, setIsNew] = useState(true);
  const [saving, setSaving] = useState(false);
  const [formErr, setFormErr] = useState<string | null>(null);
  const [formOk, setFormOk] = useState(false);

  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [search, setSearch] = useState('');
  const [pickerLoading, setPickerLoading] = useState(false);

  useEffect(() => {
    tRef.current = t;
  }, [t]);

  const reload = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      const list = await listSubagents(agentId);
      setItems(list);
    } catch (e: unknown) {
      setErr(resolveApiErrorMessage(e, tRef.current('subagent.error.load')));
    } finally {
      setLoading(false);
    }
  }, [agentId]);

  useEffect(() => { reload(); }, [reload]);

  function handleNew() {
    setForm(emptyForm());
    setIsNew(true);
    setFormErr(null);
    setFormOk(false);
    setView('edit');
  }

  async function handleEdit(info: SubagentInfo) {
    let body = '';
    if (info.hasInlineBody) {
      try {
        const md = await readFile(agentId, `subagents/${info.name}.md`);
        const endIdx = md.indexOf('---', 3);
        body = endIdx >= 0 ? md.substring(endIdx + 3).trim() : '';
      } catch {
        // ignore read errors
      }
    }
    setForm(formFromInfo(info, body));
    setIsNew(false);
    setFormErr(null);
    setFormOk(false);
    setView('edit');
  }

  async function handleSave() {
    if (!form.description.trim()) {
      setFormErr(t('subagent.error.descriptionRequired'));
      return;
    }
    const nameVal = form.name.trim();
    if (!nameVal) {
      setFormErr(t('subagent.error.nameRequired'));
      return;
    }
    setSaving(true);
    setFormErr(null);
    setFormOk(false);
    try {
      const req: SubagentUpsertRequest = {
        description: form.description.trim(),
        model: form.model.trim() || undefined,
        maxIters: form.maxIters.trim() ? parseInt(form.maxIters, 10) : undefined,
        tools: form.tools.trim() ? form.tools.split(',').map(t => t.trim()).filter(Boolean) : undefined,
        workspaceMode: form.workspaceMode,
        workspacePath: form.workspacePath.trim() || undefined,
        inlineBody: form.inlineBody.trim() || undefined,
      };
      await upsertSubagent(agentId, nameVal, req);
      setFormOk(true);
      await reload();
      onChanged?.();
    } catch (e: unknown) {
      setFormErr(resolveApiErrorMessage(e, t('subagent.error.save')));
    } finally {
      setSaving(false);
    }
  }

  async function handleDelete() {
    if (!form.name || !window.confirm(t('subagent.confirmDelete', { name: form.name }))) return;
    try {
      await deleteSubagent(agentId, form.name);
      setView('list');
      await reload();
      onChanged?.();
    } catch (e: unknown) {
      setFormErr(resolveApiErrorMessage(e, t('subagent.error.delete')));
    }
  }

  async function openPicker() {
    setPickerLoading(true);
    setSearch('');
    try {
      const all = await listAgents();
      setAgents(all.filter(a => a.id !== agentId));
    } catch {
      setAgents([]);
    } finally {
      setPickerLoading(false);
    }
    setView('picker');
  }

  async function pickAgent(agent: AgentDefinition) {
    setView('list');
    try {
      await createFromAgent(agentId, agent.id);
      await reload();
      onChanged?.();
    } catch (e: unknown) {
      setErr(resolveApiErrorMessage(e, t('subagent.error.add')));
    }
  }

  const filteredAgents = agents.filter(a => {
    const q = search.toLowerCase();
    return !q || a.name.toLowerCase().includes(q) || (a.description ?? '').toLowerCase().includes(q);
  });

  if (view === 'picker') {
    return (
      <div style={S.root}>
        <div style={S.overlay} onClick={() => setView('list')}>
          <div style={S.dialog} onClick={e => e.stopPropagation()}>
            <div style={S.dialogTitle}>{t('subagent.picker.title')}</div>
            <input
              style={S.searchInput}
              placeholder={t('subagent.picker.searchPlaceholder')}
              value={search}
              onChange={e => setSearch(e.target.value)}
              autoFocus
            />
            <div style={S.dialogList}>
              {pickerLoading && <div style={S.empty}>{t('subagent.loading')}</div>}
              {!pickerLoading && filteredAgents.length === 0 && (
                <div style={S.empty}>{t('subagent.picker.notFound')}</div>
              )}
              {filteredAgents.map(a => (
                <AgentPickerRow key={a.id} agent={a} onPick={pickAgent} />
              ))}
            </div>
            <div style={{ marginTop: 12, textAlign: 'right' }}>
              <button style={S.btn} onClick={() => setView('list')}>{t('subagent.cancel')}</button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (view === 'edit') {
    return (
      <div style={S.root}>
        <div style={S.header}>
          <button style={S.btn} onClick={() => setView('list')}>← {t('subagent.back')}</button>
          <span style={S.title}>
            {isNew ? t('subagent.new') : t('subagent.editTitle', { name: form.name })}
          </span>
        </div>
        <div style={S.scroll}>
          <div style={S.row}>
            <label style={S.fieldLabel}>{t('subagent.name')} *</label>
            <input
              style={S.input}
              value={form.name}
              onChange={e => setForm({ ...form, name: e.target.value })}
              disabled={!isNew}
              placeholder={t('subagent.namePlaceholder')}
            />
          </div>
          <div style={S.row}>
            <label style={S.fieldLabel}>{t('subagent.description')} *</label>
            <input
              style={S.input}
              value={form.description}
              onChange={e => setForm({ ...form, description: e.target.value })}
              placeholder={t('subagent.descriptionPlaceholder')}
            />
          </div>
          <div style={{ ...S.row, ...S.inlineRow }}>
            <div style={S.inlineField}>
              <label style={S.fieldLabel}>{t('subagent.model')}</label>
              <input
                style={S.input}
                value={form.model}
                onChange={e => setForm({ ...form, model: e.target.value })}
                placeholder={t('subagent.modelPlaceholder')}
              />
            </div>
            <div style={S.inlineField}>
              <label style={S.fieldLabel}>{t('subagent.maxIterations')}</label>
              <input
                style={S.input}
                type="number"
                value={form.maxIters}
                onChange={e => setForm({ ...form, maxIters: e.target.value })}
                placeholder="10"
              />
            </div>
          </div>
          <div style={S.row}>
            <label style={S.fieldLabel}>{t('subagent.tools')}</label>
            <input
              style={S.input}
              value={form.tools}
              onChange={e => setForm({ ...form, tools: e.target.value })}
              placeholder={t('subagent.toolsPlaceholder')}
            />
          </div>
          <div style={S.row}>
            <label style={S.fieldLabel}>{t('subagent.workspaceMode')}</label>
            <div style={S.radio}>
              <label style={S.radioLabel}>
                <input
                  type="radio"
                  checked={form.workspaceMode === 'isolated'}
                  onChange={() => setForm({ ...form, workspaceMode: 'isolated' })}
                /> {t('subagent.workspaceMode.isolated')}
              </label>
              <label style={S.radioLabel}>
                <input
                  type="radio"
                  checked={form.workspaceMode === 'shared'}
                  onChange={() => setForm({ ...form, workspaceMode: 'shared' })}
                /> {t('subagent.workspaceMode.shared')}
              </label>
            </div>
          </div>
          {form.workspaceMode === 'isolated' && (
            <div style={S.row}>
              <label style={S.fieldLabel}>{t('subagent.workspacePath')}</label>
              <input
                style={S.input}
                value={form.workspacePath}
                onChange={e => setForm({ ...form, workspacePath: e.target.value })}
                placeholder={t('subagent.workspacePathPlaceholder')}
              />
            </div>
          )}
          <div style={S.row}>
            <label style={S.fieldLabel}>{t('subagent.systemPrompt')}</label>
            <textarea
              style={S.textarea}
              value={form.inlineBody}
              onChange={e => setForm({ ...form, inlineBody: e.target.value })}
              placeholder={t('subagent.systemPromptPlaceholder')}
            />
          </div>
          {formErr && <div style={{ ...S.status, ...S.errText }}>{formErr}</div>}
          {formOk && <div style={{ ...S.status, ...S.ok }}>{t('subagent.saved')}</div>}
          {!readOnly && (
            <div style={S.formActions}>
              <button style={S.primaryBtn} onClick={handleSave} disabled={saving}>
                {saving ? t('subagent.saving') : t('subagent.save')}
              </button>
              {!isNew && (
                <button style={S.dangerBtn} onClick={handleDelete}>{t('subagent.delete')}</button>
              )}
            </div>
          )}
        </div>
      </div>
    );
  }

  // list view
  return (
    <div style={S.root}>
      <div style={S.header}>
        <span style={S.title}>{t('subagent.title')}</span>
        {!readOnly && (
          <>
            <button style={S.btn} onClick={openPicker}>+ {t('subagent.fromAgent')}</button>
            <button style={S.primaryBtn} onClick={handleNew}>+ {t('subagent.newButton')}</button>
          </>
        )}
      </div>
      <div style={S.scroll}>
        {err && <div style={S.err}>{err}</div>}
        {loading && <div style={S.empty}>{t('subagent.loading')}</div>}
        {!loading && !err && items.length === 0 && (
          <div style={S.empty}>
            {t('subagent.empty')}<br />
            {t('subagent.emptyHelp')}
          </div>
        )}
        {items.map(item => (
          <div
            key={item.name}
            style={S.card}
            onClick={() => handleEdit(item)}
          >
            <div>
              <span style={S.cardName}>{item.name}</span>
              <span style={{ ...S.badge, ...(item.workspaceMode === 'shared' ? S.badgeShared : S.badgeIsolated) }}>
                {workspaceModeLabel(t, item.workspaceMode)}
              </span>
              {item.model && (
                <span style={{ ...S.badge, ...S.badgeModel }}>{item.model}</span>
              )}
            </div>
            <div style={S.cardDesc}>{item.description}</div>
            {item.tools && item.tools.length > 0 && (
              <div style={{ ...S.cardDesc, fontSize: '0.8rem', marginTop: 6, fontFamily: 'monospace' }}>
                {t('subagent.toolsList', { tools: item.tools.join(', ') })}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

function AgentPickerRow({ agent, onPick }: { agent: AgentDefinition; onPick: (a: AgentDefinition) => void }) {
  const [hover, setHover] = useState(false);
  return (
    <div
      style={{ ...S.agentRow, ...(hover ? S.agentRowHover : {}) }}
      onClick={() => onPick(agent)}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
    >
      <div style={S.agentName}>{agent.name}</div>
      {agent.description && <div style={S.agentDesc}>{agent.description}</div>}
    </div>
  );
}
