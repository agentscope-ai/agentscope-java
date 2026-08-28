import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  acceptIssue, addComment, archiveIssue, assignIssue, createChildIssue, exportIssue, getIssue, getIssueSummary,
  listChildIssues, listComments, listTasks, rejectIssue, reopenIssue, resolveComment, transitionIssue,
  uploadIssueArtifact,
} from '@/api/collaboration';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { listRuns } from '@/api/orchestration';
import { Page, PageHeader } from '@/components/Page';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input, Textarea } from '@/components/ui/input';
import { formatRelative } from '@/lib/format';

export default function IssueDetailPage() {
  const { issueId = '' } = useParams();
  const scope = useControlPlaneScope();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [content, setContent] = useState('');
  const [mentionType, setMentionType] = useState('agent');
  const [mentionRef, setMentionRef] = useState('');
  const [assigneeType, setAssigneeType] = useState('agent');
  const [assigneeRef, setAssigneeRef] = useState('');
  const [childTitle, setChildTitle] = useState('');
  const [artifactFile, setArtifactFile] = useState<File | null>(null);
  const issue = useQuery({ queryKey: ['issue', issueId], queryFn: () => getIssue(issueId), enabled: !!issueId });
  const comments = useQuery({ queryKey: ['comments', issueId], queryFn: () => listComments(issueId), enabled: !!issueId, refetchInterval: 5000 });
  const tasks = useQuery({ queryKey: ['tasks', scope.tenant, scope.namespace], queryFn: () => listTasks(scope.tenant, scope.namespace), enabled: !!issueId });
  const summary = useQuery({ queryKey: ['issue-summary', issueId], queryFn: () => getIssueSummary(issueId), enabled: !!issueId });
  const children = useQuery({ queryKey: ['issue-children', issueId], queryFn: () => listChildIssues(scope.tenant, scope.namespace, issueId), enabled: !!issueId });
  const runs = useQuery({ queryKey: ['issue-runs', issueId], queryFn: () => listRuns(scope.tenant, scope.namespace, issueId), enabled: !!issueId, refetchInterval: 3000 });
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['issue', issueId] });
    void qc.invalidateQueries({ queryKey: ['issue-summary', issueId] });
    void qc.invalidateQueries({ queryKey: ['issues'] });
  };
  const comment = useMutation({ mutationFn: () => addComment(issueId, content, undefined, mentionRef ? [{ type: mentionType, ref: mentionRef }] : []), onSuccess: () => { setContent(''); setMentionRef(''); void qc.invalidateQueries({ queryKey: ['comments', issueId] }); } });
  const resolve = useMutation({ mutationFn: ({id,version,resolved}:{id:string;version:number;resolved:boolean}) => resolveComment(issueId,id,version,resolved), onSuccess: () => void qc.invalidateQueries({ queryKey: ['comments', issueId] }) });
  const assign = useMutation({ mutationFn: () => assignIssue(issueId,assigneeType,assigneeRef,issue.data?.issue.version || 0), onSuccess: () => { setAssigneeRef(''); refresh(); void qc.invalidateQueries({queryKey:['tasks']}); } });
  const child = useMutation({ mutationFn: () => createChildIssue(issueId,{title:childTitle,priority:'normal'}), onSuccess: () => { setChildTitle(''); refresh(); void qc.invalidateQueries({queryKey:['issue-children',issueId]}); } });
  const artifact = useMutation({ mutationFn: () => uploadIssueArtifact(scope.tenant,scope.namespace,issueId,artifactFile!), onSuccess: () => { setArtifactFile(null); refresh(); } });
  const action = useMutation({ mutationFn: async (kind: string) => {
    const current = issue.data?.issue;
    if (!current) return;
    if (kind === 'accept') await acceptIssue(issueId, current.version);
    else if (kind === 'reject') await rejectIssue(issueId, current.version, 'Changes requested');
    else if (kind === 'reopen') await reopenIssue(issueId, current.version);
    else if (kind === 'archive') await archiveIssue(issueId, current.version);
    else await transitionIssue(issueId, kind, current.version);
  }, onSuccess: (_data, kind) => { refresh(); if (kind === 'archive') navigate(scope.scopedPath('/issues')); } });
  const download = useMutation({ mutationFn: () => exportIssue(issueId), onSuccess: (data) => {
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const href = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = href;
    anchor.download = `issue-${issueId}.json`;
    anchor.click();
    URL.revokeObjectURL(href);
  } });
  function submit(event: FormEvent) { event.preventDefault(); comment.mutate(); }
  if (!issue.data) return <Page>Loading Issue…</Page>;
  const item = issue.data.issue;
  const issueTasks = (tasks.data?.items || []).filter((task) => task.issueId === issueId);
  const stats = summary.data?.summary;
  return <Page className="max-w-[1200px]">
    <Link to={scope.scopedPath('/issues')} className="text-sm text-muted-foreground hover:underline">← Issues</Link>
    <PageHeader title={<span className="flex items-center gap-3">{item.title}<Badge tone={item.status === 'done' ? 'success' : item.status === 'blocked' ? 'danger' : 'warning'}>{item.status}</Badge></span>} description={item.description || `Issue ${item.id}`} actions={<div className="flex flex-wrap gap-2">
      {['backlog','todo','blocked'].includes(item.status) && <Button variant="outline" onClick={() => action.mutate('in_progress')}>Start</Button>}
      {item.status === 'in_progress' && <Button variant="outline" onClick={() => action.mutate('in_review')}>Request review</Button>}
      {item.status === 'in_review' && <><Button onClick={() => action.mutate('accept')}>Accept</Button><Button variant="outline" onClick={() => action.mutate('reject')}>Reject</Button></>}
      {item.status === 'done' && !item.archivedAt && <Button variant="outline" onClick={() => action.mutate('reopen')}>Reopen</Button>}
      {(item.status === 'done' || item.status === 'cancelled') && !item.archivedAt && <Button variant="outline" onClick={() => action.mutate('archive')}>Archive</Button>}
      <Button variant="outline" onClick={() => download.mutate()}>Export</Button>
    </div>} />
    {stats && <div className="grid grid-cols-2 gap-3 md:grid-cols-5">{[
      ['Comments', stats.commentCount], ['Open threads', stats.unresolvedThreads], ['Active tasks', stats.activeTasks], ['Terminal tasks', stats.terminalTasks], ['Children', stats.childCount],
    ].map(([label, value]) => <div key={String(label)} className="rounded-xl border bg-white p-3"><div className="text-xs text-muted-foreground">{label}</div><div className="text-xl font-semibold">{value}</div></div>)}</div>}
    {!!runs.data?.runs.length && <div className="rounded-xl border bg-white p-4"><h2 className="font-semibold">Run history</h2><div className="mt-3 flex flex-wrap gap-2">{runs.data.runs.map(run=><Link key={run.id} to={scope.scopedPath(`/orchestration/runs/${run.id}`)} className="rounded-lg bg-muted px-3 py-2 text-sm hover:bg-accent"><span className="font-mono">{run.id.slice(0,8)}</span> · {run.mode} · {run.state}</Link>)}</div></div>}
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
      <section className="space-y-4"><h2 className="text-lg font-semibold">Discussion</h2><div className="space-y-3">{(comments.data?.items || []).map((entry) => <article key={`${entry.id}-${entry.version}`} className="rounded-xl border bg-white p-4"><div className="flex justify-between text-xs text-muted-foreground"><span>{entry.author.type} · {entry.author.ref || 'system'} · {entry.type}</span><span>{formatRelative(entry.createdAt)}</span></div><p className="mt-3 whitespace-pre-wrap text-sm">{entry.content}</p>{entry.routes?.length ? <div className="mt-3 flex flex-wrap gap-2">{entry.routes.map((route) => <Badge key={route.targetRef + route.outcome} tone={route.outcome === 'blocked' ? 'danger' : 'info'}>{route.targetRef}: {route.outcome}</Badge>)}</div> : null}{entry.parentId == null && !entry.deletedAt && <Button className="mt-3" size="sm" variant="outline" onClick={()=>resolve.mutate({id:entry.id,version:entry.version,resolved:!entry.resolvedAt})}>{entry.resolvedAt?'Reopen thread':'Resolve thread'}</Button>}</article>)}</div><form onSubmit={submit} className="space-y-3 rounded-xl border bg-white p-4"><Textarea value={content} onChange={(event) => setContent(event.target.value)} placeholder="Add context, feedback, or mention instructions" required/><div className="grid gap-2 sm:grid-cols-[8rem_1fr_auto]"><select className="h-10 rounded-lg border px-3 text-sm" value={mentionType} onChange={e=>setMentionType(e.target.value)}><option value="agent">Agent</option><option value="team">Team</option><option value="human">Human</option></select><Input value={mentionRef} onChange={e=>setMentionRef(e.target.value)} placeholder="Optional mention reference"/><Button type="submit" disabled={comment.isPending}>Comment</Button></div></form></section>
      <aside className="space-y-4"><div className="rounded-xl border bg-white p-4"><h2 className="font-semibold">Assignment</h2><div className="mt-3 grid gap-2"><select className="h-10 rounded-lg border px-3 text-sm" value={assigneeType} onChange={e=>setAssigneeType(e.target.value)}><option value="agent">Agent</option><option value="team">Team</option><option value="human">Human</option></select><Input value={assigneeRef} onChange={e=>setAssigneeRef(e.target.value)} placeholder={item.assigneeRef || 'Assignee reference'}/><Button variant="outline" disabled={!assigneeRef||assign.isPending} onClick={()=>assign.mutate()}>Assign</Button></div></div><div className="rounded-xl border bg-white p-4"><h2 className="font-semibold">Child Issues</h2><ul className="mt-3 space-y-2">{(children.data?.items||[]).map(childItem=><li key={childItem.id}><Link className="text-sm text-primary hover:underline" to={scope.scopedPath(`/issues/${childItem.id}`)}>{childItem.title} · {childItem.status}</Link></li>)}</ul><div className="mt-3 grid gap-2"><Input value={childTitle} onChange={e=>setChildTitle(e.target.value)} placeholder="Delegated work title"/><Button variant="outline" disabled={!childTitle||child.isPending} onClick={()=>child.mutate()}>Create child</Button></div></div><div className="rounded-xl border bg-white p-4"><h2 className="font-semibold">Artifacts</h2><Input className="mt-3" type="file" onChange={e=>setArtifactFile(e.target.files?.[0]||null)}/><Button className="mt-2" variant="outline" disabled={!artifactFile||artifact.isPending} onClick={()=>artifact.mutate()}>Upload to Issue</Button></div><div className="rounded-xl border bg-white p-4"><h2 className="font-semibold">AgentTasks</h2><ul className="mt-3 space-y-2">{issueTasks.map((task) => <li key={task.id}><Link to={scope.scopedPath(`/tasks/${task.id}`)} className="block rounded-lg bg-muted p-3 text-sm hover:bg-accent"><div>{task.agentId}</div><div className="text-xs text-muted-foreground">{task.status} · {task.inputs?.length || 0} inputs</div></Link></li>)}</ul></div>{stats?.latestResult && <div className="rounded-xl border bg-white p-4"><h2 className="font-semibold">Latest result</h2><p className="mt-2 whitespace-pre-wrap text-sm">{stats.latestResult}</p></div>}{item.parentIssueId && <Link className="block text-sm text-primary hover:underline" to={scope.scopedPath(`/issues/${item.parentIssueId}`)}>Open parent Issue</Link>}</aside>
    </div>
  </Page>;
}
