/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { useState, type FormEvent, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeft, CheckCircle2, Cpu, Plus, Server, Wifi, WifiOff } from 'lucide-react';
import {
  getRuntimeHost,
  listRuntimeHosts,
  listRuntimePools,
  listRuntimeProfiles,
  setRuntimeHostDraining,
  upsertRuntimePool,
  upsertRuntimeProfile,
  type RuntimeHost,
} from '@/api/runtimeControl';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input, Textarea } from '@/components/ui/input';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { formatRelative } from '@/lib/format';

function tone(value: string): 'default' | 'success' | 'warning' | 'danger' | 'info' {
  if (['online', 'healthy'].includes(value)) return 'success';
  if (['draining', 'pending'].includes(value)) return 'warning';
  if (['offline', 'quarantined', 'unhealthy'].includes(value)) return 'danger';
  return 'default';
}

export function RuntimeHostsPage() {
  const scope = useControlPlaneScope();
  const [params, setParams] = useSearchParams();
  const state = params.get('state') || '';
  const pool = params.get('pool') || '';
  const hosts = useQuery({
    queryKey: ['runtime-hosts', scope.tenant, scope.namespace, pool, state],
    queryFn: () => listRuntimeHosts(scope.tenant, scope.namespace, pool, state),
    refetchInterval: 7_500,
  });
  const pools = useQuery({ queryKey: ['runtime-pools', scope.tenant, scope.namespace], queryFn: () => listRuntimePools(scope.tenant, scope.namespace) });
  const items = hosts.data?.items || [];
  const capacity = items.reduce((sum, host) => sum + host.capacity, 0);
  const active = items.reduce((sum, host) => sum + host.active, 0);

  function setFilter(key: string, value: string) {
    const next = new URLSearchParams(params); if (value) next.set(key, value); else next.delete(key); setParams(next, { replace: true });
  }

  return (
    <Page className="max-w-[1440px]">
      <PageHeader title="Runtime hosts" description="User-operated execution nodes for Codex, Claude Code, and other task-scoped coding runtimes." actions={<Button variant="outline" asChild><Link to={scope.scopedPath('/runtime/profiles')}>Manage profiles</Link></Button>} />
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Metric label="Hosts" value={items.length} detail={`${items.filter((host) => host.state === 'online').length} online`} />
        <Metric label="Capacity" value={capacity} detail={`${active} active executions`} />
        <Metric label="Available slots" value={Math.max(0, capacity - active)} detail="Across the selected pool" />
        <Metric label="Needs attention" value={items.filter((host) => !['online', 'draining'].includes(host.state)).length} detail="Offline or quarantined" />
      </div>
      <div className="flex flex-wrap items-end gap-3">
        <label className="grid gap-1.5 text-sm"><span className="text-muted-foreground">State</span><select className="h-10 min-w-44 rounded-lg border border-border bg-white px-3 text-sm" value={state} onChange={(event) => setFilter('state', event.target.value)}><option value="">All states</option>{['online', 'draining', 'offline', 'quarantined'].map((item) => <option key={item}>{item}</option>)}</select></label>
        <label className="grid gap-1.5 text-sm"><span className="text-muted-foreground">Pool</span><select className="h-10 min-w-52 rounded-lg border border-border bg-white px-3 text-sm" value={pool} onChange={(event) => setFilter('pool', event.target.value)}><option value="">All pools</option>{(pools.data?.items || []).map((item) => <option key={item.id} value={item.name}>{item.name}</option>)}</select></label>
      </div>
      {!hosts.isLoading && items.length === 0 ? <EmptyState title="No Runtime Hosts" description="Install and start aistio-runtime-host on a user-controlled machine or Kubernetes node." /> : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">{items.map((host) => <HostCard key={host.id} host={host} />)}</div>
      )}
    </Page>
  );
}

