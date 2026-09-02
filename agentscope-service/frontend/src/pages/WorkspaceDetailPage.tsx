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
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { resolveApiErrorMessage } from '@/api/errors';
import {
  browseMarketplaceSkills,
  createMarketplace,
  deleteMarketplace,
  installMarketplaceSkill,
  listMarketplaces,
  Marketplace,
  MarketplaceSkill,
} from '../api/marketplaces';
import {
  deleteWorkspaceResourceSkill,
  deleteWorkspaceResourceSubagent,
  fetchBuiltinToolCatalog,
  fetchMcpCatalog,
  getWorkspace,
  getWorkspaceResourceSkill,
  getWorkspaceTools,
  listWorkspaceResourceSkills,
  listWorkspaceResourceSubagents,
  putWorkspaceResourceSkill,
  putWorkspaceTools,
  readWorkspaceFile,
  upsertWorkspaceResourceSubagent,
  writeWorkspaceFile,
  WorkspaceSummary,
  BuiltinToolCatalogEntry,
} from '../api/workspaces';
import { AgentToolset, McpServerSpec } from '../api/agents';
import type { WorkspaceSkillInfo } from '../api/skills';
import type { SubagentInfo } from '../api/subagents';
import { useT } from '@/i18n';

type Tab = 'agentsmd' | 'skills' | 'tools' | 'subagents' | 'marketplace';

const card: React.CSSProperties = {
  background: '#fff',
  border: '1px solid #e2e8f0',
  borderRadius: 12,
  padding: 16,
};

