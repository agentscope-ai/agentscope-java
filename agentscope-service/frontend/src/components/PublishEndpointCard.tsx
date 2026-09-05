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
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';

interface PublishEndpointCardProps {
  targetType: Extract<EndpointTargetType, 'team' | 'orchestration_revision'>;
  targetRef: string;
  targetName: string;
  allowDeployToExisting?: boolean;
}

const endpointPath = (endpoint: Endpoint) =>
  `/invoke/v1/endpoints/${endpoint.slug}/${endpoint.invocationMode === 'job' ? 'jobs' : 'conversations'}`;

const slugify = (value: string) => value.toLowerCase().trim()
  .replace(/[^a-z0-9]+/g, '-')
  .replace(/^-+|-+$/g, '')
  .slice(0, 48);

export function PublishEndpointCard({ targetType, targetRef, targetName, allowDeployToExisting = false }: PublishEndpointCardProps) {
  const scope = useControlPlaneScope();
  const queryClient = useQueryClient();
  const roles = getRoles().map(role => role.toLowerCase());
  const canEdit = roles.includes('admin') || roles.includes('agent_developer');
  const [showCreate, setShowCreate] = useState(false);
  const [name, setName] = useState(`${targetName} API`);
  const [slug, setSlug] = useState(`${slugify(targetName) || 'endpoint'}-${targetRef.slice(0, 6)}`);
  const [description, setDescription] = useState('');
  const [deployEndpointId, setDeployEndpointId] = useState('');
  const [secret, setSecret] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    setName(`${targetName} API`);
    setSlug(`${slugify(targetName) || 'endpoint'}-${targetRef.slice(0, 6)}`);
  }, [targetName, targetRef]);

  const current = useQuery({
    queryKey: ['endpoints', scope.tenant, scope.namespace, targetType, targetRef],
    queryFn: () => listEndpoints(scope.tenant, scope.namespace, { targetType, targetRef }),
    enabled: !!targetRef,
  });
  const compatible = useQuery({
    queryKey: ['endpoints', scope.tenant, scope.namespace, targetType, 'all'],
    queryFn: () => listEndpoints(scope.tenant, scope.namespace, { targetType }),
    enabled: allowDeployToExisting,
  });
  const currentItems = (current.data?.items ?? []).filter(item => item.status !== 'archived');
  const deployCandidates = useMemo(() => (compatible.data?.items ?? []).filter(item =>
    (item.status === 'published' || item.status === 'disabled') && !!item.activeReleaseId && item.targetRef !== targetRef,
  ), [compatible.data?.items, targetRef]);

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['endpoints'] });
  };
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
        invocationMode: 'job',
        authPolicy: { type: 'api_key' },
        rateLimit: { requests: 60, windowSeconds: 60 },
      });
      if (result.credential) setSecret(result.credential);
      return publishEndpoint(result.endpoint);
    },
    onSuccess: () => { setShowCreate(false); setError(''); refresh(); },
    onError: cause => {
      setError(`${cause instanceof Error ? cause.message : 'Publication failed'}. If the draft was created, open Endpoints to resolve readiness and publish it.`);
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
    create.mutate();
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <CardTitle className="flex items-center gap-2"><Rocket className="h-4 w-4" />Publish as API</CardTitle>
            <CardDescription>Expose this {targetType === 'team' ? 'Team' : 'immutable Workflow revision'} as a governed job API. Calls create an operational work record and Run without adding ordinary API traffic to Work Hub.</CardDescription>
          </div>
          {canEdit && !showCreate && <Button size="sm" onClick={() => setShowCreate(true)}>New Endpoint</Button>}
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        {error && <p className="text-sm text-red-600">{error}</p>}
        {secret && <div className="rounded-lg border border-sky-300 bg-sky-50 p-3 text-sm"><strong>API key ready.</strong> You can copy it again later from Endpoint Security.<div className="mt-2 flex gap-2"><code className="min-w-0 flex-1 break-all rounded bg-white p-2 text-xs">{secret}</code><Button size="sm" variant="outline" onClick={() => void navigator.clipboard.writeText(secret)}><Copy className="h-4 w-4" />Copy</Button></div></div>}

        {showCreate && <form className="grid gap-3 md:grid-cols-2" onSubmit={submit}>
          <label className="grid gap-1 text-sm">Name<Input value={name} onChange={event => setName(event.target.value)} required /></label>
          <label className="grid gap-1 text-sm">Slug<Input value={slug} onChange={event => setSlug(slugify(event.target.value))} required /></label>
          <label className="grid gap-1 text-sm md:col-span-2">Description<Input value={description} onChange={event => setDescription(event.target.value)} /></label>
          <div className="flex gap-2 md:col-span-2"><Button disabled={create.isPending || !name.trim() || !slug.trim()}>{create.isPending ? 'Publishing…' : 'Create & publish'}</Button><Button type="button" variant="outline" onClick={() => setShowCreate(false)}>Cancel</Button></div>
        </form>}

        {currentItems.map(endpoint => <div key={endpoint.id} className="rounded-lg border p-3">
          <div className="flex flex-wrap items-center gap-2"><Check className="h-4 w-4 text-emerald-600" /><strong className="text-sm">{endpoint.name}</strong><Badge tone={endpoint.status === 'published' ? 'success' : endpoint.status === 'disabled' ? 'warning' : 'info'}>{endpoint.status}</Badge>{endpoint.activeRelease ? <Badge>release {endpoint.activeRelease}</Badge> : null}</div>
          <code className="mt-2 block break-all text-xs text-muted-foreground">{endpointPath(endpoint)}</code>
          <div className="mt-3 flex flex-wrap gap-2"><Button asChild size="sm" variant="outline"><Link to={scope.scopedPath(`/agent-center/endpoints/${endpoint.id}?tab=playground`)}>Test in Playground<ExternalLink className="h-3 w-3" /></Link></Button><Button size="sm" variant="ghost" onClick={() => void navigator.clipboard.writeText(endpointPath(endpoint))}><Copy className="h-3 w-3" />Copy URL</Button></div>
        </div>)}
        {!current.isLoading && currentItems.length === 0 && !showCreate && <p className="text-sm text-muted-foreground">Not published yet. Create an Endpoint when external systems need a stable API.</p>}

        {canEdit && allowDeployToExisting && deployCandidates.length > 0 && <div className="grid gap-2 border-t pt-4">
          <div className="text-sm font-medium">Deploy this revision to an existing Endpoint</div>
          <div className="flex flex-col gap-2 sm:flex-row"><select className="h-10 min-w-0 flex-1 rounded-md border bg-background px-3 text-sm" value={deployEndpointId} onChange={event => setDeployEndpointId(event.target.value)}><option value="">Choose a stable Endpoint…</option>{deployCandidates.map(endpoint => <option key={endpoint.id} value={endpoint.id}>{endpoint.name} · {endpoint.slug} · r{endpoint.activeRelease || 0}</option>)}</select><Button variant="outline" disabled={!deployEndpointId || deploy.isPending} onClick={() => deploy.mutate()}>{deploy.isPending ? 'Deploying…' : 'Deploy revision'}</Button></div>
          <p className="text-xs text-muted-foreground">The public URL and credentials stay unchanged. A new immutable Endpoint release is recorded.</p>
        </div>}
      </CardContent>
    </Card>
  );
}
