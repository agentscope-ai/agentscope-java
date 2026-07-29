import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { CapabilityGate, DisabledAction } from '@/components/CapabilityGate';
import { EmptyState } from '@/components/EmptyState';
import { JsonViewer } from '@/components/JsonViewer';
import { PressureGauge } from '@/components/PressureGauge';
import {
  compressSession,
  fetchDataPlanes,
  fetchRuntimeSession,
  fetchSessionContext,
  fetchSessionEvents,
  fetchSessionMessages,
  terminateSession,
} from './api';

export default function OperateSessionDetailPage() {
  const { sessionId = '' } = useParams();
  const qc = useQueryClient();

  const session = useQuery({
    queryKey: ['runtime-session', sessionId],
    queryFn: () => fetchRuntimeSession(sessionId),
    enabled: !!sessionId,
    refetchInterval: 5_000,
  });

  const events = useQuery({
    queryKey: ['runtime-events', sessionId],
    queryFn: () => fetchSessionEvents(sessionId),
    enabled: !!sessionId,
    refetchInterval: 5_000,
  });

  const s = session.data;
  const planes = useQuery({
    queryKey: ['dataplanes', s?.agentName, s?.namespace],
    queryFn: () => fetchDataPlanes(s!.agentName, s!.namespace || 'default'),
    enabled: !!s?.agentName,
  });

  const dp = planes.data?.dataplanes?.[0];
  const contractLevel = dp?.contractLevel || 0;
  const capabilities = dp?.capabilities || [];

  const context = useQuery({
    queryKey: ['runtime-context', sessionId],
    queryFn: () => fetchSessionContext(sessionId),
    enabled: !!sessionId && capabilities.includes('context-query'),
  });

  const messages = useQuery({
    queryKey: ['runtime-messages', sessionId],
    queryFn: () => fetchSessionMessages(sessionId),
    enabled: !!sessionId && capabilities.includes('message-query'),
  });

  const compress = useMutation({
    mutationFn: () => compressSession(sessionId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-session', sessionId] }),
  });
  const terminate = useMutation({
    mutationFn: () => terminateSession(sessionId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-session', sessionId] }),
  });

  if (session.isError) {
    return (
      <div className="p-6">
        <EmptyState title="Session not found" description={String(session.error)} />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-6xl space-y-6 p-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <Link to="/operate/sessions" className="text-xs text-muted-foreground hover:text-foreground">
            ← Sessions
          </Link>
          <h1 className="mt-2 text-xl font-semibold">{sessionId}</h1>
          <p className="text-sm text-muted-foreground">
            {s?.agentName} · {s?.namespace} · {s?.framework || 'framework n/a'}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <CapabilityGate contractLevel={contractLevel} capabilities={capabilities} action="compress">
            {(enabled, tip) =>
              enabled ? (
                <Button size="sm" variant="outline" disabled={compress.isPending} onClick={() => compress.mutate()}>
                  Compress
                </Button>
              ) : (
                <DisabledAction label="Compress" tip={tip} />
              )
            }
          </CapabilityGate>
          <CapabilityGate contractLevel={contractLevel} capabilities={capabilities} action="compress">
            {(enabled, tip) =>
              enabled ? (
                <Button size="sm" variant="destructive" disabled={terminate.isPending} onClick={() => terminate.mutate()}>
                  Terminate
                </Button>
              ) : (
                <DisabledAction label="Terminate" tip={tip} />
              )
            }
          </CapabilityGate>
        </div>
      </div>

      <div className="grid gap-4 md:grid-cols-3">
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">Phase</CardTitle>
          </CardHeader>
          <CardContent>
            <Badge>{s?.phase || '—'}</Badge>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">Context pressure</CardTitle>
          </CardHeader>
          <CardContent>
            <PressureGauge value={s?.snapshot?.contextPressure} />
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">Tokens</CardTitle>
          </CardHeader>
          <CardContent className="text-sm tabular-nums text-muted-foreground">
            {(s?.snapshot?.totalTokens ?? 0).toLocaleString()} total
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Events</CardTitle>
        </CardHeader>
        <CardContent className="max-h-80 space-y-2 overflow-auto">
          {(events.data?.events || []).length === 0 ? (
            <p className="text-sm text-muted-foreground">No events stored yet.</p>
          ) : (
            (events.data?.events || []).map((e, i) => (
              <div key={i} className="rounded-md border border-border px-3 py-2 text-xs">
                <div className="font-medium">
                  #{String(e.seq)} {String(e.eventType)}
                </div>
                <div className="mt-1 text-muted-foreground">{String(e.content || e.toolName || '')}</div>
              </div>
            ))
          )}
        </CardContent>
      </Card>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Context</CardTitle>
          </CardHeader>
          <CardContent>
            {!capabilities.includes('context-query') ? (
              <p className="text-sm text-muted-foreground">context-query not advertised by data plane.</p>
            ) : context.isError ? (
              <p className="text-sm text-red-600">Failed to load context.</p>
            ) : context.data ? (
              <JsonViewer value={context.data} className="max-h-96" />
            ) : (
              <p className="text-sm text-muted-foreground">Loading…</p>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Messages</CardTitle>
          </CardHeader>
          <CardContent>
            {!capabilities.includes('message-query') ? (
              <p className="text-sm text-muted-foreground">message-query not advertised by data plane.</p>
            ) : messages.data ? (
              <JsonViewer value={messages.data} className="max-h-96" />
            ) : (
              <p className="text-sm text-muted-foreground">Loading…</p>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
