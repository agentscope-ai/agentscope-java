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

import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { CapabilityGate, DisabledAction } from '@/components/CapabilityGate';
import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';
import { canPlanMode, canQueryContext, canQuerySubagentTasks, canQueryTasks } from '@/lib/capabilities';
import {
  abortSession,
  archiveSession,
  compressSession,
  fetchRuntimeSession,
  fetchSessionCommands,
  fetchSessionContext,
  fetchSessionSubagentTasks,
  fetchSessionTasks,
  fetchSessionTurns,
  restoreSession,
  setSessionPlanMode,
  terminateSession,
  type SessionTurn,
} from './api';
import { CompressButton } from './components/CompressButton';
import { ContextPanel, contextSummary } from './components/ContextPanel';
import { ConversationHistoryPanel } from './components/ConversationHistoryPanel';
import { SessionEventsPanel } from './components/SessionEventsPanel';
import { StatusStrip } from './components/StatusStrip';
import { useSessionMessages } from './lib/useSessionMessages';

export default function OperateSessionDetailPage() {
  const { sessionId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const agent = params.get('agent') || undefined;
  const namespace = params.get('namespace') || undefined;
  const turnParam = params.get('turn');
  const qc = useQueryClient();
  const [contextOpen, setContextOpen] = useState(false);
  const [commandsOpen, setCommandsOpen] = useState(false);

  const session = useQuery({
    queryKey: ['runtime-session', sessionId, agent, namespace],
    queryFn: () => fetchRuntimeSession(sessionId, { agent, namespace }),
    enabled: !!sessionId,
    refetchInterval: 5_000,
    refetchIntervalInBackground: false,
  });

  const s = session.data;
  // Capabilities come from the session response (enriched by control plane) —
  // do NOT join against dataplanes[0].
  const sessionReady = !!s;
  const contractLevel = s?.contractLevel ?? 0;
  const capabilities = s?.capabilities || [];

  const context = useQuery({
    queryKey: ['runtime-context', sessionId],
    queryFn: () => fetchSessionContext(sessionId),
    enabled: sessionReady && canQueryContext(capabilities),
  });

  // Messages: always try CP transcript first (no message-query pre-gate).
  const messages = useSessionMessages(sessionId, {
    agent,
    namespace,
    enabled: !!sessionId && sessionReady,
    pollMs: 5_000,
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

  const turns = useQuery({
    queryKey: ['runtime-turns', sessionId],
    queryFn: () => fetchSessionTurns(sessionId),
    enabled: !!sessionId,
    refetchInterval: 5_000,
    refetchIntervalInBackground: false,
  });

  const turnList = turns.data?.turns || [];
  const selectedTurnIndex = (() => {
    if (turnParam) {
      const n = Number(turnParam);
      if (Number.isFinite(n) && turnList.some((t) => t.turnIndex === n)) return n;
    }
    const running = turnList.find((t) => t.status === 'running');
    if (running) return running.turnIndex;
    return turnList[0]?.turnIndex ?? null;
  })();

  function selectTurn(t: SessionTurn) {
    const next = new URLSearchParams(params);
    next.set('turn', String(t.turnIndex));
    setParams(next, { replace: true });
  }

  const compress = useMutation({
    mutationFn: (opts: { force?: boolean; queue?: boolean }) => compressSession(sessionId, opts),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['runtime-session', sessionId] });
      qc.invalidateQueries({ queryKey: ['runtime-commands', sessionId] });
      qc.invalidateQueries({ queryKey: ['runtime-turns', sessionId] });
    },
  });
  const terminate = useMutation({
    mutationFn: () => terminateSession(sessionId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['runtime-session', sessionId] });
      qc.invalidateQueries({ queryKey: ['runtime-turns', sessionId] });
    },
  });
  const archive = useMutation({
    mutationFn: () => archiveSession(sessionId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['runtime-session', sessionId] });
      qc.invalidateQueries({ queryKey: ['runtime-sessions'] });
      qc.invalidateQueries({ queryKey: ['runtime-turns', sessionId] });
    },
  });
  const restore = useMutation({
    mutationFn: () => restoreSession(sessionId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['runtime-session', sessionId] });
      qc.invalidateQueries({ queryKey: ['runtime-sessions'] });
      qc.invalidateQueries({ queryKey: ['runtime-turns', sessionId] });
    },
  });
  const abort = useMutation({
    mutationFn: () => abortSession(sessionId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['runtime-session', sessionId] });
      qc.invalidateQueries({ queryKey: ['runtime-commands', sessionId] });
      qc.invalidateQueries({ queryKey: ['runtime-turns', sessionId] });
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
  const ctxSummary = contextSummary(context.data);
  const phase = (s?.phase || '').toLowerCase();
  const isArchived = phase === 'archived';
  const readOnlyOps = phase === 'terminated' || isArchived;
  const canArchive = phase === 'idle';
  const canRestore = isArchived;
  const compressDisabled = readOnlyOps || phase === 'compressing';

  return (
    <Page>
      <div>
        <Link to="/operate/sessions" className="text-sm text-muted-foreground hover:text-foreground">
          ← Sessions
        </Link>
        <PageHeader
          className="mt-2"
          title={sessionId}
          description={`${s?.agentName} · ${s?.namespace} · ${s?.framework || 'framework n/a'}${contractLevel ? ` · L${contractLevel}` : ''}${selectedTurnIndex != null ? ` · turn #${selectedTurnIndex}` : ''}`}
          actions={
            <>
              <CapabilityGate contractLevel={contractLevel} capabilities={capabilities} action="compress">
                {(enabled, tip) =>
                  enabled ? (
                    <CompressButton
                      busy={phase === 'active' ? true : phase === 'idle' ? false : s?.busy}
                      disabled={compressDisabled}
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
              {canArchive && (
                <Button
                  size="sm"
                  variant="outline"
                  disabled={archive.isPending}
                  onClick={() => archive.mutate()}
                >
                  Archive
                </Button>
              )}
              {canRestore && (
                <Button
                  size="sm"
                  variant="outline"
                  disabled={restore.isPending}
                  onClick={() => restore.mutate()}
                >
                  Restore
                </Button>
              )}
              <CapabilityGate contractLevel={contractLevel} capabilities={capabilities} action="abort">
                {(enabled, tip) =>
                  enabled && !readOnlyOps ? (
                    <Button size="sm" variant="outline" disabled={abort.isPending} onClick={() => abort.mutate()}>
                      Abort turn
                    </Button>
                  ) : (
                    <DisabledAction
                      label="Abort turn"
                      tip={readOnlyOps ? `Session is ${phase}` : tip}
                    />
                  )
                }
              </CapabilityGate>
              {canPlanMode(capabilities) && (
                <>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={readOnlyOps || planMode.isPending || planActive}
                    onClick={() => planMode.mutate(true)}
                  >
                    Enter plan
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={readOnlyOps || planMode.isPending || !planActive}
                    onClick={() => planMode.mutate(false)}
                  >
                    Exit plan
                  </Button>
                </>
              )}
              <CapabilityGate contractLevel={contractLevel} capabilities={capabilities} action="terminate">
                {(enabled, tip) =>
                  enabled && !readOnlyOps ? (
                    <Button
                      size="sm"
                      variant="destructive"
                      disabled={terminate.isPending}
                      onClick={() => terminate.mutate()}
                    >
                      Terminate
                    </Button>
                  ) : (
                    <Button size="sm" variant="destructive" disabled title={readOnlyOps ? `Session is ${phase}` : tip}>
                      Terminate
                    </Button>
                  )
                }
              </CapabilityGate>
            </>
          }
        />
      </div>

      <StatusStrip session={s} />

      <Card>
        <CardHeader className="flex flex-row items-start justify-between gap-3 space-y-0">
          <div>
            <CardTitle>Context</CardTitle>
            <CardDescription>
              Effective AgentState for the next model call (sys prompt, tools, window occupancy) —
              not lifetime API spend and not the full session transcript.
            </CardDescription>
          </div>
          <Button
            size="sm"
            variant="outline"
            disabled={!canQueryContext(capabilities) || context.isLoading || context.isError}
            onClick={() => setContextOpen(true)}
          >
            View
          </Button>
        </CardHeader>
        <CardContent>
          {!sessionReady || session.isLoading ? (
            <p className="text-sm text-muted-foreground">Loading…</p>
          ) : !canQueryContext(capabilities) ? (
            <p className="text-sm text-muted-foreground">context-query not advertised by data plane.</p>
          ) : context.isError ? (
            <p className="text-sm text-red-600">Failed to load context.</p>
          ) : context.isLoading || (context.isFetching && !context.data) ? (
            <p className="text-sm text-muted-foreground">Loading…</p>
          ) : !context.data || !ctxSummary ? (
            <p className="text-sm text-muted-foreground">No context.</p>
          ) : (
            <div className="flex flex-wrap items-center gap-2">
              {ctxSummary.isCompacted && <Badge tone="warning">compacted</Badge>}
              {ctxSummary.planActive && <Badge tone="info">plan mode</Badge>}
              {ctxSummary.model && <Badge tone="info">{ctxSummary.model}</Badge>}
              <span className="text-sm text-muted-foreground">
                {ctxSummary.messageCount} effective msgs
                {ctxSummary.toolCount ? ` · ${ctxSummary.toolCount} tools` : ''}
                {ctxSummary.totalTokens != null
                  ? ` · window ${ctxSummary.totalTokens.toLocaleString()}${ctxSummary.maxTokens != null ? ` / ${ctxSummary.maxTokens.toLocaleString()}` : ''}`
                  : ''}
              </span>
            </div>
          )}
        </CardContent>
      </Card>

      <SessionEventsPanel sessionId={sessionId} enabled={!!sessionId} />

      <Dialog open={contextOpen} onOpenChange={setContextOpen}>
        <DialogContent size="xl">
          <DialogHeader>
            <DialogTitle>Context</DialogTitle>
            <DialogDescription>
              Effective AgentState window (sys prompt, tools, effective messages). Window tokens are
              latest-turn input size, not lifetime spend.
            </DialogDescription>
          </DialogHeader>
          <DialogBody>
            <ContextPanel
              data={context.data}
              unavailableReason={
                !canQueryContext(capabilities) ? 'context-query not advertised by data plane.' : undefined
              }
              error={context.isError}
              loading={context.isLoading}
            />
          </DialogBody>
        </DialogContent>
      </Dialog>

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

      <ConversationHistoryPanel
        turns={turnList}
        turnsLoading={turns.isLoading}
        messagesData={messages.page}
        messagesLoading={messages.loading}
        messagesError={messages.error}
        source={messages.source}
        total={messages.total}
        loadedCount={messages.messages.length}
        hasEarlier={messages.hasEarlier}
        loadingEarlier={messages.loadingEarlier}
        onLoadEarlier={() => void messages.loadEarlier()}
        sessionPending={!sessionReady && (session.isLoading || session.isFetching)}
        selectedTurnIndex={selectedTurnIndex}
        deepLinkTurnIndex={
          turnParam && Number.isFinite(Number(turnParam)) ? Number(turnParam) : null
        }
        onSelectTurn={selectTurn}
      />

      {(commands.data?.commands || []).length > 0 && (
        <Card>
          <button
            type="button"
            className="flex w-full items-center gap-3 px-6 py-4 text-left hover:bg-muted/40"
            onClick={() => setCommandsOpen((v) => !v)}
          >
            <span className="font-mono text-muted-foreground">{commandsOpen ? '▾' : '▸'}</span>
            <CardTitle className="text-base">Commands</CardTitle>
            <span className="text-sm text-muted-foreground">
              {(commands.data?.commands || []).length} command
              {(commands.data?.commands || []).length === 1 ? '' : 's'}
            </span>
          </button>
          {commandsOpen && (
            <CardContent className="space-y-2.5 border-t border-border pt-4">
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
          )}
        </Card>
      )}
    </Page>
  );
}
