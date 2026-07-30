import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { CapabilityGate, DisabledAction } from '@/components/CapabilityGate';
import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';
import { canPlanMode, canQueryContext, canQueryMessages, canQuerySubagentTasks, canQueryTasks } from '@/lib/capabilities';
import {
  abortSession,
  compressSession,
  fetchRuntimeSession,
  fetchSessionCommands,
  fetchSessionContext,
  fetchSessionEvents,
  fetchSessionMessages,
  fetchSessionSubagentTasks,
  fetchSessionTasks,
  setSessionPlanMode,
  terminateSession,
} from './api';
import { CompressButton } from './components/CompressButton';
import { ContextPanel } from './components/ContextPanel';
import { MessagesList } from './components/MessagesList';
import { StatusStrip } from './components/StatusStrip';

export default function OperateSessionDetailPage() {
  const { sessionId = '' } = useParams();
  const qc = useQueryClient();

  const session = useQuery({
    queryKey: ['runtime-session', sessionId],
    queryFn: () => fetchRuntimeSession(sessionId),
    enabled: !!sessionId,
    refetchInterval: 5_000,
    refetchIntervalInBackground: false,
  });

  const events = useQuery({
    queryKey: ['runtime-events', sessionId],
    queryFn: () => fetchSessionEvents(sessionId),
    enabled: !!sessionId,
    refetchInterval: 5_000,
    refetchIntervalInBackground: false,
  });

  const s = session.data;
  // Capabilities come from the session response (enriched by control plane) —
  // do NOT join against dataplanes[0].
  const contractLevel = s?.contractLevel ?? 0;
  const capabilities = s?.capabilities || [];

  const context = useQuery({
    queryKey: ['runtime-context', sessionId],
    queryFn: () => fetchSessionContext(sessionId),
    enabled: !!sessionId && canQueryContext(capabilities),
  });

  const messages = useQuery({
    queryKey: ['runtime-messages', sessionId],
    queryFn: () => fetchSessionMessages(sessionId),
    enabled: !!sessionId && canQueryMessages(capabilities),
  });

  const tasks = useQuery({
    queryKey: ['runtime-tasks', sessionId],
    queryFn: () => fetchSessionTasks(sessionId),
    enabled: !!sessionId && canQueryTasks(capabilities),
    retry: false,
  });

  const subagentTasks = useQuery({
    queryKey: ['runtime-subagent-tasks', sessionId],
    queryFn: () => fetchSessionSubagentTasks(sessionId),
    enabled: !!sessionId && canQuerySubagentTasks(capabilities),
    retry: false,
  });

  const commands = useQuery({
    queryKey: ['runtime-commands', sessionId],
    queryFn: () => fetchSessionCommands(sessionId),
    enabled: !!sessionId,
    retry: false,
  });

  const compress = useMutation({
    mutationFn: (opts: { force?: boolean; queue?: boolean }) => compressSession(sessionId, opts),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['runtime-session', sessionId] });
      qc.invalidateQueries({ queryKey: ['runtime-commands', sessionId] });
    },
  });
  const terminate = useMutation({
    mutationFn: () => terminateSession(sessionId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runtime-session', sessionId] }),
  });
  const abort = useMutation({
    mutationFn: () => abortSession(sessionId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['runtime-session', sessionId] });
      qc.invalidateQueries({ queryKey: ['runtime-commands', sessionId] });
    },
  });
  const planMode = useMutation({
    mutationFn: (active: boolean) => setSessionPlanMode(sessionId, active),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['runtime-context', sessionId] });
      qc.invalidateQueries({ queryKey: ['runtime-session', sessionId] });
    },
  });

  if (session.isError) {
    return (
      <Page>
        <EmptyState title="Session not found" description={String(session.error)} />
      </Page>
    );
  }

  const taskList = Array.isArray(tasks.data)
    ? tasks.data
    : Array.isArray((tasks.data as { tasks?: unknown[] } | undefined)?.tasks)
      ? ((tasks.data as { tasks: unknown[] }).tasks as Array<Record<string, unknown>>)
      : [];

  const bgTaskList = Array.isArray(subagentTasks.data?.tasks)
    ? (subagentTasks.data!.tasks as Array<Record<string, unknown>>)
    : [];

  const planActive = Boolean(
    (context.data as { frameworkState?: { planActive?: boolean } } | undefined)?.frameworkState
      ?.planActive,
  );

  return (
    <Page>
      <div>
        <Link to="/operate/sessions" className="text-sm text-muted-foreground hover:text-foreground">
          ← Sessions
        </Link>
        <PageHeader
          className="mt-2"
          title={sessionId}
          description={`${s?.agentName} · ${s?.namespace} · ${s?.framework || 'framework n/a'}${contractLevel ? ` · L${contractLevel}` : ''}`}
          actions={
            <>
              <CapabilityGate contractLevel={contractLevel} capabilities={capabilities} action="compress">
                {(enabled, tip) =>
                  enabled ? (
                    <CompressButton
                      busy={s?.busy}
                      pending={compress.isPending}
                      onCompress={async (opts) => {
                        const res = await compress.mutateAsync(opts);
                        return res;
                      }}
                    />
                  ) : (
                    <DisabledAction label="Compress" tip={tip} />
                  )
                }
              </CapabilityGate>
              <CapabilityGate contractLevel={contractLevel} capabilities={capabilities} action="abort">
                {(enabled, tip) =>
                  enabled ? (
                    <Button size="sm" variant="outline" disabled={abort.isPending} onClick={() => abort.mutate()}>
                      Abort turn
                    </Button>
                  ) : (
                    <DisabledAction label="Abort turn" tip={tip} />
                  )
                }
              </CapabilityGate>
              {canPlanMode(capabilities) && (
                <>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={planMode.isPending || planActive}
                    onClick={() => planMode.mutate(true)}
                  >
                    Enter plan
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={planMode.isPending || !planActive}
                    onClick={() => planMode.mutate(false)}
                  >
                    Exit plan
                  </Button>
                </>
              )}
              <CapabilityGate contractLevel={contractLevel} capabilities={capabilities} action="terminate">
                {(enabled, tip) =>
                  enabled ? (
                    <Button
                      size="sm"
                      variant="destructive"
                      disabled={terminate.isPending}
                      onClick={() => terminate.mutate()}
                    >
                      Terminate
                    </Button>
                  ) : (
                    <DisabledAction label="Terminate" tip={tip} />
                  )
                }
              </CapabilityGate>
            </>
          }
        />
      </div>

      <StatusStrip session={s} />

      <Card>
        <CardHeader>
          <CardTitle>Events</CardTitle>
        </CardHeader>
        <CardContent className="max-h-80 space-y-2.5 overflow-auto">
          {(events.data?.events || []).length === 0 ? (
            <p className="text-sm text-muted-foreground">No events stored yet.</p>
          ) : (
            (events.data?.events || []).map((e, i) => (
              <div key={i} className="rounded-lg border border-border px-4 py-3 text-sm">
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
            <ContextPanel
              data={context.data}
              unavailableReason={
                !canQueryContext(capabilities) ? 'context-query not advertised by data plane.' : undefined
              }
              error={context.isError}
              loading={context.isLoading}
            />
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Messages</CardTitle>
          </CardHeader>
          <CardContent>
            <MessagesList
              data={messages.data}
              unavailableReason={
                !canQueryMessages(capabilities) ? 'message-query not advertised by data plane.' : undefined
              }
              loading={messages.isLoading}
            />
          </CardContent>
        </Card>
      </div>

      {canQueryTasks(capabilities) && (
        <Card>
          <CardHeader>
            <CardTitle>Todo</CardTitle>
          </CardHeader>
          <CardContent>
            {tasks.isError ? (
              <p className="text-sm text-muted-foreground">Todo endpoint unavailable.</p>
            ) : tasks.isLoading ? (
              <p className="text-sm text-muted-foreground">Loading…</p>
            ) : taskList.length === 0 ? (
              <p className="text-sm text-muted-foreground">No todos.</p>
            ) : (
              <div className="space-y-2.5">
                {taskList.map((t, i) => {
                  const row = t as Record<string, unknown>;
                  return (
                    <div key={String(row.taskId || row.id || i)} className="rounded-lg border border-border px-4 py-3 text-sm">
                      <div className="font-medium">{String(row.subject || row.name || row.taskId || row.id || `task-${i}`)}</div>
                      <div className="mt-0.5 text-sm text-muted-foreground">
                        {String(row.state || row.status || '')}
                        {row.description ? ` · ${String(row.description)}` : ''}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {canQuerySubagentTasks(capabilities) && (
        <Card>
          <CardHeader>
            <CardTitle>Background tasks</CardTitle>
          </CardHeader>
          <CardContent>
            {subagentTasks.isError ? (
              <p className="text-sm text-muted-foreground">Background tasks unavailable.</p>
            ) : subagentTasks.isLoading ? (
              <p className="text-sm text-muted-foreground">Loading…</p>
            ) : bgTaskList.length === 0 ? (
              <p className="text-sm text-muted-foreground">No background subagent tasks.</p>
            ) : (
              <div className="space-y-2.5">
                {bgTaskList.map((t, i) => (
                  <div key={String(t.taskId || t.id || i)} className="rounded-lg border border-border px-4 py-3 text-sm">
                    <div className="font-medium">
                      {String(t.subject || t.subagentId || t.taskId || t.id || `bg-${i}`)}
                    </div>
                    <div className="mt-0.5 text-sm text-muted-foreground">
                      {String(t.status || t.state || '')}
                      {t.completed ? ' · completed' : ''}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {(commands.data?.commands || []).length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Commands</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2.5">
            {(commands.data?.commands || []).map((c) => (
              <div key={c.id} className="rounded-lg border border-border px-4 py-3 text-sm">
                <div className="font-medium">
                  {c.command} · {c.status}
                </div>
                <div className="mt-0.5 text-muted-foreground">
                  {new Date(c.requestedAt).toLocaleString()}
                  {c.error ? ` · ${c.error}` : ''}
                </div>
              </div>
            ))}
          </CardContent>
        </Card>
      )}
    </Page>
  );
}
