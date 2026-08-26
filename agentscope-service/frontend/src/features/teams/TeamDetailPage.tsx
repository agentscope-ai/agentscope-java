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

import { FormEvent, useMemo, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  approveMemberPlan,
  assignTeamTask,
  claimTeamTask,
  completeTeam,
  completeTeamTask,
  createTeamTask,
  deleteTeam,
  getTeam,
  listTeamEvents,
  listTeamMessages,
  listTeamTasks,
  managedChatSessionId,
  rejectMemberPlan,
  removeTeamMember,
  sendTeamMessage,
  spawnTeamMember,
  teamPhaseTone,
  unclaimTeamTask,
  type TeamMember,
  type TeamTask,
} from '@/api/teams';
import ChatPanel from '@/components/ChatPanel';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Page, PageHeader } from '@/components/Page';
import { ApiError } from '@/lib/apiClient';
import { cn } from '@/lib/utils';
import { useI18n, type TranslationFunction } from '@/i18n';
import {
  deployModeLabel,
  formatTeamDate,
  formatTeamNumber,
  memberPhaseLabel,
  planStatusLabel,
  teamPhaseLabel,
} from './i18n';

type Tab = 'board' | 'members' | 'messages';

function bucketTasks(tasks: TeamTask[]) {
  const unassigned: TeamTask[] = [];
  const assigned: TeamTask[] = [];
  const inProgress: TeamTask[] = [];
  const blocked: TeamTask[] = [];
  const completed: TeamTask[] = [];
  const failed: TeamTask[] = [];
  for (const t of tasks) {
    if (t.state === 'completed') {
      completed.push(t);
      continue;
    }
    if (t.state === 'failed') {
      failed.push(t);
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
  return { unassigned, assigned, inProgress, blocked, completed, failed };
}

export default function TeamDetailPage() {
  const { locale, t: tr } = useI18n();
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
  const [chatMember, setChatMember] = useState<TeamMember | null>(null);

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
  const tasks = useMemo(() => tasksQ.data?.tasks || [], [tasksQ.data?.tasks]);
  const buckets = useMemo(() => bucketTasks(tasks), [tasks]);
  const lead = members.find((m) => m.memberName === 'lead');
  const chatSessionId = chatMember ? managedChatSessionId(chatMember) : null;

  const openMemberChat = (m: TeamMember) => {
    if (!managedChatSessionId(m)) return;
    setChatMember(m);
  };

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
    onError: (e) => setError(errMsg(e, tr)),
  });

  const completeMut = useMutation({
    mutationFn: () => completeTeam(teamName, namespace),
    onSuccess: () => navigate('/teams/list'),
    onError: (e) => setError(errMsg(e, tr)),
  });

  const forceDeleteMut = useMutation({
    mutationFn: () => deleteTeam(teamName, namespace),
    onSuccess: () => navigate('/teams/list'),
    onError: (e) => setError(errMsg(e, tr)),
  });

  if (detail.isLoading) {
    return (
      <Page>
        <p className="text-sm text-muted-foreground">{tr('teams.detail.loading')}</p>
      </Page>
    );
  }

  if (detail.isError || !detail.data) {
    return (
      <Page>
        <p className="text-sm text-red-600">{tr('teams.detail.notFound')}</p>
        <Button className="mt-4" variant="outline" asChild>
          <Link to="/teams/list">{tr('common.back')}</Link>
        </Button>
      </Page>
    );
  }

  const team = detail.data.team;
  const summary = detail.data.tasks;
  const readOnly = (team.phase || '').toLowerCase() === 'completed';

  return (
    <Page className={cn(chatMember ? 'max-w-none' : 'max-w-7xl')}>
      <div
        className={cn(
          chatMember &&
            'lg:grid lg:grid-cols-[minmax(0,1fr)_minmax(360px,440px)] lg:items-start lg:gap-6',
        )}
      >
        <div className="min-w-0 space-y-8">
      <PageHeader
        title={team.name}
        description={team.objective}
        actions={
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" asChild>
              <Link to="/teams/list">{tr('common.back')}</Link>
            </Button>
            <Button
              variant={readOnly ? 'secondary' : 'destructive'}
              disabled={readOnly || completeMut.isPending}
              title={readOnly ? tr('teams.detail.alreadyCompleted') : undefined}
              onClick={() => {
                if (readOnly) return;
                if (window.confirm(tr('teams.detail.completeConfirm', { name: team.name }))) {
                  completeMut.mutate();
                }
              }}
            >
              {readOnly ? tr('teams.status.completed') : tr('teams.detail.completeTeam')}
            </Button>
            <Button
              variant="outline"
              disabled={forceDeleteMut.isPending}
              onClick={() => {
                if (window.confirm(tr('teams.detail.forceDeleteConfirm', { name: team.name }))) {
                  forceDeleteMut.mutate();
                }
              }}
            >
              {tr('teams.detail.forceDelete')}
            </Button>
          </div>
        }
      />

      <div className="flex flex-wrap items-center gap-3">
        <Badge tone={teamPhaseTone(team.phase)}>{teamPhaseLabel(tr, team.phase)}</Badge>
        <span className="text-sm text-muted-foreground">ns={team.namespace}</span>
        <span className="text-sm text-muted-foreground">
          {tr('teams.detail.taskSummary', {
            completed: formatTeamNumber(locale, summary?.completed ?? 0),
            total: formatTeamNumber(locale, summary?.total ?? 0),
            inProgress: formatTeamNumber(locale, summary?.inProgress ?? 0),
            pending: formatTeamNumber(locale, summary?.pending ?? 0),
          })}
        </span>
        {readOnly && (
          <span className="text-sm font-medium text-muted-foreground">
            {tr('teams.detail.readOnly')}
          </span>
        )}
      </div>

      {/* Topology */}
      <section className="rounded-xl border border-border bg-white p-5 shadow-sm">
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-muted-foreground">
          {tr('teams.detail.topology')}
        </h2>
        <div className="flex flex-wrap items-stretch gap-3">
          {members.map((m) => (
            <MemberCard
              key={m.memberName}
              member={m}
              highlight={m.memberName === 'lead'}
              active={chatMember?.memberName === m.memberName}
              readOnly={readOnly}
              onOpenChat={() => openMemberChat(m)}
            />
          ))}
          {members.length === 0 && (
            <p className="text-sm text-muted-foreground">{tr('teams.detail.noMembers')}</p>
          )}
        </div>
      </section>

      <div className="flex gap-1 rounded-lg bg-slate-100 p-1 w-fit">
        {(
          [
            ['board', tr('teams.detail.tab.board')],
            ['members', tr('teams.detail.tab.members')],
            ['messages', tr('teams.detail.tab.messages')],
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
              if (readOnly || !subject.trim()) return;
              createTask.mutate();
            }}
          >
            <Input
              className="min-w-[16rem] flex-1"
              value={subject}
              onChange={(e) => setSubject(e.target.value)}
              placeholder={tr('teams.detail.newTaskPlaceholder')}
              disabled={readOnly}
            />
            <Button type="submit" disabled={readOnly || createTask.isPending}>
              {tr('teams.detail.addTask')}
            </Button>
          </form>

          <div className="grid gap-3 lg:grid-cols-5">
            {(
              [
                [tr('teams.detail.bucket.unassigned'), buckets.unassigned],
                [tr('teams.detail.bucket.assigned'), buckets.assigned],
                [tr('teams.detail.bucket.inProgress'), buckets.inProgress],
                [tr('teams.detail.bucket.blocked'), buckets.blocked],
                [tr('teams.detail.bucket.completed'), buckets.completed],
                [tr('teams.detail.bucket.failed'), buckets.failed],
              ] as const
            ).map(([title, list]) => (
              <div
                key={title}
                className="flex min-h-[14rem] flex-col rounded-xl border border-border bg-muted/20"
              >
                <div className="border-b border-border px-3 py-2 text-sm font-semibold">
                  {title}{' '}
                  <span className="text-muted-foreground font-normal">
                    ({formatTeamNumber(locale, list.length)})
                  </span>
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
                        {!readOnly && t.state === 'pending' && !t.owner && (
                          <>
                            <select
                              className="h-8 max-w-[7rem] rounded border border-border bg-white px-1 text-xs"
                              value={assignOwner[t.taskId] || ''}
                              onChange={(e) =>
                                setAssignOwner((m) => ({ ...m, [t.taskId]: e.target.value }))
                              }
                            >
                              <option value="">{tr('teams.detail.assignTo')}</option>
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
                                  setError(errMsg(e, tr));
                                }
                              }}
                            >
                              {tr('teams.detail.assign')}
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
                                  setError(errMsg(e, tr));
                                }
                              }}
                            >
                              {tr('teams.detail.claim')}
                            </Button>
                          </>
                        )}
                        {!readOnly && t.state === 'pending' && t.owner && (
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
                                setError(errMsg(e, tr));
                              }
                            }}
                          >
                            {tr('teams.detail.start')}
                          </Button>
                        )}
                        {!readOnly && t.state === 'in_progress' && (
                          <>
                            <Button
                              size="sm"
                              onClick={async () => {
                                try {
                                  await completeTeamTask(teamName, t.taskId, 'done', namespace);
                                  invalidate();
                                } catch (e) {
                                  setError(errMsg(e, tr));
                                }
                              }}
                            >
                              {tr('teams.detail.complete')}
                            </Button>
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={async () => {
                                try {
                                  await unclaimTeamTask(teamName, t.taskId, t.version, namespace);
                                  invalidate();
                                } catch (e) {
                                  setError(errMsg(e, tr));
                                }
                              }}
                            >
                              {tr('teams.detail.unclaim')}
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
              if (readOnly || !spawnName.trim() || !spawnRef.trim()) return;
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
                setError(errMsg(err, tr));
              }
            }}
          >
            <div className="grid gap-1">
              <label className="text-xs text-muted-foreground">{tr('teams.detail.spawnMember')}</label>
              <Input
                value={spawnName}
                onChange={(e) => setSpawnName(e.target.value)}
                placeholder={tr('teams.detail.namePlaceholder')}
                disabled={readOnly}
              />
            </div>
            <Input
              value={spawnRef}
              onChange={(e) => setSpawnRef(e.target.value)}
              placeholder="agentRef"
              disabled={readOnly}
            />
            <Input
              value={spawnPrompt}
              onChange={(e) => setSpawnPrompt(e.target.value)}
              placeholder={tr('teams.detail.promptPlaceholder')}
              disabled={readOnly}
            />
            <Button type="submit" disabled={readOnly}>
              {tr('teams.detail.spawn')}
            </Button>
          </form>

          <div className="overflow-hidden rounded-xl border border-border bg-white shadow-sm">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-border bg-muted/40 text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 font-medium">{tr('teams.member')}</th>
                  <th className="px-4 py-3 font-medium">{tr('teams.agent')}</th>
                  <th className="px-4 py-3 font-medium">{tr('teams.mode')}</th>
                  <th className="px-4 py-3 font-medium">{tr('teams.phase')}</th>
                  <th className="px-4 py-3 font-medium">{tr('teams.plan')}</th>
                  <th className="px-4 py-3 font-medium">{tr('teams.recovery')}</th>
                  <th className="px-4 py-3 font-medium">{tr('teams.actions')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {members.map((m) => {
                  const canChat = !!managedChatSessionId(m);
                  return (
                    <tr key={m.memberName}>
                      <td className="px-4 py-3 font-medium">{m.memberName}</td>
                      <td className="px-4 py-3 font-mono text-xs">{m.agentRef}</td>
                      <td className="px-4 py-3">{deployModeLabel(tr, m.deployMode)}</td>
                      <td className="px-4 py-3">
                        <Badge tone={teamPhaseTone(m.phase)}>{memberPhaseLabel(tr, m.phase)}</Badge>
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">
                        {planStatusLabel(tr, m.planStatus)}
                        {m.planText ? `: ${m.planText.slice(0, 40)}` : ''}
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">
                        {m.restartCount ? `n=${m.restartCount}` : '—'}
                        {m.lastRestartReason ? ` · ${m.lastRestartReason}` : ''}
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex flex-wrap gap-1">
                          {canChat ? (
                            <button
                              type="button"
                              className={
                                readOnly
                                  ? 'text-xs font-medium text-muted-foreground'
                                  : 'text-xs font-medium text-primary hover:underline'
                              }
                              onClick={() => openMemberChat(m)}
                            >
                              {readOnly ? tr('teams.detail.viewChat') : tr('teams.detail.chat')}
                            </button>
                          ) : (
                            <span className="text-xs text-muted-foreground">{tr('teams.detail.observe')}</span>
                          )}
                          {!readOnly && m.planStatus === 'pending' && (
                            <>
                              <Button
                                size="sm"
                                variant="outline"
                                onClick={async () => {
                                  try {
                                    await approveMemberPlan(teamName, m.memberName, namespace);
                                    invalidate();
                                  } catch (e) {
                                    setError(errMsg(e, tr));
                                  }
                                }}
                              >
                                {tr('teams.detail.approve')}
                              </Button>
                              <Button
                                size="sm"
                                variant="outline"
                                onClick={async () => {
                                  try {
                                    await rejectMemberPlan(teamName, m.memberName, namespace);
                                    invalidate();
                                  } catch (e) {
                                    setError(errMsg(e, tr));
                                  }
                                }}
                              >
                                {tr('teams.detail.reject')}
                              </Button>
                            </>
                          )}
                          {!readOnly && m.memberName !== 'lead' && m.phase !== 'Shutdown' && (
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={async () => {
                                if (!window.confirm(tr('teams.detail.removeMemberConfirm', { name: m.memberName }))) return;
                                try {
                                  await removeTeamMember(teamName, m.memberName, namespace);
                                  invalidate();
                                } catch (e) {
                                  setError(errMsg(e, tr));
                                }
                              }}
                            >
                              {tr('teams.detail.remove')}
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
            <h3 className="mb-3 text-sm font-semibold">{tr('teams.detail.mailbox')}</h3>
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
                <li className="text-sm text-muted-foreground">{tr('teams.detail.noMessages')}</li>
              )}
            </ul>
            <form
              className="grid gap-2"
              onSubmit={async (e) => {
                e.preventDefault();
                if (readOnly || !msgBody.trim()) return;
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
                  setError(errMsg(err, tr));
                }
              }}
            >
              <select
                className="h-10 rounded-lg border border-border bg-white px-3 text-sm disabled:cursor-not-allowed disabled:opacity-50"
                value={msgTo}
                onChange={(e) => setMsgTo(e.target.value)}
                disabled={readOnly}
              >
                <option value="">{tr('teams.detail.toMember')}</option>
                {members.map((m) => (
                  <option key={m.memberName} value={m.memberName}>
                    {m.memberName}
                  </option>
                ))}
              </select>
              <Input
                value={msgBody}
                onChange={(e) => setMsgBody(e.target.value)}
                placeholder={tr('teams.detail.messagePlaceholder')}
                disabled={readOnly}
              />
              <Button type="submit" disabled={readOnly}>
                {tr('teams.detail.send')}
              </Button>
            </form>
          </div>

          <div className="rounded-xl border border-border bg-white p-4 shadow-sm">
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-sm font-semibold">{tr('teams.detail.events')}</h3>
              <Button
                size="sm"
                variant="outline"
                onClick={() => {
                  const next = eventsQ.data?.after;
                  if (next) setAfter(next);
                }}
              >
                {tr('teams.detail.advanceCursor')}
              </Button>
            </div>
            <ul className="max-h-[28rem] space-y-2 overflow-auto">
              {(eventsQ.data?.events || []).map((ev) => (
                <li key={ev.id} className="rounded-lg border border-border px-3 py-2 text-sm">
                  <div className="text-xs text-muted-foreground">
                    {formatTeamDate(locale, ev.createdAt)} · {ev.fromMember} → {ev.toMember}
                  </div>
                  <div>{ev.content}</div>
                </li>
              ))}
              {!eventsQ.data?.events?.length && (
                <li className="text-sm text-muted-foreground">{tr('teams.detail.noEvents')}</li>
              )}
            </ul>
          </div>
        </section>
      )}
        </div>

        {chatMember && chatSessionId && (
          <aside className="mt-8 flex h-[min(85vh,820px)] flex-col overflow-hidden rounded-xl border border-border bg-white shadow-sm lg:sticky lg:top-4 lg:mt-0">
            <div className="flex shrink-0 items-center justify-between gap-2 border-b border-border px-3 py-2.5">
              <div className="min-w-0">
                <div className="truncate text-sm font-semibold">{chatMember.memberName}</div>
                <div className="truncate font-mono text-[11px] text-muted-foreground">
                  {chatMember.agentRef} · {chatSessionId}
                </div>
              </div>
              <Button
                type="button"
                size="sm"
                variant="outline"
                onClick={() => setChatMember(null)}
              >
                {tr('teams.detail.close')}
              </Button>
            </div>
            <div className="min-h-0 flex-1">
              <ChatPanel
                key={chatSessionId}
                sessionId={chatSessionId}
                agentId={chatMember.managedAgentId || ''}
                embedded
                readOnly={readOnly}
              />
            </div>
          </aside>
        )}
      </div>
    </Page>
  );
}