function HostCard({ host }: { host: RuntimeHost }) {
  const scope = useControlPlaneScope();
  const usage = host.capacity > 0 ? Math.round((host.active / host.capacity) * 100) : 0;
  const OnlineIcon = host.state === 'online' ? Wifi : WifiOff;
  return (
    <Link to={scope.scopedPath(`/runtime/hosts/${host.id}`)} className="rounded-xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
      <Card className="h-full transition hover:border-indigo-200 hover:shadow-md">
        <CardHeader><div className="flex items-start justify-between gap-3"><div className="flex min-w-0 items-center gap-3"><div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-muted"><Server className="h-5 w-5" /></div><div className="min-w-0"><CardTitle className="truncate text-base">{host.hostKey}</CardTitle><CardDescription>{host.os || 'unknown'}/{host.arch || 'unknown'} · {host.daemonVersion || 'unknown version'}</CardDescription></div></div><Badge tone={tone(host.state)}><OnlineIcon className="mr-1 h-3 w-3" />{host.state}</Badge></div></CardHeader>
        <CardContent><div className="flex items-center justify-between text-sm"><span>{host.poolName}</span><span className="font-mono">{host.active}/{host.capacity}</span></div><div className="mt-2 h-2 overflow-hidden rounded-full bg-slate-100"><div className="h-full rounded-full bg-indigo-500" style={{ width: `${Math.min(100, usage)}%` }} /></div><div className="mt-3 text-xs text-muted-foreground">Heartbeat {formatRelative(host.lastSeenAt)}</div></CardContent>
      </Card>
    </Link>
  );
}

export function RuntimeHostDetailPage() {
  const { hostId = '' } = useParams();
  const scope = useControlPlaneScope();
  const queryClient = useQueryClient();
  const detail = useQuery({ queryKey: ['runtime-host', hostId], queryFn: () => getRuntimeHost(hostId), enabled: !!hostId, refetchInterval: 5_000 });
  const changeState = useMutation({
    mutationFn: (draining: boolean) => setRuntimeHostDraining(hostId, draining),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['runtime-host', hostId] });
      void queryClient.invalidateQueries({ queryKey: ['runtime-hosts'] });
    },
  });
  if (detail.isError) return <Page><EmptyState title="Runtime Host unavailable" description="The host was not found or the registry is unavailable." /></Page>;
  if (!detail.data) return <Page><p className="text-sm text-muted-foreground">Loading Runtime Host…</p></Page>;
  const host = detail.data.host;
  return (
    <Page className="max-w-[1200px]">
      <Link to={scope.scopedPath('/runtime/hosts')} className="inline-flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground"><ArrowLeft className="h-4 w-4" />Back to Runtime Hosts</Link>
      <PageHeader
        title={<span className="flex flex-wrap items-center gap-3">{host.hostKey}<Badge tone={tone(host.state)}>{host.state}</Badge></span>}
        description="A Runtime Host owns local provider processes and workspaces; it is not an application deployment controller."
        actions={host.state === 'draining'
          ? <Button disabled={changeState.isPending} onClick={() => changeState.mutate(false)}>Resume scheduling</Button>
          : <Button variant="outline" disabled={changeState.isPending || host.state === 'offline'} onClick={() => changeState.mutate(true)}>Drain host</Button>}
      />
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4"><Metric label="Active executions" value={host.active} detail={`${host.capacity} total capacity`} /><Metric label="Available slots" value={Math.max(0, host.capacity - host.active)} detail={`Pool ${host.poolName}`} /><Fact icon={Cpu} label="Machine" value={`${host.os || 'unknown'} / ${host.arch || 'unknown'}`} /><Fact icon={CheckCircle2} label="Heartbeat" value={formatRelative(host.lastSeenAt)} /></div>
      <div className="grid gap-6 lg:grid-cols-2">
        <JsonCard title="Advertised capabilities" description="Providers and runtime capabilities reported by the daemon." value={host.capabilities} />
        <JsonCard title="Host labels" description="Scheduling and ownership metadata." value={host.labels} />
      </div>
      <Card><CardHeader><CardTitle>Host identity</CardTitle><CardDescription>Use the immutable ID when correlating ExecutionAttempt records.</CardDescription></CardHeader><CardContent className="grid gap-4 text-sm sm:grid-cols-2"><Definition label="Host ID" value={host.id} /><Definition label="Host key" value={host.hostKey} /><Definition label="Lease generation" value={String(host.leaseGeneration || '—')} /><Definition label="Daemon version" value={host.daemonVersion || '—'} /></CardContent></Card>
    </Page>
  );
}

