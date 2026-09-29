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

/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Activity, Bot, ChevronDown, CircleDot, GitBranch, Search, Server } from 'lucide-react';
import { Link } from 'react-router-dom';

import { listTasks } from '@/api/collaboration';
import { listAttempts, listRuns } from '@/api/orchestration';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { EntityIdentityText, type EntityIdentityMap, useEntityIdentities } from '@/components/EntityIdentity';
import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { formatRelative } from '@/lib/format';
import {
  executionLabel,
  groupExecutions,
  isExecutionActive,
  matchesExecution,
  type ExecutionGroup,
} from './executions';

function tone(state: string): 'default' | 'success' | 'warning' | 'danger' | 'info' {
  if (['completed', 'succeeded', 'partial_succeeded', 'healthy'].includes(state)) return 'success';
  if (['failed', 'cancelled', 'timed_out', 'lost'].includes(state)) return 'danger';
  if (['running', 'dispatched', 'leased'].includes(state)) return 'info';
  if (['planned', 'queued', 'waiting', 'paused', 'cancelling'].includes(state)) return 'warning';
  return 'default';
}

function ExecutionCard({ group, identities }: { group: ExecutionGroup; identities: EntityIdentityMap }) {
  const scope = useControlPlaneScope();
  const { run, tasks, attempts } = group;
  const agents = [...new Set(tasks.map((task) => task.agentId))];
  const backendKinds = [...new Set(attempts.map((attempt) => attempt.backendKind))];

  return (
    <details className="group border-b border-slate-100 last:border-b-0">
      <summary className="flex cursor-pointer list-none items-start gap-4 px-5 py-5 hover:bg-slate-50/70">
        <span className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-xl border border-slate-200 bg-white text-slate-500">
          {run.mode === 'direct' ? <Bot className="h-4 w-4" /> : <GitBranch className="h-4 w-4" />}
        </span>
        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-center gap-2">
            <span className="text-sm font-semibold text-slate-900">{executionLabel(group)}</span>
            <Badge tone={tone(run.state)}>{run.state.replace(/_/g, ' ')}</Badge>
            <span className="rounded-full bg-slate-100 px-2 py-1 text-[11px] font-medium uppercase tracking-wide text-slate-500">{run.mode}</span>
          </span>
          <span className="mt-1.5 block text-xs text-slate-500">
            {tasks.length} agent step{tasks.length === 1 ? '' : 's'} · {attempts.length} runtime attempt{attempts.length === 1 ? '' : 's'} · {formatRelative(run.createdAt)}
          </span>
          <span className="mt-1 block truncate font-mono text-[11px] text-slate-400">
            Run {run.id.slice(0, 10)} · Issue {run.rootIssueId.slice(0, 10)}
          </span>
          {!!agents.length && (
            <span className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-xs text-slate-600">
              {agents.slice(0, 3).map((agentId) => <EntityIdentityText key={agentId} identities={identities} type="agent" entityRef={agentId} />)}
              {agents.length > 3 && <span>+{agents.length - 3} more</span>}
            </span>
          )}
        </span>
        <span className="flex shrink-0 items-center gap-3">
          {!!backendKinds.length && <span className="hidden text-xs text-slate-400 lg:block">{backendKinds.join(', ')}</span>}
          <ChevronDown className="mt-2 h-4 w-4 text-slate-400 transition-transform group-open:rotate-180" />
        </span>
      </summary>

      <div className="border-t border-slate-100 bg-slate-50/50 px-5 py-5">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div className="text-xs text-slate-500">
            Triggered by <span className="font-medium text-slate-700">{run.triggerType.replace(/_/g, ' ')}</span>
            {['waiting', 'paused'].includes(run.state) && run.waitReason && <> · Waiting: {run.waitReason}</>}
          </div>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" asChild>
              <Link to={scope.scopedPath(`/work/issues/${run.rootIssueId}`)}>Open issue</Link>
            </Button>
            <Button size="sm" asChild>
              <Link to={scope.scopedPath(`/work/executions/${run.id}`)}>Execution details</Link>
            </Button>
          </div>
        </div>

        {tasks.length ? (
          <ol className="space-y-3 border-l-2 border-slate-200 pl-5">
            {tasks.map((task) => {
              const taskAttempts = attempts.filter((attempt) => attempt.agentTaskId === task.id);
              return (
                <li key={task.id} className="relative rounded-xl border border-slate-200 bg-white p-4">
                  <span className="absolute -left-[1.7rem] top-5 h-3 w-3 rounded-full border-2 border-white bg-slate-400" />
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <EntityIdentityText identities={identities} type="agent" entityRef={task.agentId} secondary />
                        {task.teamRole && <span className="text-xs text-slate-400">as {task.teamRole}</span>}
                        <Badge tone={tone(task.status)}>{task.status.replace(/_/g, ' ')}</Badge>
                      </div>
                      <div className="mt-1 font-mono text-[11px] text-slate-400">AgentTask {task.id.slice(0, 10)} · {task.triggerType.replace(/_/g, ' ')}</div>
                    </div>
                    <Link className="text-xs font-medium text-indigo-600 hover:underline" to={scope.scopedPath(`/work/executions/tasks/${task.id}`)}>Diagnostics</Link>
                  </div>
                  {taskAttempts.length ? (
                    <div className="mt-3 grid gap-2 md:grid-cols-2 xl:grid-cols-3">
                      {taskAttempts.map((attempt) => (
                        <div key={attempt.id} className="flex items-center gap-2 rounded-lg bg-slate-50 px-3 py-2 text-xs">
                          <Server className="h-3.5 w-3.5 shrink-0 text-slate-400" />
                          <span className="min-w-0 flex-1 truncate">
                            {attempt.backendKind} · attempt #{attempt.attempt}
                            {attempt.hostId && <span className="block truncate text-[11px] text-slate-400">host <EntityIdentityText identities={identities} type="runtime_host" entityRef={attempt.hostId} /></span>}
                          </span>
                          <Badge tone={tone(attempt.state)}>{attempt.state.replace(/_/g, ' ')}</Badge>
                        </div>
                      ))}
                    </div>
                  ) : <p className="mt-3 text-xs text-slate-400">No runtime attempt has been created yet.</p>}
                </li>
              );
            })}
          </ol>
        ) : <p className="rounded-lg border border-dashed bg-white px-4 py-6 text-center text-sm text-slate-500">No Agent tasks have been created for this execution yet.</p>}
      </div>
    </details>
  );
}

