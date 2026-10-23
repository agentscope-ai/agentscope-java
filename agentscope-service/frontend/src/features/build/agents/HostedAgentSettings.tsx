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

import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import {
  getHostedAgentSettings,
  listHostedRuntimeOptions,
  updateAgent,
  updateHostedAgentSettings,
  type AgentDefinition,
  type HostedExecutionOverrides,
} from '@/api/agents';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { JsonViewer } from '@/components/JsonViewer';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input, Textarea } from '@/components/ui/input';

import { RuntimeHostCapacity } from './RuntimeHostCapacity';

const reasoningLevels = ['', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra'];

const claudePermissionModes = [
  ['', 'Runtime Profile default'],
  ['default', 'Default — ask for sensitive actions'],
  ['acceptEdits', 'Accept edits — approve workspace edits'],
  ['dontAsk', 'Don’t ask — deny actions needing approval'],
  ['plan', 'Plan — read-only planning'],
] as const;

const qoderPermissionModes = [
  ['', 'Runtime Profile default'],
  ['default', 'Default — use explicit allow rules'],
  ['auto', 'Auto — unattended policy decision'],
  ['accept_edits', 'Accept edits — approve workspace edits'],
  ['bypass_permissions', 'Full access — allow tools without approval'],
  ['dont_ask', 'Don’t ask — deny actions needing approval'],
] as const;

function configObject(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function commandHeader(provider: string) {
  if (provider === 'codex') return 'codex app-server';
  if (provider === 'claude-code') return 'claude -p';
  if (provider === 'qoder') return 'qodercli -p';
  if (provider === 'qwenpaw') return 'qwenpaw acp';
  if (provider === 'openclaw') return 'openclaw agent exec';
  return provider;
}

function quoteArgument(value: string) {
  return /\s/.test(value) ? JSON.stringify(value) : value;
}

function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}

function StringListSetting({ label, value, disabled, placeholder, description, onChange }: {
  label: string;
  value: unknown;
  disabled: boolean;
  placeholder?: string;
  description?: string;
  onChange: (value: string[] | '') => void;
}) {
  return <label className="grid gap-1.5 text-sm">
    <span className="font-medium">{label}</span>
    <Textarea
      className="min-h-20 font-mono text-xs"
      value={stringList(value).join('\n')}
      disabled={disabled}
      placeholder={placeholder}
      onChange={event => {
        const items = event.target.value.split('\n').map(item => item.trim()).filter(Boolean);
        onChange(items.length > 0 ? items : '');
      }}
    />
    {description && <span className="text-xs text-muted-foreground">{description}</span>}
  </label>;
}

function BooleanSetting({ label, value, disabled, description, onChange }: {
  label: string;
  value: unknown;
  disabled: boolean;
  description?: string;
  onChange: (value: boolean | '') => void;
}) {
  const selected = typeof value === 'boolean' ? String(value) : '';
  return <label className="grid gap-1.5 text-sm">
    <span className="font-medium">{label}</span>
    <select className="h-10 rounded-md border bg-background px-3" value={selected} disabled={disabled} onChange={event => {
      onChange(event.target.value === '' ? '' : event.target.value === 'true');
    }}>
      <option value="">Runtime Profile default</option>
      <option value="true">Enabled</option>
      <option value="false">Disabled</option>
    </select>
    {description && <span className="text-xs text-muted-foreground">{description}</span>}
  </label>;
}

function PermissionModeSetting({ provider, value, disabled, onChange }: {
  provider: 'claude-code' | 'qoder';
  value: unknown;
  disabled: boolean;
  onChange: (value: string) => void;
}) {
  const options = provider === 'qoder' ? qoderPermissionModes : claudePermissionModes;
  return <label className="grid gap-1.5 text-sm">
    <span className="font-medium">Permission mode</span>
    <select className="h-10 rounded-md border bg-background px-3" value={String(value ?? '')} disabled={disabled} onChange={event => onChange(event.target.value)}>
      {options.map(([mode, label]) => <option key={mode || 'inherit'} value={mode}>{label}</option>)}
    </select>
    {provider === 'claude-code' && <span className="text-xs text-muted-foreground">Dangerous permission bypass modes are intentionally not offered as Agent-level presets.</span>}
    {provider === 'qoder' && value === 'bypass_permissions' && <span className="text-xs text-muted-foreground">Tools run without Qoder approval prompts, with the permissions of the Host process. Applies to new executions after saving.</span>}
    {provider === 'qoder' && <span className="text-xs text-muted-foreground">In default mode, sensitive tool requests are forwarded to AgentScope Approvals. Auto mode may decide without a human review.</span>}
  </label>;
}

export function HostedAgentSettings({ agent, canEdit }: { agent: AgentDefinition; canEdit: boolean }) {
  const scope = useControlPlaneScope();
  const queryClient = useQueryClient();
  const settingsQuery = useQuery({
    queryKey: ['hosted-agent-settings', agent.id],
    queryFn: () => getHostedAgentSettings(agent.id),
  });
  const runtimesQuery = useQuery({
    queryKey: ['hosted-runtime-options', scope.tenant, scope.namespace],
    queryFn: () => listHostedRuntimeOptions(scope.tenant, scope.namespace),
  });
  const [runtimeId, setRuntimeId] = useState('');
  const [model, setModel] = useState(agent.model ?? '');
  const [reasoningEffort, setReasoningEffort] = useState('');
  const [serviceTier, setServiceTier] = useState('');
  const [maxConcurrency, setMaxConcurrency] = useState('0');
  const [providerConfiguration, setProviderConfiguration] = useState<Record<string, unknown>>({});
  const [customArgsText, setCustomArgsText] = useState('');
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ tone: 'ok' | 'error'; text: string }>();

  const settings = settingsQuery.data;
  const runtimes = runtimesQuery.data?.runtimes ?? [];
  useEffect(() => {
    if (!settings) return;
    setRuntimeId(`${settings.runtimeProfile.id}:${settings.runtimePool.id}`);
    setReasoningEffort(settings.executionOverrides?.reasoningEffort ?? '');
    setServiceTier(settings.executionOverrides?.serviceTier ?? '');
    setMaxConcurrency(String(settings.maxConcurrency || 0));
    setProviderConfiguration(configObject(settings.executionOverrides?.providerConfiguration));
    setCustomArgsText((settings.executionOverrides?.customArgs ?? []).join('\n'));
  }, [settings]);
  useEffect(() => setModel(agent.model ?? ''), [agent.model, agent.version]);

  const selectedRuntime = runtimes.find(runtime => runtime.id === runtimeId);
  const provider = selectedRuntime?.provider ?? settings?.runtimeProfile.provider ?? '';
  const customArgs = customArgsText.split('\n').map(value => value.trim()).filter(Boolean);
  const resolvedConfiguration = useMemo(() => ({
    ...configObject(settings?.runtimeProfile.configuration),
    ...providerConfiguration,
    ...(reasoningEffort ? { reasoningEffort } : {}),
    ...(serviceTier ? { serviceTier } : {}),
  }), [providerConfiguration, reasoningEffort, serviceTier, settings?.runtimeProfile.configuration]);

  const setProviderValue = (key: string, value: unknown) => {
    setProviderConfiguration(current => {
      const next = { ...current };
      if (value === '' || value == null) delete next[key]; else next[key] = value;
      return next;
    });
  };

  async function save() {
    if (!settings) return;
    const runtime = runtimes.find(item => item.id === runtimeId);
    const profileId = runtime?.runtimeProfileId ?? settings.runtimeProfile.id;
    const poolId = runtime?.runtimePoolId ?? settings.runtimePool.id;
    const parsedConcurrency = Number(maxConcurrency);
    if (!Number.isInteger(parsedConcurrency) || parsedConcurrency < 0 || parsedConcurrency > 50) {
      setMessage({ tone: 'error', text: 'Concurrency must be an integer between 0 and 50.' });
      return;
    }
    setSaving(true);
    setMessage(undefined);
    try {
      const executionOverrides: HostedExecutionOverrides = {
        reasoningEffort: reasoningEffort || undefined,
        serviceTier: serviceTier || undefined,
        providerConfiguration,
        customArgs,
      };
      await updateHostedAgentSettings(agent.id, {
        runtimeProfileId: profileId,
        runtimePoolId: poolId,
        executionOverrides,
        maxConcurrency: parsedConcurrency,
        bindingVersion: settings.bindingVersion,
        policyVersion: settings.policyVersion,
      });
      if (model !== (agent.model ?? '')) {
        if (agent.version == null) throw new Error('Missing Agent definition version');
        await updateAgent(agent.id, { name: agent.name, model, version: agent.version });
      }
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['hosted-agent-settings', agent.id] }),
        queryClient.invalidateQueries({ queryKey: ['catalog-agent', agent.id] }),
        queryClient.invalidateQueries({ queryKey: ['catalog-agent-overview', agent.id] }),
      ]);
      setMessage({ tone: 'ok', text: 'Hosted execution settings saved. New attempts use the updated configuration.' });
    } catch (error) {
      setMessage({ tone: 'error', text: error instanceof Error ? error.message : 'Failed to save settings' });
    } finally {
      setSaving(false);
    }
  }

  if (settingsQuery.isLoading) return <p className="text-sm text-muted-foreground">Loading Hosted settings…</p>;
  if (!settings) return <p className="text-sm text-red-600">{settingsQuery.error instanceof Error ? settingsQuery.error.message : 'Hosted settings are unavailable.'}</p>;

  return <div className="grid gap-5">
    <Card>
      <CardHeader><CardTitle>Execution</CardTitle><CardDescription>Per-Agent choices layered over the shared Runtime Profile. Existing attempts keep their frozen snapshot.</CardDescription></CardHeader>
      <CardContent className="grid gap-4">
        <label className="grid gap-1.5 text-sm"><span className="font-medium">Runtime</span><select className="h-10 rounded-md border bg-background px-3" value={runtimeId} disabled={!canEdit} onChange={event => {
          setRuntimeId(event.target.value); setModel(''); setReasoningEffort(''); setServiceTier(''); setProviderConfiguration({});
        }}>{!runtimes.some(runtime => runtime.id === runtimeId) && <option value={runtimeId}>{settings.runtimeProfile.provider} · {settings.runtimePool.name}</option>}{runtimes.map(runtime => <option key={runtime.id} value={runtime.id}>{runtime.name}</option>)}</select></label>
        <label className="grid gap-1.5 text-sm"><span className="font-medium">Model</span><Input value={model} disabled={!canEdit} onChange={event => setModel(event.target.value)} placeholder="Follow runtime / CLI configuration" /></label>
        <div className="grid gap-4 md:grid-cols-3">
          <label className="grid gap-1.5 text-sm"><span className="font-medium">Thinking</span><select className="h-10 rounded-md border bg-background px-3" value={reasoningEffort} disabled={!canEdit} onChange={event => setReasoningEffort(event.target.value)}>{reasoningLevels.map(level => <option key={level || 'default'} value={level}>{level || 'Follow CLI config'}</option>)}</select></label>
          <label className="grid gap-1.5 text-sm"><span className="font-medium">Speed</span><select className="h-10 rounded-md border bg-background px-3" value={serviceTier} disabled={!canEdit || provider !== 'codex'} onChange={event => setServiceTier(event.target.value)}><option value="">Runtime default</option><option value="priority">Priority / fast</option></select></label>
          <label className="grid gap-1.5 text-sm"><span className="font-medium">Agent concurrency</span><Input type="number" min="0" max="50" value={maxConcurrency} disabled={!canEdit} onChange={event => setMaxConcurrency(event.target.value)} /><span className="text-xs text-muted-foreground">Per-Agent limit. 0 uses the scheduler default; maximum 50. Host capacity can further limit parallel execution.</span></label>
        </div>
      </CardContent>
    </Card>

    <RuntimeHostCapacity poolName={settings.runtimePool.name} />

    <Card>
      <CardHeader><CardTitle>Provider settings</CardTitle><CardDescription>Structured overrides for {provider || 'the selected provider'}; the shared profile remains unchanged.</CardDescription></CardHeader>
      <CardContent className="grid gap-4">
        {provider === 'codex' && <>
          <label className="grid gap-1.5 text-sm"><span className="font-medium">Codex profile</span><Input value={String(providerConfiguration.profile ?? '')} disabled={!canEdit} onChange={event => setProviderValue('profile', event.target.value)} placeholder="Follow ~/.codex/config.toml" /><span className="text-xs text-muted-foreground">Optional named Codex profile. Leave blank to inherit the local CLI configuration.</span></label>
          <label className="grid gap-1.5 text-sm"><span className="font-medium">Sandbox</span><select className="h-10 rounded-md border bg-background px-3" value={String(providerConfiguration.sandbox ?? '')} disabled={!canEdit} onChange={event => setProviderValue('sandbox', event.target.value)}><option value="">Runtime Profile default</option><option value="read-only">read-only</option><option value="workspace-write">workspace-write</option></select><span className="text-xs text-muted-foreground">An Agent may make the Profile sandbox stricter, but cannot relax it.</span></label>
          <span className="text-xs text-muted-foreground">Codex app-server natively supports AgentScope-created non-Git workspaces and routes sensitive-action approvals through the control plane.</span>
        </>}
        {provider === 'claude-code' && <>
          <PermissionModeSetting provider="claude-code" value={providerConfiguration.permissionMode} disabled={!canEdit} onChange={value => setProviderValue('permissionMode', value)} />
          <StringListSetting label="Allowed tools" value={providerConfiguration.allowedTools} disabled={!canEdit} onChange={value => setProviderValue('allowedTools', value)} placeholder={'mcp__agentscope-collaboration__*\nRead\nGrep'} description="One Claude tool rule per line. AgentScope collaboration is pre-authorized by the automatic Runtime Profile." />
          <StringListSetting label="Disallowed tools" value={providerConfiguration.disallowedTools} disabled={!canEdit} onChange={value => setProviderValue('disallowedTools', value)} placeholder={'Bash(rm -rf:*)'} description="Deny rules take precedence over allow rules." />
          <label className="grid gap-1.5 text-sm"><span className="font-medium">Max turns</span><Input type="number" min="0" value={String(providerConfiguration.maxTurns ?? '')} disabled={!canEdit} onChange={event => setProviderValue('maxTurns', event.target.value ? Number(event.target.value) : '')} placeholder="Provider default" /><span className="text-xs text-muted-foreground">Bounds unattended agent loops; 0 or blank follows the Runtime Profile.</span></label>
        </>}
        {provider === 'qoder' && <>
          <PermissionModeSetting provider="qoder" value={providerConfiguration.permissionMode} disabled={!canEdit} onChange={value => setProviderValue('permissionMode', value)} />
          <StringListSetting label="Allowed tools" value={providerConfiguration.allowedTools} disabled={!canEdit} onChange={value => setProviderValue('allowedTools', value)} placeholder={'mcp__agentscope-collaboration__*\nRead\nGrep'} description="One Qoder permission rule per line. Exact allow rules keep headless jobs usable without enabling YOLO mode." />
          <StringListSetting label="Disallowed tools" value={providerConfiguration.disallowedTools} disabled={!canEdit} onChange={value => setProviderValue('disallowedTools', value)} placeholder={'Bash(rm -rf:*)'} description="Deny and safety rules take precedence over allow rules." />
          <div className="grid gap-4 md:grid-cols-3">
            <label className="grid gap-1.5 text-sm"><span className="font-medium">Max turns</span><Input type="number" min="0" value={String(providerConfiguration.maxTurns ?? '')} disabled={!canEdit} onChange={event => setProviderValue('maxTurns', event.target.value ? Number(event.target.value) : '')} placeholder="Default" /></label>
            <label className="grid gap-1.5 text-sm"><span className="font-medium">Max output tokens</span><Input type="number" min="0" value={String(providerConfiguration.maxOutputTokens ?? '')} disabled={!canEdit} onChange={event => setProviderValue('maxOutputTokens', event.target.value ? Number(event.target.value) : '')} placeholder="Default" /></label>
            <label className="grid gap-1.5 text-sm"><span className="font-medium">Context window</span><Input type="number" min="0" value={String(providerConfiguration.contextWindow ?? '')} disabled={!canEdit} onChange={event => setProviderValue('contextWindow', event.target.value ? Number(event.target.value) : '')} placeholder="Model default" /></label>
          </div>
          <BooleanSetting label="Strict MCP configuration" value={providerConfiguration.strictMCPConfig} disabled={!canEdit} onChange={value => setProviderValue('strictMCPConfig', value)} description="Use only MCP servers materialized by AgentScope for this attempt; enabled in the automatic Qoder Profile." />
          <label className="grid gap-1.5 text-sm"><span className="font-medium">Qoder Agent</span><Input value={String(providerConfiguration.agent ?? '')} disabled={!canEdit} onChange={event => setProviderValue('agent', event.target.value)} placeholder="Default agent" /><span className="text-xs text-muted-foreground">Optional installed Qoder Agent name passed through --agent.</span></label>
        </>}
        {provider === 'qwenpaw' && <>
          <label className="grid gap-1.5 text-sm"><span className="font-medium">QwenPaw Agent</span><Input value={String(providerConfiguration.agent ?? '')} disabled={!canEdit} onChange={event => setProviderValue('agent', event.target.value)} placeholder="Default agent" /></label>
          <label className="grid gap-1.5 text-sm"><span className="font-medium">Runtime provider</span><Input value={String(providerConfiguration.runtimeProvider ?? '')} disabled={!canEdit} onChange={event => setProviderValue('runtimeProvider', event.target.value)} placeholder="QwenPaw default" /></label>
          <BooleanSetting label="Local diagnostics" value={providerConfiguration.localDiagnostics} disabled={!canEdit} onChange={value => setProviderValue('localDiagnostics', value)} description="Include QwenPaw local diagnostic events for troubleshooting." />
        </>}
        {provider === 'openclaw' && <>
          <div className="grid gap-4 md:grid-cols-3">
            <label className="grid gap-1.5 text-sm"><span className="font-medium">Thinking</span><select className="h-10 rounded-md border bg-background px-3" value={String(providerConfiguration.thinking ?? '')} disabled={!canEdit} onChange={event => setProviderValue('thinking', event.target.value)}><option value="">Follow model default</option>{['minimal', 'low', 'medium', 'high', 'xhigh'].map(value => <option key={value} value={value}>{value}</option>)}</select></label>
            <label className="grid gap-1.5 text-sm"><span className="font-medium">Code mode</span><select className="h-10 rounded-md border bg-background px-3" value={String(providerConfiguration.codeMode ?? '')} disabled={!canEdit} onChange={event => setProviderValue('codeMode', event.target.value)}><option value="">Runtime Profile default</option><option value="direct">direct</option><option value="auto">auto</option><option value="code">code</option></select></label>
            <label className="grid gap-1.5 text-sm"><span className="font-medium">Timeout seconds</span><Input type="number" min="0" value={String(providerConfiguration.timeoutSeconds ?? '')} disabled={!canEdit} onChange={event => setProviderValue('timeoutSeconds', event.target.value ? Number(event.target.value) : '')} placeholder="600" /></label>
          </div>
          <StringListSetting label="Fallback models" value={providerConfiguration.fallbacks} disabled={!canEdit} onChange={value => setProviderValue('fallbacks', value)} placeholder={'anthropic/claude-sonnet\nollama/qwen'} description="One provider/model per line, tried in order after the primary model." />
          <div className="grid gap-4 md:grid-cols-3">
            <BooleanSetting label="Local-model lean tools" value={providerConfiguration.localModelLean} disabled={!canEdit} onChange={value => setProviderValue('localModelLean', value)} />
            <BooleanSetting label="Isolated configuration" value={providerConfiguration.isolated} disabled={!canEdit} onChange={value => setProviderValue('isolated', value)} description="Ignore ambient OpenClaw configuration." />
            <BooleanSetting label="Environment auth only" value={providerConfiguration.authEnvOnly} disabled={!canEdit} onChange={value => setProviderValue('authEnvOnly', value)} description="Ignore stored and external CLI credentials." />
          </div>
        </>}
        {!['codex', 'claude-code', 'qoder', 'qwenpaw', 'openclaw'].includes(provider) && <p className="text-sm text-muted-foreground">This provider currently exposes model and execution preferences only. Use Custom Args for supported non-reserved CLI options.</p>}
      </CardContent>
    </Card>

    <Card>
      <details>
        <summary className="cursor-pointer list-none"><CardHeader><CardTitle>Advanced CLI arguments</CardTitle><CardDescription>Optional escape hatch for supported provider flags that do not yet have a structured field.</CardDescription></CardHeader></summary>
        <CardContent className="grid gap-4">
          <p className="text-xs text-muted-foreground">One argv token per line. Workspace, model, sandbox, protocol, resume, MCP, and permission arguments are reserved by AgentScope.</p>
          <Textarea className="min-h-32 font-mono text-xs" value={customArgsText} disabled={!canEdit} onChange={event => setCustomArgsText(event.target.value)} placeholder={'--profile\nwork'} />
          <div><div className="mb-2 text-sm font-medium">Custom argument preview</div><code className="block overflow-auto rounded-lg bg-slate-950 p-4 text-xs text-slate-100">{[commandHeader(provider), ...customArgs.map(quoteArgument)].join(' ')}</code></div>
        </CardContent>
      </details>
    </Card>

    <Card>
      <CardHeader><CardTitle>Effective configuration preview</CardTitle><CardDescription>Profile v{settings.runtimeProfile.version} baseline plus this Agent's structured overrides. The immutable attempt snapshot is authoritative after dispatch.</CardDescription></CardHeader>
      <CardContent><JsonViewer value={resolvedConfiguration} className="max-h-72" /></CardContent>
    </Card>

    <div className="flex items-center justify-end gap-3">{message && <span className={`mr-auto text-sm ${message.tone === 'ok' ? 'text-emerald-600' : 'text-red-600'}`}>{message.text}</span>}<Button onClick={() => void save()} disabled={!canEdit || saving}>{saving ? 'Saving…' : 'Save settings'}</Button></div>
  </div>;
}