export function RuntimeProfilesPage() {
  const scope = useControlPlaneScope(); const qc = useQueryClient(); const [showForm, setShowForm] = useState(false); const [name, setName] = useState(''); const [provider, setProvider] = useState('codex'); const [runtime, setRuntime] = useState('codex'); const [requirements, setRequirements] = useState('{}'); const [error, setError] = useState('');
  const profiles = useQuery({ queryKey: ['runtime-profiles', scope.tenant, scope.namespace], queryFn: () => listRuntimeProfiles(scope.tenant, scope.namespace) });
  const save = useMutation({ mutationFn: upsertRuntimeProfile, onSuccess: () => { setShowForm(false); setName(''); setError(''); void qc.invalidateQueries({ queryKey: ['runtime-profiles'] }); }, onError: (cause) => setError(cause instanceof Error ? cause.message : 'Save failed') });
  function submit(event: FormEvent) { event.preventDefault(); try { save.mutate({ tenant: scope.tenant, namespace: scope.namespace, name, provider, runtime, requirements: JSON.parse(requirements) }); } catch { setError('Requirements must be valid JSON.'); } }
  return <Page><PageHeader title="Runtime profiles" description="Reusable provider configuration and capability requirements advertised to Runtime Hosts." actions={<Button onClick={() => setShowForm((open) => !open)}><Plus className="h-4 w-4" />New profile</Button>} />{showForm && <ResourceForm title="Create or update profile" onSubmit={submit} error={error} pending={save.isPending}><label className="grid gap-1 text-sm"><span>Name</span><Input value={name} onChange={(e) => setName(e.target.value)} required /></label><label className="grid gap-1 text-sm"><span>Provider</span><select className="h-10 rounded-lg border border-border bg-white px-3" value={provider} onChange={(e) => setProvider(e.target.value)}><option value="codex">Codex</option><option value="claude-code">Claude Code</option></select></label><label className="grid gap-1 text-sm"><span>Runtime command</span><Input value={runtime} onChange={(e) => setRuntime(e.target.value)} /></label><label className="grid gap-1 text-sm lg:col-span-2"><span>Requirements JSON</span><Textarea className="font-mono text-xs" value={requirements} onChange={(e) => setRequirements(e.target.value)} /></label></ResourceForm>}<div className="grid gap-4 md:grid-cols-2">{(profiles.data?.items || []).map((profile) => <Card key={profile.id}><CardHeader><div className="flex items-start justify-between"><div><CardTitle>{profile.name}</CardTitle><CardDescription>{profile.provider} · {profile.runtime || 'default runtime'}</CardDescription></div><Badge tone="info">v{profile.version}</Badge></div></CardHeader><CardContent><JsonPreview value={profile.requirements} empty="No capability requirements" /></CardContent></Card>)}</div>{!profiles.isLoading && !profiles.data?.items.length && <EmptyState title="No Runtime Profiles" description="Create a provider profile before submitting a hosted coding task." />}</Page>;
}

