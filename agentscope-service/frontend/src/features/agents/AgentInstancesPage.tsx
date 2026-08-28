/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { Boxes, Cpu, Radio, Users } from 'lucide-react';
import { listAgentInstances } from '@/api/runtimeControl';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { formatRelative } from '@/lib/format';

function healthTone(value: string): 'default' | 'success' | 'warning' | 'danger' {
  if (value === 'healthy') return 'success';
  if (value === 'unhealthy') return 'danger';
  return 'warning';
}

export default function AgentInstancesPage() {
  const scope = useControlPlaneScope();
  const [params, setParams] = useSearchParams();
  const health = params.get('health') || '';
  const query = params.get('q') || '';
  const instances = useQuery({
    queryKey: ['agent-instances', scope.tenant, scope.namespace],
    queryFn: () => listAgentInstances(scope.tenant, scope.namespace),
    refetchInterval: 7_500,
  });
  const items = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return (instances.data?.items || []).filter((item) =>
      (!health || item.health === health)
      && (!needle || [item.agentName, item.instanceKey, item.framework, item.backendKind, item.id].filter(Boolean).join(' ').toLowerCase().includes(needle)),
    );
  }, [health, instances.data?.items, query]);
  function setFilter(key: string, value: string) { const next = new URLSearchParams(params); if (value) next.set(key, value); else next.delete(key); setParams(next, { replace: true }); }

  return (
    <Page className="max-w-[1440px]">
      <PageHeader title="Agent instances" description="Observed Managed and External Application processes. Hosted coding processes are represented by ExecutionAttempt instead." />
      <div className="flex flex-wrap items-end gap-3"><label className="grid min-w-64 flex-1 gap-1.5 text-sm"><span className="text-muted-foreground">Search</span><Input value={query} onChange={(e) => setFilter('q', e.target.value)} placeholder="Agent, framework, instance key, or ID" /></label><label className="grid gap-1.5 text-sm"><span className="text-muted-foreground">Health</span><select className="h-10 min-w-44 rounded-lg border border-border bg-white px-3" value={health} onChange={(e) => setFilter('health', e.target.value)}><option value="">All health</option><option value="healthy">healthy</option><option value="unhealthy">unhealthy</option><option value="unknown">unknown</option></select></label></div>
      {!instances.isLoading && items.length === 0 ? <EmptyState title="No Agent instances" description="Connect an application through the Application SDK / ASDP or change the current filters." /> : <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">{items.map((instance) => <Card key={instance.id}><CardHeader><div className="flex items-start justify-between gap-3"><div><CardTitle>{instance.agentName}</CardTitle><CardDescription>{instance.framework || instance.backendKind} {instance.frameworkVersion || ''}</CardDescription></div><Badge tone={healthTone(instance.health)}>{instance.health}</Badge></div></CardHeader><CardContent className="space-y-3 text-sm"><InstanceFact icon={Boxes} label="Instance" value={instance.instanceKey || instance.id} mono /><div className="grid grid-cols-2 gap-3"><InstanceFact icon={Users} label="Sessions" value={`${instance.activeSessions}/${instance.capacity}`} /><InstanceFact icon={Cpu} label="SDK" value={instance.sdkVersion || '—'} /></div><InstanceFact icon={Radio} label="Heartbeat" value={formatRelative(instance.lastSeenAt)} /></CardContent></Card>)}</div>}
    </Page>
  );
}

function InstanceFact({ icon: Icon, label, value, mono }: { icon: typeof Boxes; label: string; value: string; mono?: boolean }) { return <div className="flex min-w-0 gap-2"><Icon className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" /><div className="min-w-0"><div className="text-xs text-muted-foreground">{label}</div><div className={mono ? 'truncate font-mono text-xs' : 'truncate text-sm'} title={value}>{value}</div></div></div>; }