export default function ExecutionsPage() {
  const scope = useControlPlaneScope();
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const [mode, setMode] = useState('');
  const runs = useQuery({
    queryKey: ['orchestration-runs', scope.tenant, scope.namespace, 'executions'],
    queryFn: () => listRuns(scope.tenant, scope.namespace),
    refetchInterval: 5_000,
	refetchIntervalInBackground: false,
  });
  const tasks = useQuery({
    queryKey: ['agent-tasks', scope.tenant, scope.namespace, 'executions'],
    queryFn: () => listTasks(scope.tenant, scope.namespace),
    refetchInterval: 5_000,
	refetchIntervalInBackground: false,
  });
  const attempts = useQuery({
    queryKey: ['execution-attempts', scope.tenant, scope.namespace, 'executions'],
    queryFn: () => listAttempts(scope.tenant, scope.namespace),
    refetchInterval: 5_000,
	refetchIntervalInBackground: false,
  });

  const groups = useMemo(
    () => groupExecutions(runs.data?.runs || [], tasks.data?.items || [], attempts.data?.attempts || []),
    [attempts.data?.attempts, runs.data?.runs, tasks.data?.items],
  );
  const identities = useEntityIdentities([
    ...(tasks.data?.items || []).map(task => ({ type: 'agent', ref: task.agentId })),
    ...(attempts.data?.attempts || []).map(attempt => ({ type: 'runtime_host', ref: attempt.hostId })),
  ]);
  const filtered = useMemo(() => groups.filter((group) => {
    if (status === 'active' && !isExecutionActive(group.run)) return false;
    if (status === 'failed' && group.run.state !== 'failed') return false;
    if (status === 'succeeded' && !['succeeded', 'partial_succeeded'].includes(group.run.state)) return false;
    if (mode && group.run.mode !== mode) return false;
    return matchesExecution(group, search);
  }), [groups, mode, search, status]);
  const loading = runs.isLoading || tasks.isLoading || attempts.isLoading;
  const failed = groups.filter((group) => group.run.state === 'failed').length;
  const active = groups.filter((group) => isExecutionActive(group.run)).length;

  return (
    <Page>
      <PageHeader
        title="Executions"
        description="One operational timeline from orchestration runs to Agent work and physical runtime attempts."
      />

      <div className="grid gap-3 sm:grid-cols-3">
        <Card className="p-4"><div className="flex items-center gap-2 text-xs font-medium text-slate-500"><CircleDot className="h-4 w-4" /> Active executions</div><div className="mt-2 text-2xl font-semibold text-slate-950">{loading ? '—' : active}</div></Card>
        <Card className="p-4"><div className="flex items-center gap-2 text-xs font-medium text-slate-500"><Activity className="h-4 w-4" /> Agent steps</div><div className="mt-2 text-2xl font-semibold text-slate-950">{loading ? '—' : tasks.data?.items.length || 0}</div></Card>
        <Card className="p-4"><div className="flex items-center gap-2 text-xs font-medium text-slate-500"><Server className="h-4 w-4" /> Failed executions</div><div className="mt-2 text-2xl font-semibold text-slate-950">{loading ? '—' : failed}</div></Card>
      </div>

      <div className="flex flex-col gap-2 lg:flex-row lg:items-center lg:justify-between">
        <label className="relative w-full lg:max-w-md">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
          <Input className="pl-9 shadow-none" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search run, issue, agent, task, backend, or host" />
        </label>
        <div className="flex gap-2">
          <select aria-label="Execution status" className="h-10 rounded-lg border border-slate-200 bg-white px-3 text-sm" value={status} onChange={(event) => setStatus(event.target.value)}>
            <option value="">All statuses</option>
            <option value="active">Active</option>
            <option value="failed">Failed</option>
            <option value="succeeded">Succeeded</option>
          </select>
          <select aria-label="Execution mode" className="h-10 rounded-lg border border-slate-200 bg-white px-3 text-sm" value={mode} onChange={(event) => setMode(event.target.value)}>
            <option value="">All modes</option>
            <option value="direct">Direct</option>
            <option value="adaptive">Team</option>
            <option value="declared">Workflow</option>
            <option value="subrun">Subrun</option>
          </select>
        </div>
      </div>

      {!loading && !filtered.length ? (
        <EmptyState
          title={groups.length ? 'No matching executions' : 'No executions yet'}
          description={groups.length ? 'Try a different search or filter.' : 'Assign an Issue, invoke an Endpoint job, or start a Workflow.'}
        />
      ) : (
        <Card className="overflow-hidden">
          <div className="flex items-center justify-between border-b border-slate-100 px-5 py-3 text-xs text-slate-500">
            <span>{loading ? 'Loading executions…' : `${filtered.length} execution${filtered.length === 1 ? '' : 's'}`}</span>
            <span>Expand an execution to inspect AgentTask and Attempt layers</span>
          </div>
          {filtered.map((group) => <ExecutionCard key={group.run.id} group={group} identities={identities} />)}
        </Card>
      )}
    </Page>
  );
}