export function RuntimePoolsPage() {
  const scope = useControlPlaneScope(); const qc = useQueryClient(); const [showForm, setShowForm] = useState(false); const [name, setName] = useState(''); const [selector, setSelector] = useState('{}'); const [error, setError] = useState('');
  const pools = useQuery({ queryKey: ['runtime-pools', scope.tenant, scope.namespace], queryFn: () => listRuntimePools(scope.tenant, scope.namespace) });
  const hosts = useQuery({ queryKey: ['runtime-hosts', scope.tenant, scope.namespace], queryFn: () => listRuntimeHosts(scope.tenant, scope.namespace) });
  const save = useMutation({ mutationFn: upsertRuntimePool, onSuccess: () => { setShowForm(false); setName(''); setError(''); void qc.invalidateQueries({ queryKey: ['runtime-pools'] }); }, onError: (cause) => setError(cause instanceof Error ? cause.message : 'Save failed') });
  function submit(event: FormEvent) { event.preventDefault(); try { save.mutate({ tenant: scope.tenant, namespace: scope.namespace, name, hostSelector: JSON.parse(selector) }); } catch { setError('Host selector must be valid JSON.'); } }
  return <Page><PageHeader title="Runtime pools" description="Scheduling groups that isolate compatible Runtime Hosts by tenant, environment, capability, or policy." actions={<Button onClick={() => setShowForm((open) => !open)}><Plus className="h-4 w-4" />New pool</Button>} />{showForm && <ResourceForm title="Create or update pool" onSubmit={submit} error={error} pending={save.isPending}><label className="grid gap-1 text-sm"><span>Name</span><Input value={name} onChange={(e) => setName(e.target.value)} required /></label><label className="grid gap-1 text-sm lg:col-span-2"><span>Host selector JSON</span><Textarea className="font-mono text-xs" value={selector} onChange={(e) => setSelector(e.target.value)} /></label></ResourceForm>}<div className="grid gap-4 md:grid-cols-2">{(pools.data?.items || []).map((pool) => { const members = (hosts.data?.items || []).filter((host) => host.poolName === pool.name); return <Card key={pool.id}><CardHeader><div className="flex items-start justify-between"><div><CardTitle>{pool.name}</CardTitle><CardDescription>{members.length} hosts · {members.reduce((sum, host) => sum + host.capacity, 0)} execution slots</CardDescription></div><Badge tone="info">v{pool.version}</Badge></div></CardHeader><CardContent><JsonPreview value={pool.hostSelector} empty="Matches hosts assigned to this pool" /></CardContent></Card>; })}</div>{!pools.isLoading && !pools.data?.items.length && <EmptyState title="No Runtime Pools" description="Create a pool to group user-operated Runtime Hosts for scheduling." />}</Page>;
}

function Metric({ label, value, detail }: { label: string; value: number; detail: string }) { return <Card><CardHeader className="pb-3"><CardDescription>{label}</CardDescription><CardTitle className="font-mono text-3xl">{value}</CardTitle></CardHeader><CardContent className="text-xs text-muted-foreground">{detail}</CardContent></Card>; }
function Fact({ icon: Icon, label, value }: { icon: typeof Cpu; label: string; value: string }) { return <Card><CardContent className="flex items-center gap-3 p-5"><Icon className="h-5 w-5 text-muted-foreground" /><div><div className="text-xs text-muted-foreground">{label}</div><div className="mt-1 text-sm font-medium">{value}</div></div></CardContent></Card>; }
function Definition({ label, value }: { label: string; value: string }) { return <div><div className="text-xs text-muted-foreground">{label}</div><div className="mt-1 break-all font-mono text-xs">{value}</div></div>; }
function JsonPreview({ value, empty }: { value: unknown; empty: string }) { return value == null || JSON.stringify(value) === '{}' ? <p className="text-sm text-muted-foreground">{empty}</p> : <pre className="max-h-40 overflow-auto rounded-lg bg-slate-50 p-3 text-xs">{JSON.stringify(value, null, 2)}</pre>; }
function JsonCard({ title, description, value }: { title: string; description: string; value: unknown }) { return <Card><CardHeader><CardTitle>{title}</CardTitle><CardDescription>{description}</CardDescription></CardHeader><CardContent><JsonPreview value={value} empty="Nothing advertised" /></CardContent></Card>; }
function ResourceForm({ title, children, onSubmit, error, pending }: { title: string; children: ReactNode; onSubmit: (event: FormEvent) => void; error: string; pending: boolean }) { return <form onSubmit={onSubmit} className="rounded-xl border border-indigo-200 bg-white p-5 shadow-sm"><h2 className="mb-4 font-semibold">{title}</h2><div className="grid gap-4 lg:grid-cols-2">{children}</div>{error && <p role="alert" className="mt-4 text-sm text-red-600">{error}</p>}<div className="mt-4"><Button type="submit" disabled={pending}>{pending ? 'Saving…' : 'Save'}</Button></div></form>; }