export default function WorkspaceDetailPage() {
  const t = useT();
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [ws, setWs] = useState<WorkspaceSummary | null>(null);
  const tabParam = searchParams.get('tab');
  const initialTab: Tab =
    tabParam === 'skills' ||
    tabParam === 'tools' ||
    tabParam === 'subagents' ||
    tabParam === 'marketplace' ||
    tabParam === 'agentsmd'
      ? tabParam
      : 'agentsmd';
  const [tab, setTab] = useState<Tab>(initialTab);
  const [agentsMd, setAgentsMd] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [catalog, setCatalog] = useState<BuiltinToolCatalogEntry[]>([]);
  const [mcpCatalog, setMcpCatalog] = useState<Record<string, unknown>[]>([]);
  const [tools, setTools] = useState<AgentToolset[]>([]);
  const [mcpServers, setMcpServers] = useState<McpServerSpec[]>([]);
  const [markets, setMarkets] = useState<Marketplace[]>([]);
  const [browse, setBrowse] = useState<MarketplaceSkill[]>([]);
  const [selectedMarket, setSelectedMarket] = useState('');
  const [gitUrl, setGitUrl] = useState('');
  const [mktName, setMktName] = useState('');
  const [nacosAddr, setNacosAddr] = useState('');
  const [nacosSkills, setNacosSkills] = useState('');
  const [skills, setSkills] = useState<WorkspaceSkillInfo[]>([]);
  const [selectedSkill, setSelectedSkill] = useState<string | null>(null);
  const [skillMd, setSkillMd] = useState('');
  const [newSkillName, setNewSkillName] = useState('');
  const [subagents, setSubagents] = useState<SubagentInfo[]>([]);
  const [saName, setSaName] = useState('');
  const [saDesc, setSaDesc] = useState('');
  const [saBody, setSaBody] = useState('');

  async function reloadMeta() {
    const w = await getWorkspace(id);
    setWs(w);
  }

  async function reloadSkills() {
    setSkills(await listWorkspaceResourceSkills(id));
  }

  async function reloadSubagents() {
    setSubagents(await listWorkspaceResourceSubagents(id));
  }

  useEffect(() => {
    if (
      tabParam === 'skills' ||
      tabParam === 'tools' ||
      tabParam === 'subagents' ||
      tabParam === 'marketplace' ||
      tabParam === 'agentsmd'
    ) {
      setTab(tabParam);
    }
  }, [tabParam]);

  // Reload for a different workspace, but not for translated fallback text.
  useEffect(() => {
    if (!id) return;
    reloadMeta().catch((e: unknown) =>
      setErr(resolveApiErrorMessage(e, t('managed.workspaces.loadFailed'))),
    );
    readWorkspaceFile(id, 'AGENTS.md')
      .then(f => setAgentsMd(f.content))
      .catch(() => setAgentsMd(''));
    getWorkspaceTools(id)
      .then(t => {
        setTools(t.tools || []);
        setMcpServers(t.mcpServers || []);
      })
      .catch(() => undefined);
    fetchBuiltinToolCatalog().then(setCatalog).catch(() => undefined);
    fetchMcpCatalog().then(setMcpCatalog).catch(() => undefined);
    listMarketplaces().then(setMarkets).catch(() => undefined);
    reloadSkills().catch(() => undefined);
    reloadSubagents().catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  async function saveAgentsMd() {
    setSaving(true);
    try {
      await writeWorkspaceFile(id, 'AGENTS.md', agentsMd);
      await reloadMeta();
      setErr(null);
    } catch (e: unknown) {
      setErr(resolveApiErrorMessage(e, t('managed.common.saveFailed')));
    } finally {
      setSaving(false);
    }
  }

  function enabledSet(): Set<string> {
    const set = new Set<string>();
    for (const ts of tools) {
      if (ts.type !== 'agent_toolset') continue;
      const defaultOn = ts.defaultConfig?.enabled !== false;
      if (!ts.configs || ts.configs.length === 0) {
        if (defaultOn) catalog.forEach(c => set.add(c.id));
        continue;
      }
      for (const c of ts.configs) {
        if (c.enabled !== false) set.add(c.name);
      }
    }
    return set;
  }

  async function toggleTool(productId: string) {
    const enabled = enabledSet();
    if (enabled.has(productId)) enabled.delete(productId);
    else enabled.add(productId);
    const next: AgentToolset[] = [
      {
        type: 'agent_toolset',
        defaultConfig: { enabled: false, permissionPolicy: { type: 'always_allow' } },
        configs: [...enabled].map(name => ({
          name,
          enabled: true,
          permissionPolicy: { type: name === 'bash' ? 'always_ask' : 'always_allow' },
        })),
      },
      ...tools.filter(t => t.type === 'mcp_toolset'),
    ];
    setTools(next);
    await putWorkspaceTools(id, next, mcpServers);
  }

  async function addMcpFromCatalog(entry: Record<string, unknown>) {
    const name = String(entry.id || entry.name || 'mcp');
    if (mcpServers.some(s => s.name === name)) return;
    const nextServers: McpServerSpec[] = [
      ...mcpServers,
      {
        name,
        type: String(entry.transport || 'url'),
        url: entry.url ? String(entry.url) : undefined,
        transport: entry.transport ? String(entry.transport) : undefined,
        command: entry.command ? String(entry.command) : undefined,
        args: Array.isArray(entry.args) ? (entry.args as string[]) : undefined,
      },
    ];
    const nextTools: AgentToolset[] = [
      ...tools.filter(t => !(t.type === 'mcp_toolset' && t.mcpServerName === name)),
      { type: 'mcp_toolset', mcpServerName: name },
    ];
    setMcpServers(nextServers);
    setTools(nextTools);
    await putWorkspaceTools(id, nextTools, nextServers);
  }

  async function openSkill(name: string) {
    setSelectedSkill(name);
    const detail = await getWorkspaceResourceSkill(id, name);
    setSkillMd(detail.markdown);
  }

  return (
    <div style={{ padding: '36px 40px', maxWidth: 1040 }}>
      <button
        onClick={() => navigate('/workspaces')}
        style={{
          background: 'transparent',
          border: 'none',
          color: '#6366f1',
          cursor: 'pointer',
          marginBottom: 12,
          padding: 0,
          fontWeight: 600,
        }}
      >
        ← {t('navigation.managed.workspaces')}
      </button>
      <h1 style={{ margin: '0 0 6px', fontSize: '1.5rem', fontWeight: 700 }}>
        {ws?.name || id}
      </h1>
      <p style={{ margin: '0 0 8px', color: '#64748b' }}>
        {ws?.description || t('managed.workspaceDetail.description')}
      </p>
      <div style={{ color: '#94a3b8', fontSize: '0.82rem', marginBottom: 18 }}>
        v{ws?.version ?? '?'} ·{' '}
        {t('managed.workspaces.skillCount', { count: ws?.skillCount ?? skills.length })}
        {' · '}
        {t('managed.workspaces.subagentCount', {
          count: ws?.subagentCount ?? subagents.length,
        })}
        {ws?.agentsMdExists ? ' · AGENTS.md' : ''}
      </div>
      {err && <div style={{ color: '#dc2626', marginBottom: 12 }}>{err}</div>}

      <div style={{ display: 'flex', gap: 8, marginBottom: 18, flexWrap: 'wrap' }}>
        {(
          [
            ['agentsmd', 'managed.workspaceDetail.tabs.agentsMd'],
            ['skills', 'managed.workspaceDetail.tabs.skills'],
            ['tools', 'managed.workspaceDetail.tabs.tools'],
            ['subagents', 'managed.workspaceDetail.tabs.subagents'],
            ['marketplace', 'managed.workspaceDetail.tabs.marketplace'],
          ] as const
        ).map(([k, labelKey]) => (
          <button
            key={k}
            onClick={() => {
              setTab(k);
              setSearchParams({ tab: k }, { replace: true });
            }}
            style={{
              padding: '8px 14px',
              borderRadius: 8,
              cursor: 'pointer',
              border: tab === k ? '1px solid #c7d2fe' : '1px solid #e2e8f0',
              background: tab === k ? '#eef2ff' : '#fff',
              fontWeight: 600,
              color: '#0f172a',
            }}
          >
            {t(labelKey)}
          </button>
        ))}
      </div>

      {tab === 'agentsmd' && (
        <div>
          <textarea
            value={agentsMd}
            onChange={e => setAgentsMd(e.target.value)}
            style={{
              width: '100%',
              minHeight: 320,
              boxSizing: 'border-box',
              padding: 14,
              borderRadius: 10,
              border: '1px solid #cbd5e1',
              fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
              fontSize: '0.9rem',
            }}
          />
          <button
            onClick={saveAgentsMd}
            disabled={saving}
            style={{
              marginTop: 12,
              padding: '10px 16px',
              border: 'none',
              borderRadius: 8,
              background: '#6366f1',
              color: '#fff',
              fontWeight: 600,
              cursor: 'pointer',
            }}
          >
            {saving
              ? t('common.saving')
              : t('managed.workspaceDetail.saveAgentsMd')}
          </button>
        </div>
      )}

      {tab === 'skills' && (
        <div style={{ display: 'grid', gridTemplateColumns: '280px 1fr', gap: 14, minHeight: 420 }}>
          <div style={{ ...card, padding: 0, overflow: 'hidden' }}>
            <div style={{ padding: 12, borderBottom: '1px solid #e2e8f0', display: 'flex', gap: 8 }}>
              <input
                value={newSkillName}
                onChange={e => setNewSkillName(e.target.value)}
                placeholder={t('managed.workspaceDetail.skillNamePlaceholder')}
                style={{ flex: 1, padding: 8, borderRadius: 8, border: '1px solid #cbd5e1' }}
              />
              <button
                onClick={async () => {
                  const name = newSkillName.trim();
                  if (!name) return;
                  const md = `---\nname: ${name}\ndescription: \n---\n\n# ${name}\n\n`;
                  await putWorkspaceResourceSkill(id, name, md);
                  setNewSkillName('');
                  await reloadSkills();
                  await openSkill(name);
                  await reloadMeta();
                }}
                style={{
                  padding: '8px 10px',
                  borderRadius: 8,
                  border: 'none',
                  background: '#6366f1',
                  color: '#fff',
                  fontWeight: 600,
                  cursor: 'pointer',
                }}
              >
                {t('common.actions.add')}
              </button>
            </div>
            {skills.map(sk => (
              <button
                key={sk.dirName}
                onClick={() => openSkill(sk.dirName)}
                style={{
                  display: 'block',
                  width: '100%',
                  textAlign: 'left',
                  padding: '12px 14px',
                  border: 'none',
                  borderBottom: '1px solid #f1f5f9',
                  background: selectedSkill === sk.dirName ? '#eef2ff' : '#fff',
                  cursor: 'pointer',
                }}
              >
                <div style={{ fontWeight: 650 }}>{sk.name}</div>
                <div style={{ color: '#94a3b8', fontSize: '0.78rem' }}>{sk.description || sk.dirName}</div>
              </button>
            ))}
            {skills.length === 0 && (
              <div style={{ padding: 16, color: '#94a3b8', fontSize: '0.85rem' }}>
                {t('managed.workspaceDetail.noSkills')}
              </div>
            )}
          </div>
          <div style={card}>
            {selectedSkill ? (
              <>
                <div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
                  <strong style={{ flex: 1 }}>{selectedSkill}</strong>
                  <button
                    onClick={async () => {
                      await putWorkspaceResourceSkill(id, selectedSkill, skillMd);
                      await reloadSkills();
                      await reloadMeta();
                    }}
                    style={{
                      padding: '6px 12px',
                      borderRadius: 8,
                      border: '1px solid #c7d2fe',
                      background: '#eef2ff',
                      fontWeight: 600,
                      cursor: 'pointer',
                    }}
                  >
                    {t('common.actions.save')}
                  </button>
                  <button
                    onClick={async () => {
                      if (!confirm(t('managed.workspaceDetail.confirmDeleteSkill', {
                        name: selectedSkill,
                      }))) return;
                      await deleteWorkspaceResourceSkill(id, selectedSkill);
                      setSelectedSkill(null);
                      setSkillMd('');
                      await reloadSkills();
                      await reloadMeta();
                    }}
                    style={{
                      padding: '6px 12px',
                      borderRadius: 8,
                      border: '1px solid #fecaca',
                      background: '#fef2f2',
                      color: '#dc2626',
                      fontWeight: 600,
                      cursor: 'pointer',
                    }}
                  >
                    {t('common.delete')}
                  </button>
                </div>
                <textarea
                  value={skillMd}
                  onChange={e => setSkillMd(e.target.value)}
                  style={{
                    width: '100%',
                    minHeight: 340,
                    boxSizing: 'border-box',
                    border: '1px solid #e2e8f0',
                    borderRadius: 8,
                    padding: 12,
                    fontFamily: 'ui-monospace, Menlo, monospace',
                    fontSize: '0.85rem',
                  }}
                />
              </>
            ) : (
              <div style={{ color: '#94a3b8' }}>
                {t('managed.workspaceDetail.selectSkillHint')}
              </div>
            )}
          </div>
        </div>
      )}

      {tab === 'tools' && (
        <div style={{ display: 'grid', gap: 16 }}>
          <section style={card}>
            <h3 style={{ margin: '0 0 12px' }}>
              {t('managed.workspaceDetail.builtinToolset')}
            </h3>
            <div style={{ display: 'grid', gap: 8 }}>
              {catalog.map(t => {
                const on = enabledSet().has(t.id);
                return (
                  <label key={t.id} style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
                    <input type="checkbox" checked={on} onChange={() => toggleTool(t.id)} />
                    <span>
                      <strong>{t.id}</strong>
                      <span style={{ color: '#94a3b8', marginLeft: 8 }}>{t.group}</span>
                      <div style={{ color: '#64748b', fontSize: '0.85rem' }}>{t.description}</div>
                    </span>
                  </label>
                );
              })}
            </div>
          </section>
          <section style={card}>
            <h3 style={{ margin: '0 0 12px' }}>
              {t('managed.workspaceDetail.mcpCatalog')}
            </h3>
            <div style={{ display: 'grid', gap: 10 }}>
              {mcpCatalog.map(entry => (
                <div key={String(entry.id)} style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontWeight: 600 }}>{String(entry.name)}</div>
                    <div style={{ color: '#64748b', fontSize: '0.85rem' }}>
                      {String(entry.description || '')}
                    </div>
                    {Array.isArray(entry.requiredEnv) && entry.requiredEnv.length > 0 && (
                      <div style={{ color: '#b45309', fontSize: '0.8rem', marginTop: 4 }}>
                        {t('managed.workspaceDetail.vaultEnv', {
                          names: (entry.requiredEnv as string[]).join(', '),
                        })}
                      </div>
                    )}
                  </div>
                  <button
                    onClick={() => addMcpFromCatalog(entry)}
                    style={{
                      padding: '8px 12px',
                      borderRadius: 8,
                      border: '1px solid #c7d2fe',
                      background: '#eef2ff',
                      cursor: 'pointer',
                      fontWeight: 600,
                    }}
                  >
                    {t('common.actions.add')}
                  </button>
                </div>
              ))}
            </div>
            {mcpServers.length > 0 && (
              <div style={{ marginTop: 14, color: '#64748b', fontSize: '0.85rem' }}>
                {t('managed.workspaceDetail.activeMcp', {
                  names: mcpServers.map(s => s.name).join(', '),
                })}
              </div>
            )}
          </section>
        </div>
      )}

      {tab === 'subagents' && (
        <div style={{ display: 'grid', gap: 14 }}>
          <section style={card}>
            <h3 style={{ margin: '0 0 12px' }}>
              {t('managed.workspaceDetail.addSubagent')}
            </h3>
            <div style={{ display: 'grid', gap: 8 }}>
              <input
                placeholder={t('common.fields.name')}
                value={saName}
                onChange={e => setSaName(e.target.value)}
                style={{ padding: 10, borderRadius: 8, border: '1px solid #cbd5e1' }}
              />
              <input
                placeholder={t('managed.workspaceDetail.descriptionRequiredPlaceholder')}
                value={saDesc}
                onChange={e => setSaDesc(e.target.value)}
                style={{ padding: 10, borderRadius: 8, border: '1px solid #cbd5e1' }}
              />
              <textarea
                placeholder={t('managed.workspaceDetail.inlinePromptPlaceholder')}
                value={saBody}
                onChange={e => setSaBody(e.target.value)}
                style={{
                  padding: 10,
                  borderRadius: 8,
                  border: '1px solid #cbd5e1',
                  minHeight: 100,
                }}
              />
              <button
                onClick={async () => {
                  if (!saName.trim() || !saDesc.trim()) {
                    setErr(t('managed.workspaceDetail.subagentFieldsRequired'));
                    return;
                  }
                  await upsertWorkspaceResourceSubagent(id, saName.trim(), {
                    description: saDesc.trim(),
                    inlineBody: saBody,
                    workspaceMode: 'isolated',
                  });
                  setSaName('');
                  setSaDesc('');
                  setSaBody('');
                  await reloadSubagents();
                  await reloadMeta();
                  setErr(null);
                }}
                style={{
                  width: 140,
                  padding: '10px 14px',
                  border: 'none',
                  borderRadius: 8,
                  background: '#6366f1',
                  color: '#fff',
                  fontWeight: 600,
                  cursor: 'pointer',
                }}
              >
                {t('managed.workspaceDetail.saveSubagent')}
              </button>
            </div>
          </section>
          <section style={card}>
            {subagents.map(sa => (
              <div
                key={sa.name}
                style={{
                  display: 'flex',
                  gap: 12,
                  alignItems: 'center',
                  padding: '10px 0',
                  borderBottom: '1px solid #f1f5f9',
                }}
              >
                <div style={{ flex: 1 }}>
                  <strong>{sa.name}</strong>
                  <div style={{ color: '#64748b', fontSize: '0.85rem' }}>{sa.description}</div>
                </div>
                <button
                  onClick={async () => {
                    if (!confirm(t('managed.workspaceDetail.confirmDeleteSubagent', {
                      name: sa.name,
                    }))) return;
                    await deleteWorkspaceResourceSubagent(id, sa.name);
                    await reloadSubagents();
                    await reloadMeta();
                  }}
                  style={{
                    padding: '6px 10px',
                    borderRadius: 8,
                    border: '1px solid #fecaca',
                    background: '#fff',
                    color: '#dc2626',
                    cursor: 'pointer',
                  }}
                >
                  {t('common.delete')}
                </button>
              </div>
            ))}
            {subagents.length === 0 && (
              <div style={{ color: '#94a3b8' }}>
                {t('managed.workspaceDetail.noSubagents')}
              </div>
            )}
          </section>
        </div>
      )}

      {tab === 'marketplace' && (
        <div style={{ display: 'grid', gap: 16 }}>
          <section style={card}>
            <h3 style={{ margin: '0 0 12px' }}>
              {t('managed.workspaceDetail.registerMarketplace')}
            </h3>
            <div style={{ display: 'grid', gap: 10 }}>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <input
                  placeholder={t('common.fields.name')}
                  value={mktName}
                  onChange={e => setMktName(e.target.value)}
                  style={{ padding: 10, border: '1px solid #cbd5e1', borderRadius: 8, minWidth: 160 }}
                />
                <input
                  placeholder={t('managed.workspaceDetail.gitUrlPlaceholder')}
                  value={gitUrl}
                  onChange={e => setGitUrl(e.target.value)}
                  style={{
                    padding: 10,
                    border: '1px solid #cbd5e1',
                    borderRadius: 8,
                    flex: 1,
                    minWidth: 240,
                  }}
                />
                <button
                  onClick={async () => {
                    try {
                      await createMarketplace({
                        name: mktName || 'Git skills',
                        type: 'git',
                        config: { remoteUrl: gitUrl, branch: 'main', skillsRoot: 'skills' },
                      });
                      setMarkets(await listMarketplaces());
                      setGitUrl('');
                      setMktName('');
                      setErr(null);
                    } catch (e: unknown) {
                      setErr(resolveApiErrorMessage(e, t('managed.common.actionFailed')));
                    }
                  }}
                  style={{
                    padding: '10px 14px',
                    border: 'none',
                    borderRadius: 8,
                    background: '#6366f1',
                    color: '#fff',
                    fontWeight: 600,
                    cursor: 'pointer',
                  }}
                >
                  {t('managed.workspaceDetail.addGit')}
                </button>
              </div>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <input
                  placeholder={t('managed.workspaceDetail.nacosAddressPlaceholder')}
                  value={nacosAddr}
                  onChange={e => setNacosAddr(e.target.value)}
                  style={{ padding: 10, border: '1px solid #cbd5e1', borderRadius: 8, minWidth: 200 }}
                />
                <input
                  placeholder={t('managed.workspaceDetail.nacosSkillsPlaceholder')}
                  value={nacosSkills}
                  onChange={e => setNacosSkills(e.target.value)}
                  style={{
                    padding: 10,
                    border: '1px solid #cbd5e1',
                    borderRadius: 8,
                    flex: 1,
                    minWidth: 220,
                  }}
                />
                <button
                  onClick={async () => {
                    try {
                      await createMarketplace({
                        name: mktName || 'Nacos skills',
                        type: 'nacos',
                        config: {
                          serverAddr: nacosAddr,
                          skillNames: nacosSkills
                            .split(',')
                            .map(s => s.trim())
                            .filter(Boolean),
                        },
                      });
                      setMarkets(await listMarketplaces());
                      setNacosAddr('');
                      setNacosSkills('');
                      setErr(null);
                    } catch (e: unknown) {
                      setErr(resolveApiErrorMessage(e, t('managed.common.actionFailed')));
                    }
                  }}
                  style={{
                    padding: '10px 14px',
                    border: '1px solid #c7d2fe',
                    borderRadius: 8,
                    background: '#eef2ff',
                    fontWeight: 600,
                    cursor: 'pointer',
                  }}
                >
                  {t('managed.workspaceDetail.addNacos')}
                </button>
              </div>
            </div>
          </section>

          <section style={card}>
            <h3 style={{ margin: '0 0 12px' }}>
              {t('managed.workspaceDetail.installedRegistries')}
            </h3>
            {markets.map(m => (
              <div
                key={m.id}
                style={{
                  display: 'flex',
                  gap: 10,
                  alignItems: 'center',
                  padding: '8px 0',
                  borderBottom: '1px solid #f1f5f9',
                }}
              >
                <div style={{ flex: 1 }}>
                  <strong>{m.name}</strong>{' '}
                  <span style={{ color: '#94a3b8', fontSize: '0.8rem' }}>({m.type})</span>
                </div>
                <button
                  onClick={async () => {
                    await deleteMarketplace(m.id);
                    setMarkets(await listMarketplaces());
                    if (selectedMarket === m.id) {
                      setSelectedMarket('');
                      setBrowse([]);
                    }
                  }}
                  style={{
                    padding: '6px 10px',
                    borderRadius: 8,
                    border: '1px solid #fecaca',
                    background: '#fff',
                    color: '#dc2626',
                    cursor: 'pointer',
                  }}
                >
                  {t('common.delete')}
                </button>
              </div>
            ))}
          </section>

          <section style={card}>
            <h3 style={{ margin: '0 0 12px' }}>
              {t('managed.workspaceDetail.browseMarketplace')}
            </h3>
            <select
              value={selectedMarket}
              onChange={async e => {
                const mid = e.target.value;
                setSelectedMarket(mid);
                if (mid) setBrowse(await browseMarketplaceSkills(mid));
                else setBrowse([]);
              }}
              style={{
                padding: 10,
                borderRadius: 8,
                border: '1px solid #cbd5e1',
                marginBottom: 12,
              }}
            >
              <option value="">{t('managed.workspaceDetail.selectMarketplace')}</option>
              {markets.map(m => (
                <option key={m.id} value={m.id}>
                  {m.name} ({m.type})
                </option>
              ))}
            </select>
            <div style={{ display: 'grid', gap: 8 }}>
              {browse.map(sk => (
                <div
                  key={sk.dirName || sk.name}
                  style={{ display: 'flex', gap: 12, alignItems: 'center' }}
                >
                  <div style={{ flex: 1 }}>
                    <strong>{sk.name}</strong>
                    <div style={{ color: '#64748b', fontSize: '0.85rem' }}>{sk.description}</div>
                  </div>
                  <button
                    onClick={async () => {
                      try {
                        await installMarketplaceSkill(id, selectedMarket, sk.dirName || sk.name);
                        await reloadSkills();
                        await reloadMeta();
                        setErr(null);
                      } catch (e: unknown) {
                        setErr(resolveApiErrorMessage(e, t('managed.workspaceDetail.installFailed')));
                      }
                    }}
                    style={{
                      padding: '8px 12px',
                      borderRadius: 8,
                      border: '1px solid #bbf7d0',
                      background: '#dcfce7',
                      cursor: 'pointer',
                      fontWeight: 600,
                    }}
                  >
                    {t('managed.workspaceDetail.install')}
                  </button>
                </div>
              ))}
            </div>
          </section>
        </div>
      )}
    </div>
  );
}
