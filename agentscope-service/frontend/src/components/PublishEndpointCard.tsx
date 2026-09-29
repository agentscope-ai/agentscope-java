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

import { EndpointUsage } from './EndpointUsage';
import { type FormEvent, useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, Copy, ExternalLink, Rocket } from 'lucide-react';
import { Link } from 'react-router-dom';
import {
  createEndpoint,
  deployEndpointRelease,
  listEndpoints,
  publishEndpoint,
  type Endpoint,
  type EndpointTargetType,
} from '@/api/agentEndpoints';
import { getRoles } from '@/api/auth';
import { useControlPlaneScope } from '@/app/ScopeContext';
import {
  endpointErrorMessage,
  endpointSlug,
  nextEndpointName,
  nextEndpointSlug,
} from '@/components/endpointNaming';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';

interface PublishEndpointCardProps {
  targetType: EndpointTargetType;
  targetRef: string;
  targetName: string;
  ownerPath: string;
  allowDeployToExisting?: boolean;
  relatedTargetRefs?: string[];
}

const endpointPath = (endpoint: Endpoint) =>
  `/invoke/v1/endpoints/${endpoint.slug}/${endpoint.invocationMode === 'job' ? 'jobs' : 'conversations'}`;

export function PublishEndpointCard({
  targetType,
  targetRef,
  targetName,
  ownerPath,
  allowDeployToExisting = false,
  relatedTargetRefs,
}: PublishEndpointCardProps) {
  const scope = useControlPlaneScope();
  const queryClient = useQueryClient();
  const roles = getRoles().map(role => role.toLowerCase());
  const canEdit = roles.includes('admin') || roles.includes('agent_developer');
  const defaultName = `${targetName} API`;
  const defaultSlug = `${endpointSlug(targetName) || 'endpoint'}-${targetRef.slice(0, 6)}`;
  const [showCreate, setShowCreate] = useState(false);
  const [name, setName] = useState(defaultName);
  const [slug, setSlug] = useState(defaultSlug);
  const [description, setDescription] = useState('');
  const [mode, setMode] = useState<Endpoint['invocationMode']>(targetType === 'agent' ? 'conversation' : 'job');
  const [deployEndpointId, setDeployEndpointId] = useState('');
  const [secret, setSecret] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    setName(defaultName);
    setSlug(defaultSlug);
    setMode(targetType === 'agent' ? 'conversation' : 'job');
  }, [defaultName, defaultSlug, targetType]);

  const endpoints = useQuery({
    queryKey: ['endpoints', scope.tenant, scope.namespace, 'all'],
    queryFn: () => listEndpoints(scope.tenant, scope.namespace),
    enabled: !!targetRef,
  });
  const endpointItems = useMemo(() => endpoints.data?.items ?? [], [endpoints.data?.items]);
  const relatedRefs = useMemo(() => new Set(relatedTargetRefs ?? [targetRef]), [relatedTargetRefs, targetRef]);
  const currentItems = useMemo(() => {
    return endpointItems.filter(item => item.status !== 'archived' && item.targetType === targetType && relatedRefs.has(item.targetRef));
  }, [endpointItems, relatedRefs, targetType]);
  const deployCandidates = useMemo(() => currentItems.filter(item =>
    (item.status === 'published' || item.status === 'disabled') && !!item.activeReleaseId && item.targetRef !== targetRef,
  ), [currentItems, targetRef]);

  const endpointDetailPath = (endpoint: Endpoint, tab?: string) => {
    const query = new URLSearchParams({ returnTo: ownerPath });
    if (tab) query.set('tab', tab);
    return `/agent-center/endpoints/${endpoint.id}?${query}`;
  };

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['endpoints'] });
  };
  const nameTaken = endpointItems.some(item => item.name.trim().toLowerCase() === name.trim().toLowerCase());
  const slugTaken = endpointItems.some(item => item.slug.toLowerCase() === slug.trim().toLowerCase());

  function openCreate() {
    setName(nextEndpointName(defaultName, endpointItems.map(item => item.name)));
    setSlug(nextEndpointSlug(defaultSlug, endpointItems.map(item => item.slug)));
    setDescription('');
    setSecret('');
    setError('');
    setShowCreate(true);
  }

  const create = useMutation({
    mutationFn: async () => {
      const result = await createEndpoint({
        tenant: scope.tenant,
        namespace: scope.namespace,
        name: name.trim(),
        slug: slug.trim(),
        description: description.trim(),
        targetType,
        targetRef,
        invocationMode: mode,
        authPolicy: { type: 'api_key' },
        rateLimit: { requests: 60, windowSeconds: 60 },
      });
      if (result.credential) setSecret(result.credential);
      try {
        return await publishEndpoint(result.endpoint);
      } catch (cause) {
        const publicationError = new Error(
          `Endpoint was created as a draft, but publication failed: ${endpointErrorMessage(cause, 'Publication failed')}`,
        );
        Object.assign(publicationError, { cause });
        throw publicationError;
      }
    },
    onSuccess: () => { setShowCreate(false); setError(''); refresh(); },
    onError: cause => {
      setError(endpointErrorMessage(cause, 'Endpoint creation failed'));
      refresh();
    },
  });
  const deploy = useMutation({
    mutationFn: async () => {
      const endpoint = deployCandidates.find(item => item.id === deployEndpointId);
      if (!endpoint) throw new Error('Choose an Endpoint');
      return deployEndpointRelease(endpoint, targetRef, `deploy ${targetName}`);
    },
    onSuccess: () => { setDeployEndpointId(''); setError(''); refresh(); },
    onError: cause => setError(cause instanceof Error ? cause.message : 'Release deployment failed'),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    if (nameTaken || slugTaken) return;
    create.mutate();
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <CardTitle className="flex items-center gap-2"><Rocket className="h-4 w-4" />Publish as API</CardTitle>
            <CardDescription>Expose this {targetType === 'agent' ? 'Agent' : targetType === 'team' ? 'Team' : 'immutable Workflow revision'} through a governed API owned from this page.</CardDescription>
          </div>
          {canEdit && !showCreate && <Button size="sm" disabled={endpoints.isLoading} onClick={openCreate}>New Endpoint</Button>}
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        {error && <p className="text-sm text-red-600">{error}</p>}
        {secret && <div className="rounded-lg border border-sky-300 bg-sky-50 p-3 text-sm"><strong>API key ready.</strong> You can copy it again later from Endpoint Security.<div className="mt-2 flex gap-2"><code className="min-w-0 flex-1 break-all rounded bg-white p-2 text-xs">{secret}</code><Button size="sm" variant="outline" onClick={() => void navigator.clipboard.writeText(secret)}><Copy className="h-4 w-4" />Copy</Button></div></div>}

        {showCreate && <form className="grid gap-3 md:grid-cols-2" onSubmit={submit}>
          <label className="grid gap-1 text-sm">Name<Input value={name} onChange={event => setName(event.target.value)} required />{nameTaken && <span className="text-xs text-red-600">This Endpoint name is already in use.</span>}</label>
          <label className="grid gap-1 text-sm">Slug<Input value={slug} onChange={event => setSlug(endpointSlug(event.target.value))} required />{slugTaken && <span className="text-xs text-red-600">This public URL slug is already in use.</span>}</label>
          {targetType === 'agent' && <label className="grid gap-1 text-sm">Mode<select className="h-10 rounded-md border bg-background px-3" value={mode} onChange={event => setMode(event.target.value as Endpoint['invocationMode'])}><option value="conversation">Conversation</option><option value="job">Job</option></select></label>}
          <label className="grid gap-1 text-sm md:col-span-2">Description<Input value={description} onChange={event => setDescription(event.target.value)} /></label>
          <div className="flex gap-2 md:col-span-2"><Button disabled={create.isPending || !name.trim() || !slug.trim() || nameTaken || slugTaken}>{create.isPending ? 'Publishing…' : 'Create & publish'}</Button><Button type="button" variant="outline" onClick={() => setShowCreate(false)}>Cancel</Button></div>
        </form>}

        {currentItems.map(endpoint => <div key={endpoint.id} className="rounded-lg border p-3">
          <div className="flex flex-wrap items-center gap-2"><Check className="h-4 w-4 text-emerald-600" /><strong className="text-sm">{endpoint.name}</strong><Badge tone={endpoint.status === 'published' ? 'success' : endpoint.status === 'disabled' ? 'warning' : 'info'}>{endpoint.status}</Badge>{endpoint.activeRelease ? <Badge>release {endpoint.activeRelease}</Badge> : null}</div>
          <EndpointUsage endpoint={endpoint} />
          <code className="mt-2 block break-all text-xs text-muted-foreground">{endpointPath(endpoint)}</code>
          <div className="mt-3 flex flex-wrap gap-2"><Button asChild size="sm" variant="outline"><Link to={scope.scopedPath(endpointDetailPath(endpoint))}>Manage API<ExternalLink className="h-3 w-3" /></Link></Button>{endpoint.status === 'published' && <Button asChild size="sm" variant="outline"><Link to={scope.scopedPath(endpointDetailPath(endpoint, 'playground'))}>Test API<ExternalLink className="h-3 w-3" /></Link></Button>}<Button size="sm" variant="ghost" onClick={() => void navigator.clipboard.writeText(`${window.location.origin}${endpointPath(endpoint)}`)}><Copy className="h-3 w-3" />Copy URL</Button></div>
        </div>)}
        {!endpoints.isLoading && currentItems.length === 0 && !showCreate && <p className="text-sm text-muted-foreground">Not published yet. Create an API when external systems need a stable address.</p>}

        {canEdit && allowDeployToExisting && deployCandidates.length > 0 && <div className="grid gap-2 border-t pt-4">
          <div className="text-sm font-medium">Deploy this revision to an existing Endpoint</div>
          <div className="flex flex-col gap-2 sm:flex-row"><select className="h-10 min-w-0 flex-1 rounded-md border bg-background px-3 text-sm" value={deployEndpointId} onChange={event => setDeployEndpointId(event.target.value)}><option value="">Choose a stable Endpoint…</option>{deployCandidates.map(endpoint => <option key={endpoint.id} value={endpoint.id}>{endpoint.name} · {endpoint.slug} · r{endpoint.activeRelease || 0}</option>)}</select><Button variant="outline" disabled={!deployEndpointId || deploy.isPending} onClick={() => deploy.mutate()}>{deploy.isPending ? 'Deploying…' : 'Deploy revision'}</Button></div>
          <p className="text-xs text-muted-foreground">The public URL and credentials stay unchanged. A new immutable Endpoint release is recorded.</p>
        </div>}
      </CardContent>
    </Card>
  );
}
