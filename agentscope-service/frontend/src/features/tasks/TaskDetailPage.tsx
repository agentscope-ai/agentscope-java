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

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import { cancelTask, getTask, retryTask } from '@/api/collaboration';
import { listAttempts } from '@/api/orchestration';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { EntityIdentityText, useEntityIdentities } from '@/components/EntityIdentity';
import { Page, PageHeader } from '@/components/Page';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';

const terminal = (state?: string) => ['completed', 'failed', 'cancelled'].includes(state || '');
const time = (value?: string) => value ? new Date(value).toLocaleString() : 'Not recorded';
function duration(start?: string, end?: string) {
  if (!start || !end) return '';
  const seconds = Math.max(0, (Date.parse(end) - Date.parse(start)) / 1000);
  return Number.isFinite(seconds) ? `${seconds.toFixed(1)} s` : '';
}
function ResultContent({ value }: { value: unknown }) {
  if (value == null) return <p className="text-sm text-muted-foreground">No result was recorded.</p>;
  if (typeof value === 'string') return <div className="md-text break-words leading-7"><ReactMarkdown>{value}</ReactMarkdown></div>;
  if (typeof value !== 'object') return <p>{String(value)}</p>;
  const entries = Array.isArray(value) ? value.map((item, index) => [String(index + 1), item] as const) : Object.entries(value);
  if (!entries.length) return <p className="text-sm text-muted-foreground">The execution returned an empty result.</p>;
  const main = entries.find(([key, item]) => ['output', 'final', 'response', 'message', 'content', 'result', 'text'].includes(key) && typeof item === 'string' && item.trim());
  const fields = main ? entries.filter(([key]) => key !== main[0]) : entries;
  return <div className="space-y-4">{main && <ResultContent value={main[1]} />}<dl className="space-y-3">{fields.map(([key, item]) => <div key={key} className="min-w-0 rounded-lg bg-muted/50 p-3"><dt className="mb-1 text-xs font-semibold capitalize text-muted-foreground">{key}</dt><dd className="break-words text-sm"><ResultContent value={item} /></dd></div>)}</dl></div>;
}
function Diagnostics({ value }: { value: unknown }) {
  return <details className="mt-3"><summary className="cursor-pointer text-sm text-muted-foreground">Diagnostic details</summary><pre className="mt-2 max-h-96 overflow-auto whitespace-pre-wrap break-all rounded-lg bg-slate-950 p-4 text-xs text-slate-100">{JSON.stringify(value, null, 2)}</pre></details>;
}

