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

import { useState, useId, type FormEvent } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Plus, Trash2, Clock3, Webhook } from "lucide-react";
import {
  createAutomation,
  updateAutomation,
  previewAutomationSchedule,
  type Automation,
  type AutomationSaved,
  type AutomationTrigger,
} from "@/api/automations";
import { me } from "@/api/auth";
import { listTeams } from "@/api/collaboration";
import { useControlPlaneScope } from "@/app/ScopeContext";
import { AgentPicker } from "@/components/AgentPicker";
import { Button } from "@/components/ui/button";
import { Input, Textarea } from "@/components/ui/input";
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  automationError,
  buildAutomationWrite,
  defaultTrigger,
  executionForForm,
} from "./form";

const selectClass =
  "h-10 w-full rounded-lg border border-slate-200 bg-white px-3 text-sm";
function TriggerEditor({
  trigger,
  onChange,
  onRemove,
}: {
  trigger: AutomationTrigger;
  onChange: (next: AutomationTrigger) => void;
  onRemove: () => void;
}) {
  const timezoneList = useId();
  const preview = useQuery({
    queryKey: ["automation-preview", trigger.schedule, trigger.timezone],
    queryFn: () =>
      previewAutomationSchedule(
        trigger.schedule || "",
        trigger.timezone || "UTC",
      ),
    enabled:
      trigger.type === "cron" && !!trigger.schedule && !!trigger.timezone,
    retry: false,
    staleTime: 10000,
  });
  return (
    <div className="space-y-3 rounded-xl border border-slate-200 bg-slate-50/50 p-4">
      <div className="flex items-center gap-2">
        {trigger.type === "cron" ? (
          <Clock3 className="h-4 w-4" />
        ) : (
          <Webhook className="h-4 w-4" />
        )}
        <span className="flex-1 text-sm font-medium">
          {trigger.type === "cron" ? "Schedule" : "Webhook"}
        </span>
        <label className="flex items-center gap-1 text-xs">
          <input
            type="checkbox"
            checked={trigger.enabled}
            onChange={(e) =>
              onChange({ ...trigger, enabled: e.target.checked })
            }
          />{" "}
          Enabled
        </label>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          aria-label="Remove trigger"
          onClick={onRemove}
        >
          <Trash2 className="h-3.5 w-3.5" />
        </Button>
      </div>
      {trigger.type === "cron" ? (
        <>
          <label className="block space-y-1 text-xs">
            Frequency
            <select
              aria-label="Schedule preset"
              className={selectClass}
              value={
                [
                  "0 9 * * 1-5",
                  "0 9 * * *",
                  "0 * * * *",
                  "*/30 * * * *",
                ].includes(trigger.schedule || "")
                  ? trigger.schedule
                  : "custom"
              }
              onChange={(e) => {
                if (e.target.value !== "custom")
                  onChange({ ...trigger, schedule: e.target.value });
              }}
            >
              <option value="0 9 * * 1-5">Weekdays at 09:00</option>
              <option value="0 9 * * *">Every day at 09:00</option>
              <option value="0 * * * *">Every hour</option>
              <option value="*/30 * * * *">Every 30 minutes</option>
              <option value="custom">Custom schedule</option>
            </select>
          </label>
          <label className="block space-y-1 text-xs">
            Cron expression
            <Input
              aria-label="Cron expression"
              value={trigger.schedule || ""}
              onChange={(e) =>
                onChange({ ...trigger, schedule: e.target.value })
              }
              required
              placeholder="0 9 * * 1-5"
            />
          </label>
          <label className="block space-y-1 text-xs">
            Time zone
            <Input
              aria-label="Time zone"
              list={timezoneList}
              value={trigger.timezone || ""}
              onChange={(e) =>
                onChange({ ...trigger, timezone: e.target.value })
              }
              required
            />
            <datalist id={timezoneList}>
              <option value="Asia/Shanghai" />
              <option value="UTC" />
              <option value="America/New_York" />
              <option value="Europe/London" />
            </datalist>
          </label>
          <div
            className="border-t border-slate-200 pt-2 text-xs text-slate-500"
            aria-live="polite"
          >
            {preview.isFetching ? (
              "Checking next runs…"
            ) : preview.error ? (
              <span className="text-red-600">
                {automationError(preview.error)}
              </span>
            ) : (
              <>
                <p className="mb-1 font-medium">Next runs</p>
                {preview.data?.nextRuns.slice(0, 3).map((date) => (
                  <div key={date}>
                    {new Date(date).toLocaleString(undefined, {
                      timeZone: trigger.timezone,
                    })}{" "}
                    · {trigger.timezone}
                  </div>
                ))}
              </>
            )}
          </div>
        </>
      ) : (
        <>
          <label className="block space-y-1 text-xs">
            Events to accept
            <Input
              aria-label="Webhook event filters"
              value={trigger.events?.join(", ") || ""}
              onChange={(e) =>
                onChange({
                  ...trigger,
                  events: e.target.value
                    .split(",")
                    .map((v) => v.trim())
                    .filter(Boolean),
                })
              }
              placeholder="build.completed, pull_request"
            />
          </label>
          <p className="text-xs leading-5 text-slate-500">
            Leave empty to accept all events. A protected webhook endpoint and
            credential will be available after saving.
          </p>
        </>
      )}
    </div>
  );
}
export function AutomationEditor({
  rule,
  onClose,
  onSaved,
}: {
  rule?: Automation;
  onClose: () => void;
  onSaved: (saved: AutomationSaved) => void;
}) {
  const scope = useControlPlaneScope();
  const [name, setName] = useState(rule?.name || "");
  const [description, setDescription] = useState(rule?.description || "");
  const [enabled, setEnabled] = useState(rule?.enabled ?? true);
  const [execution, setExecution] = useState(() => executionForForm(rule));
  const [triggers, setTriggers] = useState<AutomationTrigger[]>(() =>
    rule ? structuredClone(rule.triggers || []) : [defaultTrigger()],
  );
  const [contextLinks, setContextLinks] = useState(() =>
    (rule?.execution?.contextRefs || [])
      .filter(
        (v): v is { url: string } =>
          !!v &&
          typeof v === "object" &&
          "url" in v &&
          typeof v.url === "string",
      )
      .map((v) => v.url)
      .join("\n"),
  );
  const user = useQuery({ queryKey: ["automation-current-user"], queryFn: me });
  const subscriberRef = user.data?.userId || user.data?.username;
  const teams = useQuery({
    queryKey: ["automation-teams", scope.tenant, scope.namespace],
    queryFn: () => listTeams(scope.tenant, scope.namespace),
  });
  const save = useMutation({
    mutationFn: () => {
      const links = contextLinks
        .split("\n")
        .map((v) => v.trim())
        .filter(Boolean);
      for (const link of links) {
        try {
          const url = new URL(link);
          if (!["http:", "https:"].includes(url.protocol)) throw new Error();
        } catch {
          throw new Error("Context links must start with https:// or http://.");
        }
      }
      const retained = (execution.contextRefs || []).filter(
        (v) => !(v && typeof v === "object" && "url" in v),
      );
      const body = buildAutomationWrite(
        scope.tenant,
        scope.namespace,
        name,
        description,
        enabled,
        {
          ...execution,
          contextRefs: [...retained, ...links.map((url) => ({ url }))],
        },
        triggers,
        rule?.version,
      );
      return rule
        ? updateAutomation(rule.id, { ...body, expectedVersion: rule.version })
        : createAutomation(body);
    },
    onSuccess: onSaved,
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!save.isPending) save.mutate();
  };
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open && !save.isPending) onClose();
      }}
    >
      <DialogContent size="xl">
        <DialogHeader>
          <DialogTitle>
            {rule ? "Edit automation" : "New automation"}
          </DialogTitle>
          <DialogDescription>
            Give an Agent or Team a repeatable task.
          </DialogDescription>
        </DialogHeader>
        <DialogBody className="p-0 lg:overflow-hidden">
          <form
            id="automation-editor"
            onSubmit={submit}
            className="grid lg:h-[calc(88vh-165px)] lg:grid-cols-[minmax(0,1.35fr)_minmax(310px,1fr)]"
          >
            <div className="space-y-5 p-6 lg:overflow-y-auto lg:border-r lg:border-slate-200">
              <label className="block space-y-2 text-sm font-medium">
                Name
                <Input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="Daily engineering digest"
                  required
                  autoFocus
                  maxLength={200}
                  className="text-lg font-semibold"
                />
              </label>
              <label className="block space-y-2 text-sm font-medium">
                Runbook
                <span className="block text-xs font-normal text-slate-500">
                  Read by the Agent on every run. Include the goal, constraints,
                  steps and expected output.
                </span>
                <Textarea
                  value={execution.runbook}
                  onChange={(e) =>
                    setExecution({ ...execution, runbook: e.target.value })
                  }
                  required
                  className="min-h-[330px] text-sm leading-7 lg:min-h-[450px]"
                  placeholder={
                    "1. Review the latest project activity.\n2. Summarize changes, blockers and decisions.\n3. Include source links and clear next steps."
                  }
                />
              </label>
              <label className="block space-y-2 text-sm font-medium">
                Description{" "}
                <span className="font-normal text-slate-400">(optional)</span>
                <Input
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder="A short description for the automation list"
                />
              </label>
              <label className="block space-y-2 text-sm font-medium">
                Context links{" "}
                <span className="font-normal text-slate-400">(optional)</span>
                <Textarea
                  value={contextLinks}
                  onChange={(e) => setContextLinks(e.target.value)}
                  placeholder="One project or document link per line"
                />
              </label>
            </div>
            <div className="space-y-6 p-6 lg:overflow-y-auto">
              <fieldset className="space-y-2">
                <legend className="mb-2 text-sm font-medium">Assignee</legend>
                <select
                  aria-label="Assignee type"
                  className={selectClass}
                  value={execution.assigneeType}
                  onChange={(e) =>
                    setExecution({
                      ...execution,
                      assigneeType: e.target.value as "agent" | "team",
                      assigneeRef: "",
                    })
                  }
                >
                  <option value="agent">Agent</option>
                  <option value="team">Team</option>
                </select>
                {execution.assigneeType === "agent" ? (
                  <AgentPicker
                    required
                    value={execution.assigneeRef}
                    onChange={(assigneeRef) =>
                      setExecution({ ...execution, assigneeRef })
                    }
                  />
                ) : (
                  <select
                    required
                    aria-label="Team"
                    className={selectClass}
                    value={execution.assigneeRef}
                    onChange={(e) =>
                      setExecution({
                        ...execution,
                        assigneeRef: e.target.value,
                      })
                    }
                  >
                    <option value="">Select Team…</option>
                    {teams.data?.items
                      .filter(
                        (team) =>
                          team.status === "active" ||
                          team.id === execution.assigneeRef,
                      )
                      .map((team) => (
                        <option key={team.id} value={team.id}>
                          {team.name}
                        </option>
                      ))}
                  </select>
                )}
                <p className="text-xs leading-5 text-slate-500">
                  Runs use the assignee’s configured workspace, tools and
                  execution environment.
                </p>
              </fieldset>
              <fieldset className="space-y-2">
                <legend className="mb-2 text-sm font-medium">
                  Output mode
                </legend>
                {(
                  [
                    {
                      value: "create_issue",
                      title: "Create issue",
                      detail: "Track, discuss and review each result.",
                    },
                    {
                      value: "run_only",
                      title: "Run only",
                      detail: "View results in automation history.",
                    },
                  ] as const
                ).map((mode) => (
                  <label
                    key={mode.value}
                    className={`flex cursor-pointer gap-3 rounded-xl border p-3 ${execution.outputMode === mode.value ? "border-slate-900 bg-slate-50" : "border-slate-200"}`}
                  >
                    <input
                      type="radio"
                      name="outputMode"
                      checked={execution.outputMode === mode.value}
                      onChange={() =>
                        setExecution({ ...execution, outputMode: mode.value })
                      }
                    />
                    <span>
                      <span className="block text-sm font-medium">
                        {mode.title}
                      </span>
                      <span className="text-xs text-slate-500">
                        {mode.detail}
                      </span>
                    </span>
                  </label>
                ))}
              </fieldset>
              {execution.outputMode === "create_issue" && (
                <label className="block space-y-2 text-sm font-medium">
                  Completion
                  <select
                    aria-label="Completion policy"
                    className={selectClass}
                    value={execution.completionPolicy}
                    onChange={(e) =>
                      setExecution({
                        ...execution,
                        completionPolicy: e.target.value as
                          | "automatic"
                          | "review",
                      })
                    }
                  >
                    <option value="review">Require human review</option>
                    <option value="automatic">
                      Complete when the work finishes
                    </option>
                  </select>
                </label>
              )}
              {execution.outputMode === "create_issue" && subscriberRef && (
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={
                      execution.subscribers?.includes(subscriberRef) ?? false
                    }
                    onChange={(event) =>
                      setExecution({
                        ...execution,
                        subscribers: event.target.checked
                          ? [...(execution.subscribers || []), subscriberRef]
                          : (execution.subscribers || []).filter(
                              (ref) => ref !== subscriberRef,
                            ),
                      })
                    }
                  />{" "}
                  Subscribe me to each issue
                </label>
              )}
              <div className="space-y-3">
                <h3 className="text-sm font-medium">Triggers</h3>
                {triggers.length === 0 && (
                  <p className="text-sm text-slate-500">
                    Manual runs only. Add a trigger to run automatically.
                  </p>
                )}
                {triggers.map((trigger) => (
                  <TriggerEditor
                    key={trigger.id}
                    trigger={trigger}
                    onChange={(next) =>
                      setTriggers((items) =>
                        items.map((t) => (t.id === next.id ? next : t)),
                      )
                    }
                    onRemove={() =>
                      setTriggers((items) =>
                        items.filter((t) => t.id !== trigger.id),
                      )
                    }
                  />
                ))}
                <div className="flex gap-2">
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    disabled={triggers.length >= 10}
                    onClick={() =>
                      setTriggers((items) => [...items, defaultTrigger()])
                    }
                  >
                    <Plus className="h-3.5 w-3.5" /> Schedule
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    disabled={triggers.length >= 10}
                    onClick={() =>
                      setTriggers((items) => [
                        ...items,
                        defaultTrigger("webhook"),
                      ])
                    }
                  >
                    <Plus className="h-3.5 w-3.5" /> Webhook
                  </Button>
                </div>
              </div>
              <details className="space-y-3 text-sm">
                <summary className="cursor-pointer font-medium">
                  Advanced settings
                </summary>
                <label className="block space-y-1">
                  Overlapping runs
                  <select
                    className={selectClass}
                    value={execution.concurrencyPolicy}
                    onChange={(e) =>
                      setExecution({
                        ...execution,
                        concurrencyPolicy: e.target.value as "skip" | "queue",
                      })
                    }
                  >
                    <option value="skip">
                      Skip while a previous execution is active
                    </option>
                    <option value="queue">Queue and execute in order</option>
                  </select>
                </label>
                <label className="block space-y-1">
                  Maximum queue time (minutes)
                  <Input
                    type="number"
                    min={1}
                    max={10080}
                    value={execution.queueTimeoutSeconds / 60}
                    onChange={(e) =>
                      setExecution({
                        ...execution,
                        queueTimeoutSeconds: Number(e.target.value) * 60,
                      })
                    }
                  />
                </label>
                <label className="block space-y-1">
                  Maximum execution time (minutes)
                  <Input
                    type="number"
                    min={1}
                    max={10080}
                    value={execution.runTimeoutSeconds / 60}
                    onChange={(e) =>
                      setExecution({
                        ...execution,
                        runTimeoutSeconds: Number(e.target.value) * 60,
                      })
                    }
                  />
                </label>
              </details>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={enabled}
                  onChange={(e) => setEnabled(e.target.checked)}
                />{" "}
                Enable after saving
              </label>
            </div>
          </form>
        </DialogBody>
        <div className="flex shrink-0 flex-wrap items-center justify-between gap-3 border-t border-slate-200 px-6 py-4">
          <span role="alert" className="max-w-lg text-sm text-red-600">
            {save.error ? automationError(save.error) : ""}
          </span>
          <div className="flex gap-2">
            <Button variant="ghost" disabled={save.isPending} onClick={onClose}>
              Cancel
            </Button>
            <Button
              type="submit"
              form="automation-editor"
              disabled={
                save.isPending ||
                !name.trim() ||
                !execution.runbook.trim() ||
                !execution.assigneeRef
              }
            >
              {save.isPending
                ? "Saving…"
                : rule
                  ? "Save changes"
                  : "Create automation"}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
