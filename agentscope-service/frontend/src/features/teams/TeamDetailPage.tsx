import { FormEvent, useMemo, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  approveMemberPlan,
  assignTeamTask,
  chatSessionPath,
  claimTeamTask,
  completeTeam,
  completeTeamTask,
  createTeamTask,
  deleteTeam,
  getTeam,
  listTeamEvents,
  listTeamMessages,
  listTeamTasks,
  rejectMemberPlan,
  removeTeamMember,
  sendTeamMessage,
  spawnTeamMember,
  teamPhaseTone,
  unclaimTeamTask,
  type TeamMember,
  type TeamTask,
} from '@/api/teams';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Page, PageHeader } from '@/components/Page';
import { ApiError } from '@/lib/apiClient';

type Tab = 'board' | 'members' | 'messages';

function bucketTasks(tasks: TeamTask[]) {
  const unassigned: TeamTask[] = [];
  const assigned: TeamTask[] = [];
  const inProgress: TeamTask[] = [];
  const blocked: TeamTask[] = [];
  const completed: TeamTask[] = [];
  for (const t of tasks) {
    if (t.state === 'completed') {
      completed.push(t);
      continue;
    }
    if (t.state === 'in_progress') {
      inProgress.push(t);
      continue;
    }
    const blockers = t.blockedBy?.length || 0;
    if (blockers > 0) {
      blocked.push(t);
      continue;
    }
    if (!t.owner) unassigned.push(t);
    else assigned.push(t);
  }
  return { unassigned, assigned, inProgress, blocked, completed };
}

