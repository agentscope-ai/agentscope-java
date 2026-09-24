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

import { useState, type FormEvent } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  addTeamMember,
  removeTeamMember,
  updateTeamMember,
  type RuntimeBinding,
  type RuntimeBindingPolicy,
  type TeamMember,
} from "@/api/collaboration";
import {
  AgentBindingPicker,
  AgentIdentity,
  AgentPicker,
} from "@/components/AgentPicker";
import { EmptyState } from "@/components/EmptyState";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input, Textarea } from "@/components/ui/input";
import { useControlPlaneScope } from "@/app/ScopeContext";
export function MembersEditor({
  teamId,
  teamVersion,
  leaderAgentId,
  members,
  canEdit,
}: {
  teamId: string;
  teamVersion: number;
  leaderAgentId: string;
  members: TeamMember[];
  canEdit: boolean;
}) {
  const qc = useQueryClient();
  const canConfigureRuntime = useControlPlaneScope().roles.includes("admin");
  const [clearRuntimePolicy, setClearRuntimePolicy] = useState(false);
  const [adding, setAdding] = useState(false);
  const [agentId, setAgentId] = useState("");
  const [role, setRole] = useState("");
  const [instructions, setInstructions] = useState("");
  const [backend, setBackend] = useState<
    "auto" | "managed" | "external-application" | "hosted-runtime"
  >("auto");
  const [bindingId, setBindingId] = useState("");
  const [requiredCapabilities, setRequiredCapabilities] = useState("{}");
  const [securityConstraints, setSecurityConstraints] = useState("{}");
  const [editing, setEditing] = useState<string>();
  const [editRole, setEditRole] = useState("");
  const [editInstructions, setEditInstructions] = useState("");
  const refresh = () =>
    void qc.invalidateQueries({ queryKey: ["team-overview", teamId] });
  const add = useMutation({
    mutationFn: () => {
      let runtimeBindingPolicy: RuntimeBindingPolicy | undefined;
      if (backend !== "auto") {
        const binding: RuntimeBinding = { agentId, bindingId, kind: backend };
        runtimeBindingPolicy = {
          selectionMode: "ordered",
          fallbackMode: "disabled",
          candidates: [
            {
              binding,
              requiredCapabilities: JSON.parse(requiredCapabilities),
              securityConstraints: JSON.parse(securityConstraints),
            },
          ],
        };
      }
      return addTeamMember(teamId, {
        agentId,
        role,
        instructions,
        runtimeBindingPolicy,
      });
    },
    onSuccess: () => {
      setAdding(false);
      setAgentId("");
      setRole("");
      setInstructions("");
      setBackend("auto");
      setBindingId("");
      setRequiredCapabilities("{}");
      setSecurityConstraints("{}");
      refresh();
    },
  });
  const remove = useMutation({
    mutationFn: (memberId: string) => removeTeamMember(teamId, memberId),
    onSuccess: refresh,
  });
  const update = useMutation({
    mutationFn: (member: TeamMember) =>
      updateTeamMember(teamId, member.id, {
        role: editRole,
        instructions: editInstructions,
        capabilityRequirements: member.capabilityRequirements,
        runtimeBindingPolicy: clearRuntimePolicy ? null : member.runtimeBindingPolicy,
        expectedTeamVersion: teamVersion,
      }),
    onSuccess: () => {
      setEditing(undefined);
      refresh();
    },
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    add.mutate();
  };
  return (
    <div className="space-y-4">
      {(remove.error || update.error) && (
        <p role="alert" className="text-sm text-destructive">
          {String(remove.error || update.error)}
        </p>
      )}
      {members.map((member) => (
        <Card key={member.id}>
          <CardContent className="pt-6">
            {editing === member.id ? (
              <form
                className="grid gap-3"
                onSubmit={(event) => {
                  event.preventDefault();
                  update.mutate(member);
                }}
              >
                <div className="grid gap-3 md:grid-cols-2">
                  <Input
                    value={editRole}
                    aria-label="Worker role"
                    onChange={(event) => setEditRole(event.target.value)}
                    required
                  />
                  <div className="rounded-md border border-border px-3 py-2 text-sm">
                    <AgentIdentity agentId={member.agentId} />
                  </div>
                </div>
                <Textarea
                  value={editInstructions}
                  onChange={(event) => setEditInstructions(event.target.value)}
                  placeholder="Responsibilities and hand-off expectations"
                />
                {member.runtimeBindingPolicy && canConfigureRuntime && <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={clearRuntimePolicy} onChange={event => setClearRuntimePolicy(event.target.checked)} />Use the Agent’s automatic runtime selection</label>}
                <div className="flex gap-2">
                  <Button type="submit" size="sm" disabled={update.isPending}>
                    Save
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => setEditing(undefined)}
                  >
                    Cancel
                  </Button>
                </div>
              </form>
            ) : (
              <div className="flex items-start justify-between gap-4">
                <div>
                  <div className="font-medium">{member.role}</div>
                  <div className="mt-1 text-sm">
                    <AgentIdentity agentId={member.agentId} />
                  </div>
                  {member.instructions && (
                    <p className="mt-2 text-sm text-muted-foreground">
                      {member.instructions}
                    </p>
                  )}
                  {member.runtimeBindingPolicy && (
                    <p className="mt-2 text-xs text-muted-foreground">
                      Pinned runtime selection ·{" "}
                      {member.runtimeBindingPolicy.candidates
                        .map((candidate) => candidate.binding.kind)
                        .join(" → ")}
                    </p>
                  )}
                </div>
                {canEdit && (
                  <div className="flex gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => {
                        setEditing(member.id);
                        setClearRuntimePolicy(false);
                        setEditRole(member.role);
                        setEditInstructions(member.instructions ?? "");
                      }}
                    >
                      Edit
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={remove.isPending}
                      onClick={() => remove.mutate(member.id)}
                    >
                      Remove
                    </Button>
                  </div>
                )}
              </div>
            )}
          </CardContent>
        </Card>
      ))}
      {!members.length && (
        <EmptyState
          title="No worker members"
          description="The leader can run alone, or you can add role-specific Agent members."
        />
      )}
      {canEdit && !adding && (
        <Button variant="outline" onClick={() => setAdding(true)}>
          Add worker
        </Button>
      )}
      {canEdit && adding && (
        <form
          onSubmit={submit}
          className="grid gap-3 rounded-xl border border-border bg-white p-5"
        >
          <h3 className="font-semibold">Add member</h3>
          <div className="grid gap-3 md:grid-cols-2">
            <Input
              value={role}
              onChange={(event) => setRole(event.target.value)}
              placeholder="Unique role, for example researcher"
              required
            />
            <AgentPicker
              value={agentId}
              onChange={(value) => {
                setAgentId(value);
                setBindingId("");
              }}
              excludeIds={[
                leaderAgentId,
                ...members.map((member) => member.agentId),
              ]}
              required
              aria-label="Team member Agent"
            />
          </div>
          <Textarea
            value={instructions}
            onChange={(event) => setInstructions(event.target.value)}
            placeholder="Responsibilities and hand-off expectations"
          />
          <p className="text-sm text-muted-foreground">This worker uses the Agent’s automatic runtime selection.</p>
          {canConfigureRuntime && <details className="rounded-lg border p-3"><summary className="cursor-pointer text-sm font-medium">Advanced runtime settings</summary>
          <label className="mt-3 grid gap-1 text-sm">
            Runtime selection
            <select
              className="h-10 rounded-md border border-border bg-background px-3"
              value={backend}
              onChange={(event) => {
                setBackend(event.target.value as typeof backend);
                setBindingId("");
              }}
            >
              <option value="auto">
                Use Agent's automatic runtime selection
              </option>
              <option value="external-application">External application</option>
              <option value="managed">Managed Agent</option>
              <option value="hosted-runtime">Hosted Runtime</option>
            </select>
          </label>
          {backend !== "auto" && (
            <>
              <AgentBindingPicker
                agentId={agentId}
                kind={backend}
                value={bindingId}
                onChange={setBindingId}
                required
              />
              <div className="grid gap-3 md:grid-cols-2">
                <Textarea
                  className="font-mono text-xs"
                  value={requiredCapabilities}
                  onChange={(event) =>
                    setRequiredCapabilities(event.target.value)
                  }
                  placeholder="Required capabilities JSON"
                />
                <Textarea
                  className="font-mono text-xs"
                  value={securityConstraints}
                  onChange={(event) =>
                    setSecurityConstraints(event.target.value)
                  }
                  placeholder="Security constraints JSON"
                />
              </div>
            </>
          )}
          </details>}
          <div>
            <Button type="submit" disabled={add.isPending}>
              Add member
            </Button>
            <Button
              type="button"
              variant="ghost"
              onClick={() => setAdding(false)}
            >
              Cancel
            </Button>
          </div>
          {add.error && (
            <p className="text-sm text-destructive">{String(add.error)}</p>
          )}
        </form>
      )}
    </div>
  );
}
