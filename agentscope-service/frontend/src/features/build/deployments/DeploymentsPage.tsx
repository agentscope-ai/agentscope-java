import { FormEvent, useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowRight, CheckCircle2, CircleDashed, MessageSquare, Plus, TerminalSquare, TriangleAlert } from 'lucide-react';
import { Link, useSearchParams } from 'react-router-dom';
import { createEndpoint, Endpoint, listEndpoints } from '@/api/agentEndpoints';
import { getRoles } from '@/api/auth';
import { listTeams } from '@/api/collaboration';
import { listDefinitions, listRevisions } from '@/api/orchestration';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/EmptyState';
import { AgentPicker } from '@/components/AgentPicker';
import { EntityIdentityText, entityDisplayName, useEntityIdentities } from '@/components/EntityIdentity';
import { Input } from '@/components/ui/input';
import { Page, PageHeader } from '@/components/Page';
import { useControlPlaneScope } from '@/app/ScopeContext';

type TargetOption = { type: Endpoint['targetType']; id: string; label: string };

const statusTone = (status: Endpoint['status']) => status === 'published' ? 'success' : status === 'draft' ? 'info' : status === 'disabled' ? 'warning' : 'default';

export default function DeploymentsPage() {
  const queryClient = useQueryClient();
  const scope = useControlPlaneScope();
  const [searchParams] = useSearchParams();
  const targetRefFilter = searchParams.get('targetRef') ?? '';
  const roles = getRoles().map(role => role.toLowerCase());
  const canEdit = roles.includes('admin') || roles.includes('agent_developer');
  const endpoints = useQuery({
    queryKey: ['endpoints', scope.tenant, scope.namespace, targetRefFilter],
    queryFn: () => listEndpoints(scope.tenant, scope.namespace, targetRefFilter ? { targetRef: targetRefFilter } : undefined),
  });
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');
  const [description, setDescription] = useState('');
  const [mode, setMode] = useState<Endpoint['invocationMode']>('job');
  const [targetType, setTargetType] = useState<Endpoint['targetType']>('agent');
  const [targetRef, setTargetRef] = useState(targetRefFilter);
  const [targetOptions, setTargetOptions] = useState<TargetOption[]>([]);
  const [createdCredential, setCreatedCredential] = useState<{ endpoint: Endpoint; secret: string } | null>(null);
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<'all' | Endpoint['status']>('all');
  const [typeFilter, setTypeFilter] = useState<'all' | Endpoint['targetType']>('all');

  useEffect(() => {
    let cancelled = false;
    void Promise.all([
      listTeams(scope.tenant, scope.namespace),
      listDefinitions(scope.tenant, scope.namespace),
    ]).then(async ([teams, definitions]) => {
      const revisions = await Promise.all((definitions.definitions ?? []).map(async definition => ({
        definition,
        revisions: (await listRevisions(definition.id)).revisions,
      })));
      if (cancelled) return;
      setTargetOptions([
        ...(teams.items ?? []).map(team => ({ type: 'team' as const, id: team.id, label: `Team · ${team.name}` })),
        ...revisions.flatMap(({ definition, revisions: values }) => values.map(revision => ({
          type: 'orchestration_revision' as const,
          id: revision.id,
          label: `Workflow · ${definition.name} r${revision.revision}`,
        }))),
      ]);
    }).catch(cause => {
      if (!cancelled) setError(cause instanceof Error ? cause.message : 'Failed to load Endpoint targets');
    });
    return () => { cancelled = true; };
  }, [scope.tenant, scope.namespace]);

  const matchingTargets = useMemo(() => targetOptions.filter(option => option.type === targetType), [targetOptions, targetType]);
  // Older/rebuilt control planes can return `items: null` for an empty
  // namespace. Treat it as an empty collection so the page can render its
  // first-use state instead of failing during React render.
  const endpointItems = useMemo(() => endpoints.data?.items ?? [], [endpoints.data?.items]);
  const identities = useEntityIdentities(endpointItems.map(endpoint => ({ type: endpoint.targetType, ref: endpoint.targetRef })));
  const visibleItems = useMemo(() => endpointItems.filter(endpoint => {
    if (endpoint.status === 'archived') return false;
    if (statusFilter !== 'all' && endpoint.status !== statusFilter) return false;
    if (typeFilter !== 'all' && endpoint.targetType !== typeFilter) return false;
    const needle = search.trim().toLowerCase();
    return !needle || endpoint.name.toLowerCase().includes(needle) || endpoint.slug.toLowerCase().includes(needle) || endpoint.targetRef.toLowerCase().includes(needle) || entityDisplayName(identities, endpoint.targetType, endpoint.targetRef).toLowerCase().includes(needle);
  }), [endpointItems, identities, search, statusFilter, typeFilter]);
  const publishedCount = endpointItems.filter(endpoint => endpoint.status === 'published').length;
  const pausedCount = endpointItems.filter(endpoint => endpoint.status === 'disabled').length;
  const draftCount = endpointItems.filter(endpoint => endpoint.status === 'draft').length;
  const create = useMutation({
    mutationFn: createEndpoint,
    onSuccess: result => {
      if (result.credential) setCreatedCredential({ endpoint: result.endpoint, secret: result.credential });
      setCreating(false);
      setName(''); setSlug(''); setDescription(''); setTargetRef(targetRefFilter);
      void queryClient.invalidateQueries({ queryKey: ['endpoints'] });
    },
    onError: cause => setError(cause instanceof Error ? cause.message : 'Endpoint creation failed'),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    create.mutate({
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
  }

  return (
    <Page>
      <PageHeader
        title="Endpoint Catalog"
        description="Discover, test, secure, and operate the APIs published from Agents, Teams, and Workflows. Prefer publishing from the target's own page."
        actions={canEdit ? <Button variant="outline" onClick={() => setCreating(value => !value)}><Plus className="h-4 w-4" />Advanced create</Button> : undefined}
      />
      {error && <p className="text-sm text-red-600">{error}</p>}

      <div className="grid gap-4 md:grid-cols-3">
        <Card><CardHeader><CardTitle className="flex items-center gap-2"><CheckCircle2 className="h-4 w-4 text-emerald-600" />{publishedCount} published</CardTitle><CardDescription>Accepting new calls through the public Gateway.</CardDescription></CardHeader></Card>
        <Card><CardHeader><CardTitle className="flex items-center gap-2"><TriangleAlert className="h-4 w-4 text-amber-600" />{pausedCount} disabled</CardTitle><CardDescription>Stable contracts retained, but new calls are paused.</CardDescription></CardHeader></Card>
        <Card><CardHeader><CardTitle className="flex items-center gap-2"><CircleDashed className="h-4 w-4 text-sky-600" />{draftCount} drafts</CardTitle><CardDescription>Need readiness review and publication before use.</CardDescription></CardHeader></Card>
      </div>

      {createdCredential && (
        <Card className="border-amber-300 bg-amber-50">
          <CardHeader><CardTitle>Credential created</CardTitle><CardDescription>{createdCredential.endpoint.name} is still a draft. You can copy this key again later from Endpoint Security.</CardDescription></CardHeader>
          <CardContent className="flex flex-col gap-3 md:flex-row"><code className="min-w-0 flex-1 break-all rounded border bg-white p-3 text-xs">{createdCredential.secret}</code><Button variant="outline" onClick={() => void navigator.clipboard.writeText(createdCredential.secret)}>Copy</Button><Button variant="ghost" onClick={() => setCreatedCredential(null)}>Dismiss</Button></CardContent>
        </Card>
      )}

      {creating && (
        <Card>
          <CardHeader><CardTitle>Advanced Endpoint creation</CardTitle><CardDescription>Use this for Agent APIs or administration outside a target page. Target type and invocation mode form the stable contract.</CardDescription></CardHeader>
          <CardContent>
            <form className="grid gap-4 md:grid-cols-2" onSubmit={submit}>
              <label className="grid gap-1 text-sm">Name<Input required value={name} onChange={event => setName(event.target.value)} /></label>
              <label className="grid gap-1 text-sm">Slug<Input required value={slug} onChange={event => setSlug(event.target.value.toLowerCase().replace(/[^a-z0-9-]/g, '-'))} placeholder="customer-support" /></label>
              <label className="grid gap-1 text-sm">Mode<select className="h-10 rounded-lg border px-3" value={mode} onChange={event => { const next = event.target.value as Endpoint['invocationMode']; setMode(next); if (next === 'conversation') setTargetType('agent'); }}><option value="job">Job</option><option value="conversation">Conversation</option></select></label>
              <label className="grid gap-1 text-sm">Target type<select className="h-10 rounded-lg border px-3" value={targetType} disabled={mode === 'conversation'} onChange={event => { setTargetType(event.target.value as Endpoint['targetType']); setTargetRef(''); }}><option value="agent">Agent</option><option value="team">Team</option><option value="orchestration_revision">Workflow revision</option></select></label>
              <label className="grid gap-1 text-sm md:col-span-2">Target{targetType === 'agent' ? <AgentPicker value={targetRef} onChange={setTargetRef} required aria-label="Endpoint target Agent" /> : <select required className="h-10 rounded-lg border px-3" value={targetRef} onChange={event => setTargetRef(event.target.value)}><option value="">Select target…</option>{matchingTargets.map(option => <option key={option.id} value={option.id}>{option.label}</option>)}</select>}</label>
              <label className="grid gap-1 text-sm md:col-span-2">Description<Input value={description} onChange={event => setDescription(event.target.value)} /></label>
              <div className="flex gap-2 md:col-span-2"><Button type="submit" disabled={create.isPending || !name.trim() || !slug.trim() || !targetRef}>Create draft</Button><Button type="button" variant="outline" onClick={() => setCreating(false)}>Cancel</Button></div>
            </form>
          </CardContent>
        </Card>
      )}

      <Card>
        <CardContent className="grid gap-3 pt-6 md:grid-cols-[1fr_12rem_12rem]">
          <Input value={search} onChange={event => setSearch(event.target.value)} placeholder="Search name, slug, or target…" aria-label="Search Endpoints" />
          <select className="h-10 rounded-md border bg-background px-3 text-sm" value={typeFilter} onChange={event => setTypeFilter(event.target.value as typeof typeFilter)}><option value="all">All target types</option><option value="agent">Agent</option><option value="team">Team</option><option value="orchestration_revision">Workflow</option></select>
          <select className="h-10 rounded-md border bg-background px-3 text-sm" value={statusFilter} onChange={event => setStatusFilter(event.target.value as typeof statusFilter)}><option value="all">All statuses</option><option value="published">Published</option><option value="disabled">Disabled</option><option value="draft">Draft</option></select>
        </CardContent>
      </Card>

      {endpoints.isLoading ? <p className="text-sm text-muted-foreground">Loading…</p> : endpointItems.length === 0 ? (
        <EmptyState title="No endpoints" description="Create a governed API contract when a capability needs a stable external address." />
      ) : visibleItems.length === 0 ? (
        <EmptyState title="No matching endpoints" description="Clear or change the catalog filters." />
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          {visibleItems.map(endpoint => (
            <Card key={endpoint.id}>
              <CardHeader>
                <div className="flex items-start justify-between gap-3">
                  <div><CardTitle>{endpoint.name}</CardTitle><CardDescription className="mt-1 font-mono">/invoke/v1/endpoints/{endpoint.slug}/{endpoint.invocationMode === 'job' ? 'jobs' : 'conversations'}</CardDescription></div>
                  <Badge tone={statusTone(endpoint.status)}>{endpoint.status}</Badge>
                </div>
              </CardHeader>
              <CardContent className="grid gap-4 text-sm">
                <div className="flex flex-wrap gap-2"><Badge tone="info">{endpoint.invocationMode === 'conversation' ? <><MessageSquare className="mr-1 h-3 w-3" />conversation</> : <><TerminalSquare className="mr-1 h-3 w-3" />job</>}</Badge><Badge>{endpoint.targetType}</Badge>{endpoint.activeRelease ? <Badge>release {endpoint.activeRelease}</Badge> : null}<EntityIdentityText identities={identities} type={endpoint.targetType} entityRef={endpoint.targetRef} secondary className="text-xs" /></div>
                <div className="flex items-center justify-between gap-3"><span className="text-xs text-muted-foreground">Updated {new Date(endpoint.updatedAt).toLocaleString()}</span><div className="flex gap-2">{endpoint.status === 'published' && <Button asChild size="sm" variant="ghost"><Link to={scope.scopedPath(`/agent-center/endpoints/${endpoint.id}?tab=playground`)}>Test</Link></Button>}<Button asChild size="sm" variant="outline"><Link to={scope.scopedPath(`/agent-center/endpoints/${endpoint.id}`)}>Manage<ArrowRight className="h-4 w-4" /></Link></Button></div></div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </Page>
  );
}