export default function TeamDetailPage() {
  const { teamName = '' } = useParams();
  const [params] = useSearchParams();
  const namespace = params.get('namespace') || 'default';
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [tab, setTab] = useState<Tab>('board');
  const [subject, setSubject] = useState('');
  const [assignOwner, setAssignOwner] = useState<Record<string, string>>({});
  const [msgTo, setMsgTo] = useState('');
  const [msgBody, setMsgBody] = useState('');
  const [error, setError] = useState('');
  const [spawnName, setSpawnName] = useState('');
  const [spawnRef, setSpawnRef] = useState('');
  const [spawnPrompt, setSpawnPrompt] = useState('');

  const detail = useQuery({
    queryKey: ['team', namespace, teamName],
    queryFn: () => getTeam(teamName, namespace),
    enabled: !!teamName,
    refetchInterval: 8_000,
  });

  const tasksQ = useQuery({
    queryKey: ['team-tasks', namespace, teamName],
    queryFn: () => listTeamTasks(teamName, namespace),
    enabled: !!teamName,
    refetchInterval: 5_000,
  });

  const messagesQ = useQuery({
    queryKey: ['team-messages', namespace, teamName],
    queryFn: () => listTeamMessages(teamName, 80, namespace),
    enabled: !!teamName && tab === 'messages',
    refetchInterval: 5_000,
  });

  const [after, setAfter] = useState('');
  const eventsQ = useQuery({
    queryKey: ['team-events', namespace, teamName, after],
    queryFn: () => listTeamEvents(teamName, after, 50, namespace),
    enabled: !!teamName,
    refetchInterval: 5_000,
  });

  const members = detail.data?.members || [];
  const tasks = tasksQ.data?.tasks || [];
  const buckets = useMemo(() => bucketTasks(tasks), [tasks]);
  const lead = members.find((m) => m.memberName === 'lead');

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ['team', namespace, teamName] });
    void qc.invalidateQueries({ queryKey: ['team-tasks', namespace, teamName] });
    void qc.invalidateQueries({ queryKey: ['team-messages', namespace, teamName] });
    void qc.invalidateQueries({ queryKey: ['teams'] });
  };

  const createTask = useMutation({
    mutationFn: () => createTeamTask(teamName, { subject: subject.trim() }, namespace),
    onSuccess: () => {
      setSubject('');
      invalidate();
    },
    onError: (e) => setError(errMsg(e)),
  });

  const completeMut = useMutation({
    mutationFn: () => completeTeam(teamName, namespace),
    onSuccess: () => navigate('/teams/list'),
    onError: (e) => setError(errMsg(e)),
  });

  const forceDeleteMut = useMutation({
    mutationFn: () => deleteTeam(teamName, namespace),
    onSuccess: () => navigate('/teams/list'),
    onError: (e) => setError(errMsg(e)),
  });

  if (detail.isLoading) {
    return (
      <Page>
        <p className="text-sm text-muted-foreground">Loading team…</p>
      </Page>
    );
  }

  if (detail.isError || !detail.data) {
    return (
      <Page>
        <p className="text-sm text-red-600">Team not found or store unavailable.</p>
        <Button className="mt-4" variant="outline" asChild>
          <Link to="/teams/list">Back</Link>
        </Button>
      </Page>
    );
  }

  const team = detail.data.team;
  const summary = detail.data.tasks;

  return (
    <Page className="max-w-7xl">
      <PageHeader
        title={team.name}
        description={team.objective}
        actions={
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" asChild>
              <Link to="/teams/list">Back</Link>
            </Button>
            <Button
              variant="destructive"
              disabled={completeMut.isPending}
              onClick={() => {
                if (window.confirm(`Complete team "${team.name}" (keeps state until TTL)?`)) {
                  completeMut.mutate();
                }
              }}
            >
              Complete team
            </Button>
            <Button
              variant="outline"
              disabled={forceDeleteMut.isPending}
              onClick={() => {
                if (window.confirm(`Force-delete team "${team.name}" immediately?`)) {
                  forceDeleteMut.mutate();
                }
              }}
            >
              Force delete
            </Button>
          </div>
        }
      />

      <div className="flex flex-wrap items-center gap-3">
        <Badge tone={teamPhaseTone(team.phase)}>{team.phase}</Badge>
        <span className="text-sm text-muted-foreground">ns={team.namespace}</span>
        <span className="text-sm text-muted-foreground">
          tasks {summary?.completed ?? 0}/{summary?.total ?? 0} complete ·{' '}
          {summary?.inProgress ?? 0} in progress · {summary?.pending ?? 0} pending
        </span>
      </div>

      {/* Topology */}
      <section className="rounded-xl border border-border bg-white p-5 shadow-sm">
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-muted-foreground">
          Topology
        </h2>
        <div className="flex flex-wrap items-stretch gap-3">
          {members.map((m) => (
            <MemberCard key={m.memberName} member={m} highlight={m.memberName === 'lead'} />
          ))}
          {members.length === 0 && (
            <p className="text-sm text-muted-foreground">No members registered.</p>
          )}
        </div>
      </section>

      <div className="flex gap-1 rounded-lg bg-slate-100 p-1 w-fit">
        {(
          [
            ['board', 'Task board'],
            ['members', 'Members'],
            ['messages', 'Messages'],
          ] as const
        ).map(([id, label]) => (
          <button
            key={id}
            className={
              tab === id
                ? 'rounded-md bg-white px-3 py-1.5 text-sm font-semibold shadow-sm'
                : 'rounded-md px-3 py-1.5 text-sm font-medium text-muted-foreground'
            }
            onClick={() => setTab(id)}
          >
            {label}
          </button>
        ))}
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}

      {tab === 'board' && (
        <section className="space-y-4">
          <form
            className="flex flex-wrap gap-2"
            onSubmit={(e: FormEvent) => {
              e.preventDefault();
              if (!subject.trim()) return;
              createTask.mutate();
            }}
          >
            <Input
              className="min-w-[16rem] flex-1"
              value={subject}
              onChange={(e) => setSubject(e.target.value)}
              placeholder="New task subject"
            />
            <Button type="submit" disabled={createTask.isPending}>
              Add task
            </Button>
          </form>

          <div className="grid gap-3 lg:grid-cols-5">
            {(
              [
                ['Unassigned', buckets.unassigned],
                ['Assigned', buckets.assigned],
                ['In progress', buckets.inProgress],
                ['Blocked', buckets.blocked],
                ['Completed', buckets.completed],
              ] as const
            ).map(([title, list]) => (
              <div
                key={title}
                className="flex min-h-[14rem] flex-col rounded-xl border border-border bg-muted/20"
              >
                <div className="border-b border-border px-3 py-2 text-sm font-semibold">
                  {title}{' '}
                  <span className="text-muted-foreground font-normal">({list.length})</span>
                </div>
                <ul className="flex-1 space-y-2 overflow-auto p-2">
                  {list.map((t) => (
                    <li
                      key={t.taskId}
                      className="rounded-lg border border-border bg-white p-3 text-sm shadow-sm"
                    >
                      <div className="font-medium">{t.subject}</div>
                      <div className="mt-1 font-mono text-[11px] text-muted-foreground">
                        {t.taskId}
                        {t.owner ? ` · ${t.owner}` : ''}
                        {t.blockedBy?.length ? ` · blockedBy=${t.blockedBy.join(',')}` : ''}
                      </div>
                      <div className="mt-2 flex flex-wrap gap-1">
                        {t.state === 'pending' && !t.owner && (
                          <>
                            <select
                              className="h-8 max-w-[7rem] rounded border border-border bg-white px-1 text-xs"
                              value={assignOwner[t.taskId] || ''}
                              onChange={(e) =>
                                setAssignOwner((m) => ({ ...m, [t.taskId]: e.target.value }))
                              }
                            >
                              <option value="">assign to…</option>
                              {members.map((m) => (
                                <option key={m.memberName} value={m.memberName}>
                                  {m.memberName}
                                </option>
                              ))}
                            </select>
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={async () => {
                                const owner = assignOwner[t.taskId];
                                if (!owner) return;
                                try {
                                  await assignTeamTask(
                                    teamName,
                                    t.taskId,
                                    owner,
                                    t.version,
                                    namespace,
                                  );
                                  invalidate();
                                } catch (e) {
                                  setError(errMsg(e));
                                }
                              }}
                            >
                              Assign
                            </Button>
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={async () => {
                                try {
                                  await claimTeamTask(
                                    teamName,
                                    t.taskId,
                                    lead?.memberName || 'lead',
                                    t.version,
                                    namespace,
                                  );
                                  invalidate();
                                } catch (e) {
                                  setError(errMsg(e));
                                }
                              }}
                            >
                              Claim
                            </Button>
                          </>
                        )}
                        {t.state === 'pending' && t.owner && (
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={async () => {
                              try {
                                await claimTeamTask(
                                  teamName,
                                  t.taskId,
                                  t.owner || '',
                                  t.version,
                                  namespace,
                                );
                                invalidate();
                              } catch (e) {
                                setError(errMsg(e));
                              }
                            }}
                          >
                            Start
                          </Button>
                        )}
                        {t.state === 'in_progress' && (
                          <>
                            <Button
                              size="sm"
                              onClick={async () => {
                                try {
                                  await completeTeamTask(teamName, t.taskId, 'done', namespace);
                                  invalidate();
                                } catch (e) {
                                  setError(errMsg(e));
                                }
                              }}
                            >
                              Complete
                            </Button>
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={async () => {
                                try {
                                  await unclaimTeamTask(teamName, t.taskId, t.version, namespace);
                                  invalidate();
                                } catch (e) {
                                  setError(errMsg(e));
                                }
                              }}
                            >
                              Unclaim
                            </Button>
                          </>
                        )}
                      </div>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </section>
      )}

      {tab === 'members' && (
        <section className="space-y-4">
          <form
            className="flex flex-wrap items-end gap-2 rounded-xl border border-border bg-white p-4 shadow-sm"
            onSubmit={async (e) => {
              e.preventDefault();
              if (!spawnName.trim() || !spawnRef.trim()) return;
              try {
                await spawnTeamMember(
                  teamName,
                  {
                    name: spawnName.trim(),
                    agentRef: spawnRef.trim(),
                    prompt: spawnPrompt.trim() || undefined,
                  },
                  namespace,
                );
                setSpawnName('');
                setSpawnRef('');
                setSpawnPrompt('');
                invalidate();
              } catch (err) {
                setError(errMsg(err));
              }
            }}
          >
            <div className="grid gap-1">
              <label className="text-xs text-muted-foreground">Spawn member</label>
              <Input
                value={spawnName}
                onChange={(e) => setSpawnName(e.target.value)}
                placeholder="name"
              />
            </div>
            <Input
              value={spawnRef}
              onChange={(e) => setSpawnRef(e.target.value)}
              placeholder="agentRef"
            />
            <Input
              value={spawnPrompt}
              onChange={(e) => setSpawnPrompt(e.target.value)}
              placeholder="prompt (optional)"
            />
            <Button type="submit">Spawn</Button>
          </form>

          <div className="overflow-hidden rounded-xl border border-border bg-white shadow-sm">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-border bg-muted/40 text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 font-medium">Member</th>
                  <th className="px-4 py-3 font-medium">Agent</th>
                  <th className="px-4 py-3 font-medium">Mode</th>
                  <th className="px-4 py-3 font-medium">Phase</th>
                  <th className="px-4 py-3 font-medium">Plan</th>
                  <th className="px-4 py-3 font-medium">Recovery</th>
                  <th className="px-4 py-3 font-medium">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {members.map((m) => {
                  const chat = chatSessionPath(m);
                  return (
                    <tr key={m.memberName}>
                      <td className="px-4 py-3 font-medium">{m.memberName}</td>
                      <td className="px-4 py-3 font-mono text-xs">{m.agentRef}</td>
                      <td className="px-4 py-3">{m.deployMode || 'byo'}</td>
                      <td className="px-4 py-3">
                        <Badge tone={teamPhaseTone(m.phase)}>{m.phase}</Badge>
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">
                        {m.planStatus || '—'}
                        {m.planText ? `: ${m.planText.slice(0, 40)}` : ''}
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">
                        {m.restartCount ? `n=${m.restartCount}` : '—'}
                        {m.lastRestartReason ? ` · ${m.lastRestartReason}` : ''}
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex flex-wrap gap-1">
                          {chat ? (
                            <Link className="text-primary hover:underline text-xs" to={chat}>
                              Chat
                            </Link>
                          ) : (
                            <span className="text-xs text-muted-foreground">Observe</span>
                          )}
                          {m.planStatus === 'pending' && (
                            <>
                              <Button
                                size="sm"
                                variant="outline"
                                onClick={async () => {
                                  try {
                                    await approveMemberPlan(teamName, m.memberName, namespace);
                                    invalidate();
                                  } catch (e) {
                                    setError(errMsg(e));
                                  }
                                }}
                              >
                                Approve
                              </Button>
                              <Button
                                size="sm"
                                variant="outline"
                                onClick={async () => {
                                  try {
                                    await rejectMemberPlan(teamName, m.memberName, namespace);
                                    invalidate();
                                  } catch (e) {
                                    setError(errMsg(e));
                                  }
                                }}
                              >
                                Reject
                              </Button>
                            </>
                          )}
                          {m.memberName !== 'lead' && m.phase !== 'Shutdown' && (
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={async () => {
                                if (!window.confirm(`Remove member ${m.memberName}?`)) return;
                                try {
                                  await removeTeamMember(teamName, m.memberName, namespace);
                                  invalidate();
                                } catch (e) {
                                  setError(errMsg(e));
                                }
                              }}
                            >
                              Remove
                            </Button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {tab === 'messages' && (
        <section className="grid gap-4 lg:grid-cols-2">
          <div className="rounded-xl border border-border bg-white p-4 shadow-sm">
            <h3 className="mb-3 text-sm font-semibold">Mailbox</h3>
            <ul className="mb-4 max-h-80 space-y-2 overflow-auto">
              {(messagesQ.data?.messages || []).map((m) => (
                <li key={m.id} className="rounded-lg border border-border px-3 py-2 text-sm">
                  <div className="text-xs text-muted-foreground">
                    {m.fromMember} → {m.toMember}
                  </div>
                  <div>{m.content}</div>
                </li>
              ))}
              {!messagesQ.data?.messages?.length && (
                <li className="text-sm text-muted-foreground">No messages yet.</li>
              )}
            </ul>
            <form
              className="grid gap-2"
              onSubmit={async (e) => {
                e.preventDefault();
                if (!msgBody.trim()) return;
                try {
                  await sendTeamMessage(
                    teamName,
                    {
                      from: lead?.memberName || 'lead',
                      to: msgTo.trim(),
                      content: msgBody.trim(),
                    },
                    namespace,
                  );
                  setMsgBody('');
                  void qc.invalidateQueries({
                    queryKey: ['team-messages', namespace, teamName],
                  });
                } catch (err) {
                  setError(errMsg(err));
                }
              }}
            >
              <select
                className="h-10 rounded-lg border border-border bg-white px-3 text-sm"
                value={msgTo}
                onChange={(e) => setMsgTo(e.target.value)}
              >
                <option value="">To member… (empty = broadcast)</option>
                {members.map((m) => (
                  <option key={m.memberName} value={m.memberName}>
                    {m.memberName}
                  </option>
                ))}
              </select>
              <Input
                value={msgBody}
                onChange={(e) => setMsgBody(e.target.value)}
                placeholder="Short message or artifact ref"
              />
              <Button type="submit">Send</Button>
            </form>
          </div>

          <div className="rounded-xl border border-border bg-white p-4 shadow-sm">
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-sm font-semibold">Events (poll)</h3>
              <Button
                size="sm"
                variant="outline"
                onClick={() => {
                  const next = eventsQ.data?.after;
                  if (next) setAfter(next);
                }}
              >
                Advance cursor
              </Button>
            </div>
            <ul className="max-h-[28rem] space-y-2 overflow-auto">
              {(eventsQ.data?.events || []).map((ev) => (
                <li key={ev.id} className="rounded-lg border border-border px-3 py-2 text-sm">
                  <div className="text-xs text-muted-foreground">
                    {ev.createdAt || ''} · {ev.fromMember} → {ev.toMember}
                  </div>
                  <div>{ev.content}</div>
                </li>
              ))}
              {!eventsQ.data?.events?.length && (
                <li className="text-sm text-muted-foreground">No events.</li>
              )}
            </ul>
          </div>
        </section>
      )}
    </Page>
  );
}

function MemberCard({ member, highlight }: { member: TeamMember; highlight?: boolean }) {
  const chat = chatSessionPath(member);
  return (
    <div
      className={
        highlight
          ? 'min-w-[10rem] rounded-xl border-2 border-primary/40 bg-white p-4 shadow-sm'
          : 'min-w-[10rem] rounded-xl border border-border bg-white p-4 shadow-sm'
      }
    >
      <div className="flex items-center justify-between gap-2">
        <div className="font-semibold">{member.memberName}</div>
        <Badge tone={teamPhaseTone(member.phase)}>{member.phase}</Badge>
      </div>
      <div className="mt-1 font-mono text-xs text-muted-foreground">{member.agentRef}</div>
      <div className="mt-2 text-xs text-muted-foreground">
        {member.deployMode || 'byo'}
        {member.restartCount ? ` · restarts ${member.restartCount}` : ''}
        {member.lastRestartReason ? ` · ${member.lastRestartReason}` : ''}
        {member.planStatus ? ` · plan ${member.planStatus}` : ''}
      </div>
      <div className="mt-3">
        {chat ? (
          <Link className="text-sm font-medium text-primary hover:underline" to={chat}>
            Open chat
          </Link>
        ) : (
          <span className="text-sm text-muted-foreground">Observe only</span>
        )}
      </div>
    </div>
  );
}

function errMsg(e: unknown) {
  if (e instanceof ApiError) return e.body || e.message;
  if (e instanceof Error) return e.message;
  return 'Request failed';
}
