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

import { useSearchParams } from "react-router-dom";
import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { ArrowDown, GitBranch } from "lucide-react";
import { updateTeam, type Team, type TeamOverview } from "@/api/collaboration";
import { AgentIdentity, AgentPicker } from "@/components/AgentPicker";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  CardDescription,
} from "@/components/ui/card";
import { Input, Textarea } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { MembersEditor } from "./TeamMembersEditor";
import { teamTone } from "./teamActivity";

const limits = [
  [
    "maxActiveTasks",
    "Concurrent tasks",
    "0 uses the default of 32.",
    undefined,
  ],
  ["maxHops", "Delegation hops", "0 uses the default of 8.", 64],
  [
    "maxFanout",
    "Recipients per delegation",
    "0 adds no Team-specific limit.",
    256,
  ],
  ["maxChildDepth", "Child Issue depth", "0 uses the default of 4.", 32],
  [
    "maxChildIssues",
    "Child Issues per parent",
    "0 adds no Team-specific limit.",
    undefined,
  ],
  [
    "maxTaskRetries",
    "Retries per task",
    "0 adds no Team-specific limit.",
    undefined,
  ],
] as const;
function patchTeam(team: Team, fields: Record<string, unknown>) {
  return updateTeam(team.id, {
    name: team.name,
    description: team.description || "",
    instructions: team.instructions || "",
    leaderAgentId: team.leaderAgentId,
    policy: team.policy || {},
    status: team.status,
    expectedVersion: team.version,
    ...fields,
  });
}
export function TeamStructure({
  team,
  overview,
  compact = false,
}: {
  team: Team;
  overview: TeamOverview;
  compact?: boolean;
}) {
  const [selected, setSelected] = useState(team.leaderAgentId);
  const member = overview.members.find((item) => item.agentId === selected);
  const definition = team.members?.find((item) => item.agentId === selected);
  return (
    <Card>
      <CardHeader>
        <CardTitle>Team structure</CardTitle>
        <CardDescription>
          {compact
            ? `${overview.members.length} Agents · Lead coordinates and accepts worker results`
            : "Configured roles and current runtime readiness. The Lead chooses whom to involve for each request."}
        </CardDescription>
      </CardHeader>
      <CardContent>
        <div className="rounded-xl border border-border bg-slate-50 p-4">
          <button
            onClick={() => setSelected(team.leaderAgentId)}
            className={`mx-auto block w-full max-w-72 rounded-lg border border-border bg-white p-3 text-left shadow-sm ${selected === team.leaderAgentId ? "border-primary" : ""}`}
          >
            <div className="text-xs font-medium text-primary">LEAD</div>
            <div className="mt-1 font-medium">
              <AgentIdentity agentId={team.leaderAgentId} showId={false} />
            </div>
          </button>
          {!!team.members?.length && (
            <>
              <div className="flex flex-col items-center py-2 text-xs text-muted-foreground">
                <ArrowDown className="h-5 w-5" />
                <span>Delegate · receive results</span>
              </div>
              <div
                className={`grid gap-2 ${compact ? "" : "sm:grid-cols-2 lg:grid-cols-3"}`}
              >
                {team.members.map((item) => {
                  const readiness =
                    overview.members.find((m) => m.agentId === item.agentId)
                      ?.readiness.state || "unknown";
                  return (
                    <button
                      key={item.id}
                      onClick={() => setSelected(item.agentId)}
                      className={`rounded-lg border border-border bg-white p-3 text-left shadow-sm ${selected === item.agentId ? "border-primary" : ""}`}
                    >
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <span className="text-xs font-medium text-muted-foreground">
                          {item.role}
                        </span>
                        <Badge tone={teamTone(readiness)}>{readiness}</Badge>
                      </div>
                      <div className="mt-1 text-sm">
                        <AgentIdentity agentId={item.agentId} showId={false} />
                      </div>
                    </button>
                  );
                })}
              </div>
            </>
          )}
        </div>
        {member && (
          <div className="mt-4 text-sm">
            <div className="flex items-center justify-between gap-2">
              <span className="font-medium">
                {member.leader ? "Lead" : member.role}
              </span>
              <Badge tone={teamTone(member.readiness.state)}>
                {member.readiness.state}
              </Badge>
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
              {member.readiness.reason}
            </p>
            {!compact && (
              <p className="mt-3 whitespace-pre-wrap">
                {member.leader
                  ? "Coordinates requests, delegates work, reviews results and submits the final response."
                  : definition?.instructions ||
                    "No role-specific instructions configured."}
              </p>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
export function TeamOrchestration({
  team,
  overview,
  canEdit,
  onSaved,
}: {
  team: Team;
  overview: TeamOverview;
  canEdit: boolean;
  onSaved: () => void;
}) {
  const [params, setParams] = useSearchParams();
  const section = ["rules", "limits"].includes(params.get("section") || "")
    ? params.get("section")!
    : "roles";
  const setSection = (value: string) => {
    const next = new URLSearchParams(params);
    next.set("section", value);
    setParams(next);
  };
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex gap-1 rounded-lg bg-muted p-1">
          {[
            ["roles", "Roles & members"],
            ["rules", "Coordination rules"],
            ["limits", "Execution limits"],
          ].map(([key, label]) => (
            <Button
              key={key}
              size="sm"
              variant={section === key ? "secondary" : "ghost"}
              aria-pressed={section === key}
              onClick={() => setSection(key)}
            >
              {label}
            </Button>
          ))}
        </div>
        <p className="flex items-center gap-2 text-xs text-muted-foreground">
          <GitBranch className="h-3 w-3" />
          Changes apply to new collaborations.
        </p>
      </div>
      {section === "roles" && (
        <div className="grid items-start gap-5 xl:grid-cols-[0.8fr_1.2fr]">
          <TeamStructure team={team} overview={overview} />
          <div className="space-y-5">
            <LeaderEditor
              key={team.version}
              team={team}
              canEdit={canEdit}
              onSaved={onSaved}
            />
            <div>
              <h3 className="mb-3 font-semibold">
                Workers · {team.members?.length || 0}
              </h3>
              <MembersEditor
                teamId={team.id}
                teamVersion={team.version}
                leaderAgentId={team.leaderAgentId}
                members={team.members || []}
                canEdit={canEdit}
              />
            </div>
          </div>
        </div>
      )}
      <div className={section === "roles" ? "hidden" : ""}>
        <RulesEditor
          key={team.version}
          team={team}
          canEdit={canEdit}
          section={section}
          onSaved={onSaved}
        />
      </div>
    </div>
  );
}
function LeaderEditor({
  team,
  canEdit,
  onSaved,
}: {
  team: Team;
  canEdit: boolean;
  onSaved: () => void;
}) {
  const [leader, setLeader] = useState(team.leaderAgentId);
  const save = useMutation({
    mutationFn: () => patchTeam(team, { leaderAgentId: leader }),
    onSuccess: onSaved,
  });
  return (
    <Card>
      <CardHeader>
        <CardTitle>Lead Agent</CardTitle>
        <CardDescription>
          The entry point for new requests and owner of the final response.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault();
            save.mutate();
          }}
        >
          <AgentPicker
            value={leader}
            onChange={setLeader}
            disabled={!canEdit}
            excludeIds={(team.members || []).map((member) => member.agentId)}
            required
            aria-label="Team lead Agent"
          />
          {canEdit && (
            <Button
              type="submit"
              size="sm"
              disabled={save.isPending || leader === team.leaderAgentId}
            >
              Save Lead
            </Button>
          )}
          {save.error && (
            <p role="alert" className="text-sm text-destructive">
              {String(save.error)}
            </p>
          )}
        </form>
      </CardContent>
    </Card>
  );
}
function RulesEditor({
  team,
  canEdit,
  section,
  onSaved,
}: {
  team: Team;
  canEdit: boolean;
  section: string;
  onSaved: () => void;
}) {
  const [instructions, setInstructions] = useState(team.instructions || "");
  const [policy, setPolicy] = useState<Record<string, unknown>>(
    (team.policy || {}) as Record<string, unknown>,
  );
  const save = useMutation({
    mutationFn: () => patchTeam(team, { instructions, policy }),
    onSuccess: onSaved,
  });
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          {section === "limits" ? "Execution limits" : "Coordination rules"}
        </CardTitle>
        <CardDescription>
          Existing collaborations continue with their saved Team configuration.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form
          className="space-y-5"
          onSubmit={(e) => {
            e.preventDefault();
            save.mutate();
          }}
        >
          <fieldset disabled={!canEdit || save.isPending} className="space-y-5">
            <div className={section === "rules" ? "space-y-5" : "hidden"}>
              <label className="block text-sm font-medium">
                Team instructions
                <Textarea
                  className="mt-2 min-h-60"
                  value={instructions}
                  onChange={(e) => setInstructions(e.target.value)}
                  placeholder="Explain how the Lead assigns work, accepts results, handles failures and submits the final response."
                />
              </label>
              <div className="grid gap-4">
                {[
                  [
                    "allowExternalDelegation",
                    "Allow delegation outside this Team",
                    "The Lead can involve Agents or Teams outside the configured roster.",
                  ],
                  [
                    "allowMentionAll",
                    "Allow delegation to all members",
                    "Allow a request to address the entire roster.",
                  ],
                  [
                    "requireReview",
                    "Require human review",
                    "Require review according to the execution policy.",
                  ],
                ].map(([field, label, help]) => (
                  <label
                    key={field}
                    className="flex items-start gap-3 rounded-lg border border-border p-3"
                  >
                    <input
                      className="mt-1"
                      type="checkbox"
                      checked={Boolean(policy[field])}
                      onChange={(e) =>
                        setPolicy((p) => ({ ...p, [field]: e.target.checked }))
                      }
                    />
                    <span>
                      <span className="text-sm font-medium">{label}</span>
                      <span className="mt-1 block text-xs text-muted-foreground">
                        {help}
                      </span>
                    </span>
                  </label>
                ))}
              </div>
            </div>
            <div
              className={
                section === "limits"
                  ? "grid gap-5 sm:grid-cols-2 lg:grid-cols-3"
                  : "hidden"
              }
            >
              {limits.map(([field, label, help, max]) => (
                <label key={field} className="text-sm font-medium">
                  {label}
                  <Input
                    className="mt-2"
                    type="number"
                    min={0}
                    max={max}
                    step={1}
                    required
                    value={String(policy[field] ?? 0)}
                    onChange={(e) =>
                      setPolicy((p) => ({
                        ...p,
                        [field]:
                          e.target.value === "" ? "" : Number(e.target.value),
                      }))
                    }
                  />
                  <span className="mt-2 block text-xs font-normal text-muted-foreground">
                    {help}
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
          {canEdit && (
            <Button type="submit" disabled={save.isPending}>
              Save collaboration rules & limits
            </Button>
          )}
          {save.error && (
            <p role="alert" className="text-sm text-destructive">
              {String(save.error)}
            </p>
          )}
        </form>
      </CardContent>
    </Card>
  );
}
export function TeamSettings({
  team,
  canEdit,
  onSaved,
}: {
  team: Team;
  canEdit: boolean;
  onSaved: () => void;
}) {
  const [name, setName] = useState(team.name);
  const [description, setDescription] = useState(team.description || "");
  const [status, setStatus] = useState(team.status);
  const save = useMutation({
    mutationFn: () =>
      patchTeam(team, { name: name.trim(), description, status }),
    onSuccess: onSaved,
  });
  return (
    <Card>
      <CardHeader>
        <CardTitle>Team settings</CardTitle>
        <CardDescription>
          Identity and availability of this Team.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form
          className="max-w-2xl space-y-4"
          onSubmit={(e) => {
            e.preventDefault();
            save.mutate();
          }}
        >
          <fieldset disabled={!canEdit || save.isPending} className="space-y-4">
            <label className="block text-sm">
              Name
              <Input
                className="mt-2"
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
              />
            </label>
            <label className="block text-sm">
              Description
              <Textarea
                className="mt-2"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
              />
            </label>
            <label className="block text-sm">
              Availability
              <select
                className="mt-2 block w-full rounded-md border border-border bg-background p-2"
                value={status}
                onChange={(e) => setStatus(e.target.value)}
              >
                <option value="active">Active</option>
                <option value="disabled">Disabled</option>
              </select>
            </label>
          </fieldset>
          <p className="break-all text-xs text-muted-foreground">
            Team ID: {team.id} · Version {team.version}
          </p>
          {canEdit && (
            <Button type="submit" disabled={save.isPending || !name.trim()}>
              Save settings
            </Button>
          )}
          {save.error && (
            <p role="alert" className="text-sm text-destructive">
              {String(save.error)}
            </p>
          )}
        </form>
      </CardContent>
    </Card>
  );
}
