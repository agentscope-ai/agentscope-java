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

import { useQuery } from "@tanstack/react-query";
import {
  ArrowRight,
  CircleDotDashed,
  Inbox,
  Plus,
  Users,
} from "lucide-react";
import { Link } from "react-router-dom";

import { getInboxSummary, listInbox, listIssues, listTeams } from "@/api/collaboration";
import { getRoles } from "@/api/auth";
import { useControlPlaneScope } from "@/app/ScopeContext";
import { Button } from "@/components/ui/button";
import { EntityIdentityText, useEntityIdentities } from "@/components/EntityIdentity";
import { formatRelative } from "@/lib/format";

import {
  WorkEmpty,
  WorkLoadingRows,
  WorkPage,
  WorkPageHeader,
  WorkPanel,
  WorkPanelHeader,
  WorkStatusBadge,
} from "./WorkSurface";

export default function WorkOverviewPage() {
  const scope = useControlPlaneScope();
  const roles = getRoles().map((role) => role.toLowerCase());
  const canUseAgentCenter = roles.includes("admin") || roles.includes("operator") || roles.includes("agent_developer");
  const issues = useQuery({
    queryKey: ["issues", scope.tenant, scope.namespace, "work-overview"],
    queryFn: () => listIssues(scope.tenant, scope.namespace),
    refetchInterval: 7500,
  });
  const teams = useQuery({
    queryKey: ["teams", scope.tenant, scope.namespace, "work-overview"],
    queryFn: () => listTeams(scope.tenant, scope.namespace),
    enabled: canUseAgentCenter,
  });
  const inbox = useQuery({
    queryKey: ["inbox", scope.tenant, scope.namespace, "work-overview"],
    queryFn: () => listInbox(scope.tenant, scope.namespace, { view: "attention", limit: 6 }),
    refetchInterval: 7500,
  });
  const inboxSummary = useQuery({ queryKey: ["inbox-summary", scope.tenant, scope.namespace], queryFn: () => getInboxSummary(scope.tenant, scope.namespace), refetchInterval: 7500 });
  const attentionCount = inboxSummary.data?.summary.attentionTotal ?? 0;
  const issueItems = issues.data?.items || [];
  const unreadItems = (inbox.data?.items || []).filter((item) => !item.read || item.needsAction);
  const identities = useEntityIdentities([
    ...issueItems.map((issue) => ({ type: issue.assigneeType, ref: issue.assigneeRef })),
    ...unreadItems.map((item) => ({ type: item.actor.type, ref: item.actor.ref })),
  ]);
  const cards = [
    {
      label: "Open issues",
      value: issueItems.filter((issue) => !["done", "cancelled"].includes(issue.status)).length,
      loading: issues.isLoading,
      to: "/work/issues",
      icon: CircleDotDashed,
    },
    ...(canUseAgentCenter ? [{
      label: "Teams",
      value: teams.data?.items.length || 0,
      loading: teams.isLoading,
      to: "/agent-center/teams",
      icon: Users,
    }] : []),
    {
      label: "Needs attention",
      value: attentionCount,
      loading: inbox.isLoading,
      to: "/work/inbox",
      icon: Inbox,
    },
  ];

  return (
    <WorkPage>
      <WorkPageHeader
        title="Overview"
        description="Track durable work, agent execution, and the decisions that need your attention."
        actions={
          <Button asChild>
            <Link to={scope.scopedPath("/work/issues?new=1")}>
              <Plus className="h-4 w-4" /> New issue
            </Link>
          </Button>
        }
      />

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-3">
        {cards.map((card) => {
          const Icon = card.icon;
          return (
            <Link
              key={card.label}
              to={scope.scopedPath(card.to)}
              className="group rounded-2xl border border-slate-200/90 bg-white px-4 py-4 transition hover:-translate-y-0.5 hover:border-slate-300 hover:shadow-md sm:px-5 sm:py-5"
            >
              <div className="flex items-center justify-between gap-3">
                <span className="text-[13px] font-medium text-slate-500">{card.label}</span>
                <span className="rounded-lg bg-slate-50 p-2 text-slate-500 transition group-hover:bg-indigo-50 group-hover:text-indigo-600">
                  <Icon className="h-4 w-4" />
                </span>
              </div>
              <div className="mt-3 text-2xl font-semibold tracking-tight text-slate-950 sm:text-[28px]">
                {card.loading ? "—" : card.value}
              </div>
            </Link>
          );
        })}
      </div>

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1.7fr)_minmax(320px,0.8fr)]">
        <WorkPanel>
          <WorkPanelHeader
            title="Recent issues"
            description={scope.selectorVisible ? "The latest work in this namespace" : "The latest work"}
            action={
              <Button variant="ghost" size="sm" asChild>
                <Link to={scope.scopedPath("/work/issues")}>
                  View all <ArrowRight className="h-4 w-4" />
                </Link>
              </Button>
            }
          />
          {issues.isLoading ? (
            <WorkLoadingRows />
          ) : issueItems.length ? (
            <div className="divide-y divide-slate-100">
              {issueItems.slice(0, 8).map((issue) => (
                <Link
                  key={issue.id}
                  to={scope.scopedPath(`/work/issues/${issue.id}`)}
                  className="group flex items-center gap-4 px-5 py-4 transition hover:bg-slate-50/80"
                >
                  <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-400">
                    <CircleDotDashed className="h-4 w-4" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-medium text-slate-900 group-hover:text-indigo-700">
                      {issue.title}
                    </div>
                    <div className="mt-1 flex items-center gap-2 text-xs text-slate-500">
                      <span className="font-mono">{issue.identifier || issue.id.slice(0, 8)}</span>
                      <span aria-hidden="true">·</span>
                      {issue.assigneeRef ? <EntityIdentityText identities={identities} type={issue.assigneeType} entityRef={issue.assigneeRef} /> : <span>Unassigned</span>}
                    </div>
                  </div>
                  <span className="hidden text-xs text-slate-400 sm:block">{formatRelative(issue.updatedAt)}</span>
                  <WorkStatusBadge status={issue.status} />
                </Link>
              ))}
            </div>
          ) : (
            <WorkEmpty
              title="No issues yet"
              description="Create the first issue to start a durable work thread."
              action={
                <Button size="sm" asChild>
                  <Link to={scope.scopedPath("/work/issues?new=1")}>Create issue</Link>
                </Button>
              }
            />
          )}
        </WorkPanel>

        <WorkPanel>
          <WorkPanelHeader
            title="Needs attention"
            description={attentionCount ? `${attentionCount} messages need attention` : "You are all caught up"}
            action={
              <Button variant="ghost" size="sm" asChild>
                <Link to={scope.scopedPath("/work/inbox")}>Open inbox</Link>
              </Button>
            }
          />
          {inbox.isLoading ? (
            <WorkLoadingRows rows={3} />
          ) : unreadItems.length ? (
            <div className="divide-y divide-slate-100">
              {unreadItems.slice(0, 6).map((item) => (
                <Link
                  key={item.id}
                  to={scope.scopedPath(`/work/inbox?item=${encodeURIComponent(item.id)}`)}
                  className="flex gap-3 px-5 py-4 transition hover:bg-slate-50/80"
                >
                  <span className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-indigo-500" />
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-medium text-slate-900">{item.title}</div>
                    <p className="mt-1 line-clamp-2 text-xs leading-5 text-slate-500">{item.body || item.type}</p>
                    <div className="mt-1.5 text-[11px] text-slate-400">{formatRelative(item.createdAt)}</div>
                  </div>
                </Link>
              ))}
            </div>
          ) : (
            <WorkEmpty
              className="min-h-64"
              title="Nothing needs attention"
              description="Mentions, blocked work, and approval requests will appear here."
            />
          )}
        </WorkPanel>
      </div>
    </WorkPage>
  );
}
