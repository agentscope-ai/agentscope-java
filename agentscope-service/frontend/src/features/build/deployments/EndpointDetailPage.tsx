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

import { EndpointUsage } from '@/components/EndpointUsage';
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, Copy, ExternalLink, History, KeyRound, RefreshCw, ShieldCheck } from 'lucide-react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  archiveEndpoint,
  createEndpointCredential,
  disableEndpoint,
  Endpoint,
  getEndpoint,
  getEndpointReadiness,
  listEndpointCredentials,
  listEndpointInvocations,
  listEndpointReleases,
  patchEndpoint,
  publishEndpoint,
  revealEndpointCredential,
  rollbackEndpointRelease,
  revokeEndpointCredential,
  rotateEndpointCredential,
} from '@/api/agentEndpoints';
import { getRoles } from '@/api/auth';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { Page, PageHeader } from '@/components/Page';
import { InvocationPlayground } from '@/components/InvocationPlayground';
import { EntityIdentityText, useEntityIdentities } from '@/components/EntityIdentity';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { safeEndpointOwnerPath } from './endpointNavigation';

type Tab = 'overview' | 'contract' | 'security' | 'playground' | 'invocations' | 'releases' | 'target';
const tabs: { id: Tab; label: string }[] = [
  { id: 'overview', label: 'Overview' },
  { id: 'contract', label: 'Contract' },
  { id: 'security', label: 'Security' },
  { id: 'playground', label: 'Test API' },
  { id: 'invocations', label: 'Invocations' },
  { id: 'releases', label: 'Releases' },
  { id: 'target', label: 'Target' },
];

const lifecycleTone = (status: Endpoint['status']) => status === 'published' ? 'success' : status === 'draft' ? 'info' : status === 'disabled' ? 'warning' : 'default';
const readinessTone = (state?: string) => state === 'ready' ? 'success' : state === 'degraded' ? 'warning' : state === 'incompatible' || state === 'unavailable' ? 'danger' : 'default';