export default function TaskDetailPage() {
  const { taskId = '' } = useParams();
  const scope = useControlPlaneScope();
  const qc = useQueryClient();
  const detail = useQuery({ queryKey: ['agent-task', taskId], queryFn: () => getTask(taskId), enabled: !!taskId, refetchInterval: query => terminal(query.state.data?.task.status) ? false : 3000, refetchIntervalInBackground: false });
  const attempts = useQuery({ queryKey: ['execution-attempts', taskId], queryFn: () => listAttempts(scope.tenant, scope.namespace, taskId), enabled: !!taskId, refetchInterval: () => terminal(detail.data?.task.status) ? false : 3000, refetchIntervalInBackground: false });
  const task = detail.data?.task;
  const history = [...(attempts.data?.attempts || [])].sort((a, b) => a.attempt - b.attempt || a.createdAt.localeCompare(b.createdAt));
  const identities = useEntityIdentities([{ type: 'agent', ref: task?.agentId }, { type: 'issue', ref: task?.issueId }, { type: 'team', ref: task?.teamId }, ...history.map(attempt => ({ type: 'runtime_host', ref: attempt.hostId }))]);
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['agent-task', taskId] }); void qc.invalidateQueries({ queryKey: ['execution-attempts', taskId] }); };
  const cancel = useMutation({ mutationFn: () => cancelTask(taskId, task?.version || 0), onSuccess: refresh });
  const retry = useMutation({ mutationFn: () => retryTask(taskId), onSuccess: refresh });
  if (detail.isError) return <Page><p role="alert">Unable to load task: {String(detail.error)}</p></Page>;
  if (!task) return <Page>Loading task…</Page>;
  const finalResult = task.result ?? history[history.length - 1]?.result;
  const mutationError = cancel.error || retry.error;
  const issuePath = scope.scopedPath(`/work/issues/${task.issueId}`);
  return <Page>
    <Link to={scope.scopedPath(task.orchestrationRunId ? `/work/executions/${task.orchestrationRunId}` : '/work/executions')} className="text-sm text-muted-foreground">← Execution</Link>
    <PageHeader title={<span className="flex flex-wrap items-center gap-3">{task.teamRole ? `${task.teamRole} task` : 'Agent task'}<Badge tone={task.status === 'completed' ? 'success' : task.status === 'failed' ? 'danger' : 'warning'}>{task.status}</Badge></span>}
      description={<><EntityIdentityText identities={identities} type="agent" entityRef={task.agentId} /> · Created {time(task.createdAt)}</>}
      actions={<div className="flex gap-2">{task.status === 'failed' && <Button disabled={retry.isPending} variant="outline" onClick={() => retry.mutate()}>{retry.isPending ? 'Retrying…' : 'Retry'}</Button>}{!terminal(task.status) && <Button disabled={cancel.isPending} variant="destructive" onClick={() => cancel.mutate()}>{cancel.isPending ? 'Cancelling…' : 'Cancel'}</Button>}</div>} />
    {mutationError && <p role="alert" className="text-sm text-destructive">{String(mutationError)}</p>}
    <div className="mb-5 flex flex-wrap items-center gap-4 text-sm">
      <Link className="text-primary underline" to={issuePath}>Issue: <EntityIdentityText identities={identities} type="issue" entityRef={task.issueId} /></Link>
      <Link className="text-primary underline" to={scope.scopedPath(task.orchestrationRunId ? `/work/executions/${task.orchestrationRunId}` : '/work/executions')}>View execution</Link>
      <span className="text-muted-foreground">{task.triggerType}{task.startedAt && ` · Started ${time(task.startedAt)}`}{task.completedAt && ` · Finished ${time(task.completedAt)}`}{duration(task.startedAt, task.completedAt) && ` · ${duration(task.startedAt, task.completedAt)}`}</span>
    </div>
    <div className="grid items-start gap-5 xl:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)]">
      <div className="min-w-0 space-y-5">
        <section className="rounded-xl border border-border bg-white p-5"><h2 className="mb-3 font-semibold">Result</h2>
          {task.errorMessage && <p role="alert" className="mb-3 rounded-lg bg-red-50 p-3 text-sm text-red-800">{task.errorMessage}{task.errorCode && <span className="mt-1 block font-mono text-xs">{task.errorCode}</span>}</p>}
          {finalResult == null && !terminal(task.status) ? <p className="text-sm text-muted-foreground">The result will appear when the execution finishes.</p> : <ResultContent value={finalResult} />}
        </section>
        <section className="rounded-xl border border-border bg-white p-5"><h2 className="mb-3 font-semibold">Inputs</h2>
          {!task.inputs?.length && <p className="text-sm text-muted-foreground">This task was started from the <Link className="text-primary underline" to={issuePath}>Issue and execution context</Link>.</p>}
          <ol className="space-y-3">{(task.inputs || []).map(input => {
            const summary = detail.data?.inputSummaries?.find(item => item.inputId === input.id);
            return <li key={input.id} className="rounded-lg border border-border p-3"><div className="mb-2 flex justify-between text-xs text-muted-foreground"><span>Input #{input.sequence}</span><span>{input.state}</span></div>
              {summary?.state === 'recorded' ? <ResultContent value={summary.content} /> : <p className="text-sm text-muted-foreground">{summary?.state === 'changed' ? 'The source comment changed after this input was captured. Its current text is not shown as the original input.' : 'Input text is unavailable.'}</p>}
              <Link className="mt-2 inline-block text-xs text-primary underline" to={`${issuePath}${issuePath.includes('?') ? '&' : '?'}comment=${encodeURIComponent(input.commentId)}`}>Open source comment · version {input.commentVersion}</Link>
            </li>;
          })}</ol>
        </section>
      </div>
      <section className="min-w-0 rounded-xl border border-border bg-white p-5"><h2 className="font-semibold">Execution history</h2>
        {attempts.isError && <p role="alert" className="mt-3 text-sm text-destructive">Unable to load execution history: {String(attempts.error)}</p>}
        {!history.length && <p className="mt-3 text-sm text-muted-foreground">{attempts.isLoading ? 'Loading attempts…' : 'Waiting for a runtime to accept this task.'}</p>}
        <ol className="mt-4 space-y-4">{history.map(attempt => <li key={attempt.id} className="rounded-lg border border-border p-4">
          <div className="flex justify-between gap-2"><h3 className="text-sm font-semibold">Attempt {attempt.attempt}</h3><Badge tone={attempt.state === 'succeeded' ? 'success' : attempt.state === 'failed' ? 'danger' : 'warning'}>{attempt.state}</Badge></div>
          <p className="mt-1 text-xs text-muted-foreground">{attempt.backendKind}{attempt.hostId && <> · <EntityIdentityText identities={identities} type="runtime_host" entityRef={attempt.hostId} /></>}</p>
          <ol className="my-3 space-y-2 border-l-2 pl-3 text-xs text-muted-foreground"><li>Created · {time(attempt.createdAt)}</li>{attempt.startedAt && <li>Started · {time(attempt.startedAt)}</li>}{attempt.completedAt && <li>Finished · {time(attempt.completedAt)} · {duration(attempt.startedAt, attempt.completedAt)}</li>}</ol>
          {attempt.failureMessage && <p className="mb-2 text-sm text-red-700">{attempt.failureMessage}</p>}
          {attempt.sessionRef && <Button asChild size="sm" variant="outline"><Link to={scope.scopedPath(`/work/sessions/${attempt.sessionRef}`)}>View conversation and events</Link></Button>}
          {attempt.result != null && JSON.stringify(attempt.result) !== JSON.stringify(finalResult) && <details className="mt-3"><summary className="cursor-pointer text-sm">Attempt result</summary><div className="mt-2"><ResultContent value={attempt.result} /></div></details>}
          <Diagnostics value={{ id: attempt.id, bindingId: attempt.bindingId, dispatchGeneration: attempt.dispatchGeneration, sessionId: attempt.sessionId, providerSessionId: attempt.providerSessionId, failureCode: attempt.failureCode, runtimeBinding: attempt.runtimeBinding, result: attempt.result, usage: attempt.usage }} />
        </li>)}</ol>
        <Diagnostics value={{ taskId: task.id, runId: task.orchestrationRunId, nodeId: task.runNodeId, runtimeBinding: task.runtimeBinding, result: task.result }} />
      </section>
    </div>
  </Page>;
}