function MemberCard({
  member,
  highlight,
  active,
  readOnly,
  onOpenChat,
}: {
  member: TeamMember;
  highlight?: boolean;
  active?: boolean;
  readOnly?: boolean;
  onOpenChat: () => void;
}) {
  const { locale, t } = useI18n();
  const canChat = !!managedChatSessionId(member);
  return (
    <div
      className={
        active
          ? 'min-w-[10rem] rounded-xl border-2 border-primary bg-primary/5 p-4 shadow-sm'
          : highlight
            ? 'min-w-[10rem] rounded-xl border-2 border-primary/40 bg-white p-4 shadow-sm'
            : 'min-w-[10rem] rounded-xl border border-border bg-white p-4 shadow-sm'
      }
    >
      <div className="flex items-center justify-between gap-2">
        <div className="font-semibold">{member.memberName}</div>
        <Badge tone={teamPhaseTone(member.phase)}>{memberPhaseLabel(t, member.phase)}</Badge>
      </div>
      <div className="mt-1 font-mono text-xs text-muted-foreground">{member.agentRef}</div>
      <div className="mt-2 text-xs text-muted-foreground">
        {deployModeLabel(t, member.deployMode)}
        {member.restartCount
          ? ` · ${t('teams.detail.restarts', { count: formatTeamNumber(locale, member.restartCount) })}`
          : ''}
        {member.lastRestartReason ? ` · ${member.lastRestartReason}` : ''}
        {member.planStatus ? ` · ${t('teams.detail.planStatus', { status: planStatusLabel(t, member.planStatus) })}` : ''}
      </div>
      <div className="mt-3">
        {canChat ? (
          <button
            type="button"
            className={
              readOnly
                ? 'text-sm font-medium text-muted-foreground hover:underline'
                : 'text-sm font-medium text-primary hover:underline'
            }
            onClick={onOpenChat}
          >
            {active
              ? readOnly
                ? t('teams.detail.viewingChat')
                : t('teams.detail.chatOpen')
              : readOnly
                ? t('teams.detail.viewChat')
                : t('teams.detail.openChat')}
          </button>
        ) : (
          <span className="text-sm text-muted-foreground">{t('teams.detail.observeOnly')}</span>
        )}
      </div>
    </div>
  );
}

function errMsg(e: unknown, t: TranslationFunction) {
  if (e instanceof ApiError) return e.body || e.message;
  if (e instanceof Error) return e.message;
  return t('common.requestFailed');
}