export default function EndpointDetailPage() {
  const { endpointId = '' } = useParams();
  const scope = useControlPlaneScope();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const roles = getRoles().map(role => role.toLowerCase());
  const canEdit = roles.includes('admin') || roles.includes('agent_developer');
  const [params, setParams] = useSearchParams();
  const requestedTab = params.get('tab') as Tab | null;
  const tab = tabs.some(item => item.id === requestedTab) ? requestedTab! : 'overview';
  const endpointQuery = useQuery({ queryKey: ['endpoint', endpointId], queryFn: () => getEndpoint(endpointId), enabled: !!endpointId });
  const endpoint = endpointQuery.data?.endpoint;
  const ownerPath = safeEndpointOwnerPath(params.get('returnTo'), endpoint);
  const readiness = useQuery({
    queryKey: ['endpoint-readiness', endpointId],
    queryFn: () => getEndpointReadiness(endpointId),
    enabled: !!endpointId,
    refetchInterval: endpoint?.status === 'published' ? 10_000 : false,
  });
  const credentials = useQuery({ queryKey: ['endpoint-credentials', endpointId], queryFn: () => listEndpointCredentials(endpointId), enabled: !!endpointId && tab === 'security' });
  const invocations = useQuery({ queryKey: ['endpoint-invocations', endpointId], queryFn: () => listEndpointInvocations(endpointId), enabled: !!endpointId && (tab === 'overview' || tab === 'invocations'), refetchInterval: tab === 'invocations' ? 5_000 : false });
  const releases = useQuery({ queryKey: ['endpoint-releases', endpointId], queryFn: () => listEndpointReleases(endpointId), enabled: !!endpointId && (tab === 'overview' || tab === 'releases') });
  const identities = useEntityIdentities([
    { type: endpoint?.targetType, ref: endpoint?.targetRef },
    ...(releases.data?.items || []).map(release => ({ type: release.targetType, ref: release.targetRef })),
  ]);
  const [secret, setSecret] = useState('');
  const [credentialName, setCredentialName] = useState('');
  const [error, setError] = useState('');
  const [editingContract, setEditingContract] = useState(false);
  const [inputSchemaText, setInputSchemaText] = useState('');
  const [outputSchemaText, setOutputSchemaText] = useState('');
  const [timeoutText, setTimeoutText] = useState('300');
  const [maxPayloadText, setMaxPayloadText] = useState('1048576');
  const [rateRequestsText, setRateRequestsText] = useState('60');
  const [rateWindowText, setRateWindowText] = useState('60');
  const [editingDetails, setEditingDetails] = useState(false);
  const [nameText, setNameText] = useState('');
  const [descriptionText, setDescriptionText] = useState('');

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['endpoint', endpointId] });
    void queryClient.invalidateQueries({ queryKey: ['endpoint-readiness', endpointId] });
    void queryClient.invalidateQueries({ queryKey: ['endpoint-credentials', endpointId] });
    void queryClient.invalidateQueries({ queryKey: ['endpoint-invocations', endpointId] });
    void queryClient.invalidateQueries({ queryKey: ['endpoint-releases', endpointId] });
  };
  const lifecycle = useMutation({
    mutationFn: async (action: 'publish' | 'disable' | 'archive') => {
      if (!endpoint) throw new Error('Endpoint is unavailable');
      if (action === 'publish') return publishEndpoint(endpoint);
      if (action === 'disable') return disableEndpoint(endpoint);
      return archiveEndpoint(endpoint);
    },
    onSuccess: (_, action) => {
      if (action === 'archive') navigate(scope.scopedPath(ownerPath));
      else refresh();
    },
    onError: cause => setError(cause instanceof Error ? cause.message : 'Endpoint lifecycle update failed'),
  });
  const credentialAction = useMutation({
    mutationFn: async (request: { type: 'create'; name: string } | { type: 'rotate' | 'revoke' | 'reveal'; id: string }) => {
      if (request.type === 'create') return createEndpointCredential(endpointId, { name: request.name });
      if (request.type === 'rotate') return rotateEndpointCredential(endpointId, request.id);
      if (request.type === 'reveal') return revealEndpointCredential(endpointId, request.id);
      return revokeEndpointCredential(endpointId, request.id);
    },
    onSuccess: (result, request) => {
      if ('secret' in result && typeof result.secret === 'string') {
        setSecret(result.secret);
        if (request.type === 'reveal') void navigator.clipboard.writeText(result.secret);
      }
      setCredentialName('');
      refresh();
    },
    onError: cause => setError(cause instanceof Error ? cause.message : 'Credential update failed'),
  });
  const updateContract = useMutation({
    mutationFn: async () => {
      if (!endpoint) throw new Error('Endpoint is unavailable');
      const operational = {
        timeoutSeconds: Number(timeoutText),
        maxPayloadBytes: Number(maxPayloadText),
        rateLimit: { requests: Number(rateRequestsText), windowSeconds: Number(rateWindowText) },
      };
      if (endpoint.status === 'published') return patchEndpoint(endpoint, operational);
      return patchEndpoint(endpoint, {
        ...operational,
        inputSchema: inputSchemaText.trim() ? JSON.parse(inputSchemaText) : null,
        outputSchema: outputSchemaText.trim() ? JSON.parse(outputSchemaText) : null,
      });
    },
    onSuccess: () => { setEditingContract(false); refresh(); },
    onError: cause => setError(cause instanceof Error ? cause.message : 'Contract update failed'),
  });
  const rollback = useMutation({
    mutationFn: (releaseId: string) => {
      if (!endpoint) throw new Error('Endpoint is unavailable');
      return rollbackEndpointRelease(endpoint, releaseId);
    },
    onSuccess: refresh,
    onError: cause => setError(cause instanceof Error ? cause.message : 'Rollback failed'),
  });
  const updateDetails = useMutation({
    mutationFn: () => {
      if (!endpoint) throw new Error('Endpoint is unavailable');
      if (!nameText.trim()) throw new Error('API name is required');
      return patchEndpoint(endpoint, { name: nameText.trim(), description: descriptionText.trim() });
    },
    onSuccess: () => { setEditingDetails(false); setError(''); refresh(); },
    onError: cause => setError(cause instanceof Error ? cause.message : 'API details update failed'),
  });

  if (endpointQuery.isLoading) return <Page><p className="text-sm text-muted-foreground">Loading Endpoint…</p></Page>;
  if (!endpoint) return <Page><PageHeader title="Endpoint unavailable" /><p className="text-sm text-red-600">{endpointQuery.error instanceof Error ? endpointQuery.error.message : 'Endpoint was not found.'}</p></Page>;
  const invokePath = `/invoke/v1/endpoints/${endpoint.slug}/${endpoint.invocationMode === 'job' ? 'jobs' : 'conversations'}`;
  const ownerLabel = endpoint.targetType === 'agent' ? 'Agent' : endpoint.targetType === 'team' ? 'Team' : 'Workflow';

  return (
    <Page>
      <button type="button" className="inline-flex cursor-pointer items-center gap-1 self-start border-0 bg-transparent p-0 text-sm text-muted-foreground hover:text-foreground" aria-label="Back to previous page" title="Back to previous page" onClick={() => navigate(-1)}><ArrowLeft className="h-4 w-4" />Back</button>
      <PageHeader
        title={endpoint.name}
        description={endpoint.description || 'A stable public API contract governed by AgentScope Service.'}
        actions={<div className="flex flex-wrap gap-2"><Button variant="outline" size="sm" onClick={refresh}><RefreshCw className="h-4 w-4" />Refresh</Button>{canEdit && <Button variant="outline" size="sm" onClick={() => { setEditingDetails(value => !value); setNameText(endpoint.name); setDescriptionText(endpoint.description ?? ''); }}>{editingDetails ? 'Cancel edit' : 'Edit details'}</Button>}{canEdit && endpoint.status !== 'published' && <Button size="sm" disabled={lifecycle.isPending || !readiness.data?.readiness.compatible} onClick={() => lifecycle.mutate('publish')}>Publish</Button>}{canEdit && endpoint.status === 'published' && <Button size="sm" variant="outline" disabled={lifecycle.isPending} onClick={() => lifecycle.mutate('disable')}>Disable new calls</Button>}{canEdit && endpoint.status !== 'archived' && <Button size="sm" variant="destructive" disabled={lifecycle.isPending} onClick={() => { if (window.confirm('Archive this Endpoint? New and existing public access will stop.')) lifecycle.mutate('archive'); }}>Archive</Button>}</div>}
      />
      {error && <p className="text-sm text-red-600">{error}</p>}
      {editingDetails && <Card><CardHeader><CardTitle>Edit API details</CardTitle><CardDescription>The public slug remains stable; name and description can change without creating a release.</CardDescription></CardHeader><CardContent><form className="grid gap-3" onSubmit={event => { event.preventDefault(); updateDetails.mutate(); }}><label className="grid gap-1 text-sm">Name<Input value={nameText} onChange={event => setNameText(event.target.value)} required /></label><label className="grid gap-1 text-sm">Description<textarea className="min-h-24 rounded-md border bg-background p-3" value={descriptionText} onChange={event => setDescriptionText(event.target.value)} /></label><div><Button disabled={updateDetails.isPending || !nameText.trim()}>{updateDetails.isPending ? 'Saving…' : 'Save details'}</Button></div></form></CardContent></Card>}

      <div className="flex flex-wrap items-center gap-2 rounded-xl border bg-white p-4">
        <Badge tone={lifecycleTone(endpoint.status)}>{endpoint.status}</Badge>
        <Badge tone={readinessTone(readiness.data?.readiness.state)}>{readiness.data?.readiness.state ?? 'checking'}</Badge>
        <Badge tone="info">{endpoint.invocationMode}</Badge><Badge>{endpoint.targetType}</Badge>
        <code className="ml-auto max-w-full truncate text-xs text-muted-foreground">{invokePath}</code>
        <Button size="sm" variant="ghost" onClick={() => void navigator.clipboard.writeText(invokePath)}><Copy className="h-4 w-4" /></Button>
      </div>

      <div className="flex gap-1 overflow-x-auto border-b">
        {tabs.map(item => <button key={item.id} className={`whitespace-nowrap border-b-2 px-4 py-3 text-sm ${tab === item.id ? 'border-primary font-medium text-primary' : 'border-transparent text-muted-foreground'}`} onClick={() => setParams(current => { const next = new URLSearchParams(current); next.set('tab', item.id); return next; })}>{item.label}</button>)}
      </div>

      {tab === 'overview' && <div className="grid gap-4 md:grid-cols-3">
        <Card><CardHeader><CardTitle>Publication</CardTitle><CardDescription>Lifecycle is independent from target readiness.</CardDescription></CardHeader><CardContent className="space-y-2 text-sm"><div>Status <Badge className="ml-2" tone={lifecycleTone(endpoint.status)}>{endpoint.status}</Badge></div><div>Auth <span className="font-medium">{endpoint.authPolicy?.type ?? 'api_key'}</span></div><div>Active release <span className="font-medium">{endpoint.activeRelease ? `r${endpoint.activeRelease}` : 'not deployed'}</span></div><div>Config version <span className="font-medium">{endpoint.version}</span></div></CardContent></Card>
        <Card><CardHeader><CardTitle>Target readiness</CardTitle><CardDescription>Evaluated without dispatching a request.</CardDescription></CardHeader><CardContent className="space-y-2 text-sm"><Badge tone={readinessTone(readiness.data?.readiness.state)}>{readiness.data?.readiness.state ?? 'checking'}</Badge><p className="text-muted-foreground">{readiness.data?.readiness.reason ?? 'Loading target eligibility…'}</p></CardContent></Card>
        <Card><CardHeader><CardTitle>Recent traffic</CardTitle><CardDescription>Durable public Invocation identities.</CardDescription></CardHeader><CardContent className="space-y-2 text-sm"><div className="text-3xl font-semibold">{invocations.data?.items.length ?? '—'}</div><p className="text-muted-foreground">Most recent retained invocations</p></CardContent></Card>
      </div>}

      {tab === 'contract' && <EndpointUsage endpoint={endpoint} />}
      {tab === 'contract' && <div className="grid gap-4 md:grid-cols-2">
        <Card><CardHeader><div className="flex items-start justify-between gap-3"><div><CardTitle>Invocation contract</CardTitle><CardDescription>Target type and mode stay immutable; Workflow revisions are deployed as releases.</CardDescription></div>{canEdit && <Button size="sm" variant="outline" onClick={() => { setEditingContract(value => !value); setInputSchemaText(endpoint.inputSchema ? JSON.stringify(endpoint.inputSchema, null, 2) : ''); setOutputSchemaText(endpoint.outputSchema ? JSON.stringify(endpoint.outputSchema, null, 2) : ''); setTimeoutText(String(endpoint.timeoutSeconds)); setMaxPayloadText(String(endpoint.maxPayloadBytes)); setRateRequestsText(String(endpoint.rateLimit?.requests ?? 60)); setRateWindowText(String(endpoint.rateLimit?.windowSeconds ?? 60)); }}>{editingContract ? 'Cancel' : 'Edit'}</Button>}</div></CardHeader><CardContent className="space-y-3 text-sm"><div><span className="text-muted-foreground">Mode</span><p>{endpoint.invocationMode}</p></div><div><span className="text-muted-foreground">Public path</span><p className="break-all font-mono text-xs">{invokePath}</p></div><div><span className="text-muted-foreground">Timeout</span><p>{endpoint.timeoutSeconds}s</p></div><div><span className="text-muted-foreground">Maximum payload</span><p>{endpoint.maxPayloadBytes.toLocaleString()} bytes</p></div><div><span className="text-muted-foreground">Rate limit</span><p>{endpoint.rateLimit?.requests ?? 'unlimited'}{endpoint.rateLimit?.requests ? ` requests / ${endpoint.rateLimit.windowSeconds ?? 60}s / principal` : ''}</p></div><div><span className="text-muted-foreground">Event schema</span><p>{endpoint.eventSchemaVersion}</p></div></CardContent></Card>
        <Card><CardHeader><CardTitle>JSON schemas</CardTitle><CardDescription>Missing schemas mean the target accepts its native input contract.</CardDescription></CardHeader><CardContent className="space-y-4"><div><div className="mb-1 text-sm text-muted-foreground">Input</div><pre className="max-h-64 overflow-auto rounded-lg bg-slate-950 p-3 text-xs text-slate-100">{JSON.stringify(endpoint.inputSchema ?? null, null, 2)}</pre></div><div><div className="mb-1 text-sm text-muted-foreground">Output</div><pre className="max-h-64 overflow-auto rounded-lg bg-slate-950 p-3 text-xs text-slate-100">{JSON.stringify(endpoint.outputSchema ?? null, null, 2)}</pre></div></CardContent></Card>
        {editingContract && <Card className="md:col-span-2"><CardHeader><CardTitle>Edit contract controls</CardTitle><CardDescription>{endpoint.status === 'published' ? 'Disable the Endpoint before changing JSON schemas. Timeout, payload, and rate limits remain operational controls.' : 'Schema changes take effect when this Endpoint is published again.'}</CardDescription></CardHeader><CardContent><form className="grid gap-4 md:grid-cols-2" onSubmit={event => { event.preventDefault(); updateContract.mutate(); }}><label className="grid gap-1 text-sm">Timeout seconds<Input type="number" min="1" value={timeoutText} onChange={event => setTimeoutText(event.target.value)} /></label><label className="grid gap-1 text-sm">Maximum payload bytes<Input type="number" min="1" value={maxPayloadText} onChange={event => setMaxPayloadText(event.target.value)} /></label><label className="grid gap-1 text-sm">Requests per window<Input type="number" min="1" value={rateRequestsText} onChange={event => setRateRequestsText(event.target.value)} /></label><label className="grid gap-1 text-sm">Window seconds<Input type="number" min="1" value={rateWindowText} onChange={event => setRateWindowText(event.target.value)} /></label><label className="grid gap-1 text-sm">Input JSON Schema<textarea className="min-h-48 rounded-lg border p-3 font-mono text-xs disabled:bg-muted" disabled={endpoint.status === 'published'} value={inputSchemaText} onChange={event => setInputSchemaText(event.target.value)} /></label><label className="grid gap-1 text-sm">Output JSON Schema<textarea className="min-h-48 rounded-lg border p-3 font-mono text-xs disabled:bg-muted" disabled={endpoint.status === 'published'} value={outputSchemaText} onChange={event => setOutputSchemaText(event.target.value)} /></label><div className="md:col-span-2"><Button disabled={updateContract.isPending || !Number(timeoutText) || !Number(maxPayloadText) || !Number(rateRequestsText) || !Number(rateWindowText)}>Save contract controls</Button></div></form></CardContent></Card>}
      </div>}

      {tab === 'security' && <div className="space-y-4">
        {secret && <Card className="border-sky-300 bg-sky-50"><CardHeader><CardTitle>Credential value</CardTitle><CardDescription>The key is encrypted at rest and can be copied again by an authorized Agent developer or administrator.</CardDescription></CardHeader><CardContent className="flex gap-2"><code className="min-w-0 flex-1 break-all rounded bg-white p-3 text-xs">{secret}</code><Button variant="outline" onClick={() => void navigator.clipboard.writeText(secret)}><Copy className="h-4 w-4" />Copy</Button><Button variant="ghost" onClick={() => setSecret('')}>Hide</Button></CardContent></Card>}
        <Card><CardHeader><div className="flex items-start justify-between"><div><CardTitle className="flex items-center gap-2"><ShieldCheck className="h-4 w-4" />Authentication & credentials</CardTitle><CardDescription>Values stay masked by default. Explicit copy decrypts only the selected Endpoint credential.</CardDescription></div>{canEdit && endpoint.authPolicy?.type === 'api_key' && <form className="flex gap-2" onSubmit={event => { event.preventDefault(); if (credentialName.trim()) credentialAction.mutate({ type: 'create', name: credentialName.trim() }); }}><Input value={credentialName} onChange={event => setCredentialName(event.target.value)} placeholder="Credential name" /><Button size="sm" disabled={!credentialName.trim()}>Create</Button></form>}</div></CardHeader><CardContent className="space-y-3">{(credentials.data?.items ?? []).map(credential => <div key={credential.id} className="flex flex-wrap items-center gap-3 rounded-lg border p-3 text-sm"><KeyRound className="h-4 w-4 text-muted-foreground" /><div className="min-w-36 flex-1"><div className="font-medium">{credential.name}</div><div className="font-mono text-xs text-muted-foreground">asep_{credential.keyPrefix}… · created {new Date(credential.createdAt).toLocaleString()}</div></div><Badge tone={credential.status === 'active' ? 'success' : 'default'}>{credential.status}</Badge>{credential.lastUsedAt && <span className="text-xs text-muted-foreground">used {new Date(credential.lastUsedAt).toLocaleString()}</span>}{canEdit && credential.status === 'active' && <><Button size="sm" variant="outline" disabled={credentialAction.isPending} onClick={() => credentialAction.mutate({ type: 'reveal', id: credential.id })}><Copy className="h-3 w-3" />Copy key</Button><Button size="sm" variant="outline" onClick={() => credentialAction.mutate({ type: 'rotate', id: credential.id })}>Rotate</Button><Button size="sm" variant="destructive" onClick={() => { if (window.confirm(`Revoke ${credential.name}? Existing callers using it will be rejected.`)) credentialAction.mutate({ type: 'revoke', id: credential.id }); }}>Revoke</Button></>}</div>)}{!credentials.isLoading && !(credentials.data?.items.length) && <p className="text-sm text-muted-foreground">No API-key credentials. This Endpoint may use platform authentication.</p>}</CardContent></Card>
      </div>}

      {tab === 'playground' && <InvocationPlayground endpoint={endpoint} onInvoked={() => void queryClient.invalidateQueries({ queryKey: ['endpoint-invocations', endpointId] })} />}

      {tab === 'invocations' && <Card><CardHeader><CardTitle>Invocations</CardTitle><CardDescription>Public identities are distinct from internal Issues, Runs, Tasks, Attempts, and Sessions.</CardDescription></CardHeader><CardContent className="space-y-3">{(invocations.data?.items ?? []).map(invocation => <div key={invocation.id} className="grid gap-2 rounded-lg border p-3 text-sm md:grid-cols-[1fr_auto_auto]"><div><div className="font-mono text-xs">{invocation.id}</div><div className="mt-1 text-xs text-muted-foreground">{new Date(invocation.createdAt).toLocaleString()} · correlation {invocation.correlationId}</div>{invocation.errorMessage && <div className="mt-1 text-xs text-red-600">{invocation.errorMessage}</div>}</div><Badge tone={invocation.status === 'completed' ? 'success' : invocation.status === 'failed' || invocation.status === 'timed_out' ? 'danger' : invocation.status === 'cancelled' ? 'warning' : 'info'}>{invocation.status}</Badge><div className="flex gap-2">{invocation.issueId && <Button asChild size="sm" variant="outline"><Link to={scope.scopedPath(`/work/issues/${invocation.issueId}`)}>Issue<ExternalLink className="h-3 w-3" /></Link></Button>}{invocation.runId && <Button asChild size="sm" variant="outline"><Link to={scope.scopedPath(`/work/executions/${invocation.runId}`)}>Execution<ExternalLink className="h-3 w-3" /></Link></Button>}{invocation.sessionId && <Button asChild size="sm" variant="outline"><Link to={scope.scopedPath(`/work/sessions/${invocation.sessionId}`)}>Session<ExternalLink className="h-3 w-3" /></Link></Button>}</div></div>)}{!invocations.isLoading && !(invocations.data?.items.length) && <p className="text-sm text-muted-foreground">No invocations yet. Use Test API or call the public API.</p>}</CardContent></Card>}

      {tab === 'releases' && <Card><CardHeader><CardTitle className="flex items-center gap-2"><History className="h-4 w-4" />Release history</CardTitle><CardDescription>Every deployment and rollback appends an immutable release while the public URL and credentials stay stable.</CardDescription></CardHeader><CardContent className="space-y-3">{(releases.data?.items ?? []).map(release => <div key={release.id} className="flex flex-wrap items-center gap-3 rounded-lg border p-3 text-sm"><div className="min-w-48 flex-1"><div className="flex items-center gap-2"><strong>Release {release.number}</strong>{endpoint.activeReleaseId === release.id && <Badge tone="success">active</Badge>}</div><div className="mt-1 text-xs"><EntityIdentityText identities={identities} type={release.targetType} entityRef={release.targetRef} secondary /></div><div className="mt-1 text-xs text-muted-foreground">{release.reason || 'deployment'} · {new Date(release.activatedAt).toLocaleString()}</div></div>{canEdit && endpoint.activeReleaseId !== release.id && <Button size="sm" variant="outline" disabled={rollback.isPending} onClick={() => { if (window.confirm(`Roll back by creating a new release from release ${release.number}?`)) rollback.mutate(release.id); }}>Roll back to this</Button>}</div>)}{!releases.isLoading && !(releases.data?.items.length) && <p className="text-sm text-muted-foreground">No release yet. Publishing this Endpoint creates release 1.</p>}</CardContent></Card>}

      {tab === 'target' && <Card><CardHeader><CardTitle>Active target</CardTitle><CardDescription>Target type and invocation mode are part of the stable contract. Workflow revision changes are explicit Endpoint releases.</CardDescription></CardHeader><CardContent className="space-y-3 text-sm"><div><span className="text-muted-foreground">Type</span><p>{endpoint.targetType}</p></div><div><span className="text-muted-foreground">Active target</span><p><EntityIdentityText identities={identities} type={endpoint.targetType} entityRef={endpoint.targetRef} secondary /></p></div><div><span className="text-muted-foreground">Release</span><p>{endpoint.activeRelease ? `r${endpoint.activeRelease}` : 'not deployed'}</p></div><Button asChild variant="outline"><Link to={scope.scopedPath(ownerPath)}>Open {ownerLabel}<ExternalLink className="h-4 w-4" /></Link></Button></CardContent></Card>}
    </Page>
  );
}
