/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Bot, Boxes, MessageSquare, Network, PlayCircle, Plus, Settings2 } from 'lucide-react';
import { listAgents } from '@/api/agents';
import { listDeployments } from '@/api/deployments';
import { listEnvironments } from '@/api/environments';
import { listManagedSessions } from '@/api/managedSessions';
import { listAgentInstances } from '@/api/runtimeControl';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { Page, PageHeader } from '@/components/Page';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { fetchManagedAgents } from '@/features/operate/api';

function statusTone(status: string): 'default' | 'success' | 'warning' | 'danger' | 'info' {
  if (['idle', 'active'].includes(status)) return 'success';
  if (['running', 'created'].includes(status)) return 'info';
  if (status === 'requires_action') return 'warning';
  if (status === 'errored') return 'danger';
  return 'default';
}

function updatedAt(value: number): string {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(value);
}

export default function ManagedOverviewPage() {
  const scope = useControlPlaneScope();
  const agents = useQuery({ queryKey: ['managed-overview', 'agents'], queryFn: listAgents });
  const registeredAgents = useQuery({
    queryKey: ['managed-overview', 'registered-agents', scope.namespace],
    queryFn: () => fetchManagedAgents({ presence: 'all', namespace: scope.namespace }),
    refetchInterval: 10_000,
  });
  const agentInstances = useQuery({
    queryKey: ['managed-overview', 'agent-instances', scope.tenant, scope.namespace],
    queryFn: () => listAgentInstances(scope.tenant, scope.namespace),
    refetchInterval: 10_000,
  });
  const sessions = useQuery({
    queryKey: ['managed-overview', 'sessions'],
    queryFn: () => listManagedSessions(undefined, 'active'),
    refetchInterval: 10_000,
  });
  const entrypoints = useQuery({ queryKey: ['managed-overview', 'entrypoints'], queryFn: listDeployments });
  const environments = useQuery({ queryKey: ['managed-overview', 'environments'], queryFn: listEnvironments });

  const agentItems = agents.data || [];
  const sessionItems = sessions.data || [];
  const entrypointItems = entrypoints.data || [];
  const registeredItems = registeredAgents.data?.items || [];
  const instanceItems = agentInstances.data?.items || [];
  const recentSessions = [...sessionItems].sort((a, b) => b.updatedAt - a.updatedAt).slice(0, 5);
  const hasError = agents.isError || registeredAgents.isError || agentInstances.isError || sessions.isError || entrypoints.isError || environments.isError;

  return (
    <Page className="max-w-[1400px]">
      <PageHeader
        title="Managed Agents"
        description="Manage the complete Agent portfolio: platform-managed definitions, registered application Agents, observed instances, and their reusable delivery bindings."
        actions={(
          <>
            <Button variant="outline" asChild><Link to="/managed/sessions/new"><MessageSquare className="h-4 w-4" />New conversation</Link></Button>
            <Button asChild><Link to="/managed/agents/new"><Plus className="h-4 w-4" />New Agent</Link></Button>
          </>
        )}
      />

      {hasError && (
        <div role="alert" className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
          Some Managed Agent resources could not be loaded. Check the service API and refresh this page.
        </div>
      )}

      <section aria-labelledby="agent-portfolio-heading" className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2"><Bot className="h-5 w-5 text-primary" /><h2 id="agent-portfolio-heading" className="text-lg font-semibold">Agent portfolio</h2></div>
          <span className="font-mono text-xs text-muted-foreground">Registered scope: {scope.tenant} / {scope.namespace}</span>
        </div>
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <SummaryCard icon={Bot} label="Managed agents" value={agentItems.filter((item) => !item.archivedAt).length} detail={`${agentItems.length} platform-managed definitions`} to="/managed/agents" />
          <SummaryCard icon={Network} label="Registered agents" value={registeredItems.length} detail="Application SDK and Runtime Host registrations" to={scope.scopedPath('/managed/registered-agents?presence=all')} />
          <SummaryCard icon={Boxes} label="Healthy instances" value={instanceItems.filter((item) => item.health === 'healthy').length} detail={`${instanceItems.length} observed data-plane instances`} to={scope.scopedPath('/managed/agent-instances?health=healthy')} />
          <SummaryCard icon={Boxes} label="Unhealthy instances" value={instanceItems.filter((item) => item.health !== 'healthy').length} detail="Instances requiring Agent-owner attention" to={scope.scopedPath('/managed/agent-instances?health=unhealthy')} />
        </div>
      </section>

      <section aria-labelledby="delivery-heading" className="space-y-3">
        <div className="flex items-center gap-2"><PlayCircle className="h-5 w-5 text-primary" /><h2 id="delivery-heading" className="text-lg font-semibold">Managed delivery</h2></div>
        <div className="grid gap-4 sm:grid-cols-3">
          <SummaryCard icon={MessageSquare} label="Conversations" value={sessionItems.length} detail={`${sessionItems.filter((item) => item.status === 'running').length} currently running`} to="/managed/sessions" />
          <SummaryCard icon={PlayCircle} label="Entrypoints" value={entrypointItems.filter((item) => item.enabled && !item.archivedAt).length} detail={`${entrypointItems.length} trigger bindings`} to="/managed/entrypoints" />
          <SummaryCard icon={Settings2} label="Environments" value={(environments.data || []).filter((item) => !item.archivedAt).length} detail="Reusable execution bindings" to="/managed/environments" />
        </div>
      </section>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1.5fr)_minmax(20rem,1fr)]">
        <Card>
          <CardHeader className="flex-row items-start justify-between gap-4">
            <div><CardTitle>Recent conversations</CardTitle><CardDescription>Durable bindings that materialize an Agent only when a turn is invoked.</CardDescription></div>
            <Button variant="outline" size="sm" asChild><Link to="/managed/sessions">View all</Link></Button>
          </CardHeader>
          <CardContent className="space-y-2">
            {recentSessions.map((session) => (
              <Link key={session.id} to={`/managed/sessions/${encodeURIComponent(session.id)}`} className="flex min-w-0 items-center gap-3 rounded-lg border border-border px-3 py-3 hover:bg-muted">
                <MessageSquare className="h-4 w-4 shrink-0 text-muted-foreground" />
                <span className="min-w-0 flex-1"><span className="block truncate text-sm font-medium">{session.id}</span><span className="block truncate text-xs text-muted-foreground">Agent {session.agentId} · updated {updatedAt(session.updatedAt)}</span></span>
                <Badge tone={statusTone(session.status)}>{session.status}</Badge>
              </Link>
            ))}
            {!sessions.isLoading && recentSessions.length === 0 && <p className="py-8 text-center text-sm text-muted-foreground">No conversations yet. Start one from a Managed Agent.</p>}
          </CardContent>
        </Card>

        <Card>
          <CardHeader><CardTitle>How this area works</CardTitle><CardDescription>Agent definitions, registrations, and runtime observations stay distinct while sharing one management surface.</CardDescription></CardHeader>
          <CardContent className="space-y-4 text-sm">
            <FlowStep icon={Bot} title="Define" detail="Build Managed Agent versions, tools, skills, and default resources." />
            <FlowStep icon={Network} title="Connect" detail="Application SDKs and Runtime Hosts register user-operated Agents and instances." />
            <FlowStep icon={PlayCircle} title="Invoke" detail="A chat message or trigger materializes a turn, sandbox, and worker lease on demand." />
          </CardContent>
        </Card>
      </div>
    </Page>
  );
}

function SummaryCard({ icon: Icon, label, value, detail, to }: { icon: typeof Bot; label: string; value: number; detail: string; to: string }) {
  return <Link to={to} className="rounded-xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"><Card className="h-full hover:border-indigo-200 hover:shadow-sm"><CardHeader className="pb-3"><div className="flex items-center justify-between"><CardDescription>{label}</CardDescription><Icon className="h-4 w-4 text-muted-foreground" /></div><CardTitle className="font-mono text-3xl tabular-nums">{value}</CardTitle></CardHeader><CardContent className="text-xs text-muted-foreground">{detail}</CardContent></Card></Link>;
}

function FlowStep({ icon: Icon, title, detail }: { icon: typeof Bot; title: string; detail: string }) {
  return <div className="flex gap-3"><div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-muted"><Icon className="h-4 w-4" /></div><div><div className="font-medium">{title}</div><div className="mt-0.5 text-muted-foreground">{detail}</div></div></div>;
}
