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
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Clock3, Play, Plus, Power, Workflow } from "lucide-react";

import {
  createAutomation,
  listAutomations,
  triggerAutomation,
  updateAutomation,
  type Automation,
} from "@/api/collaboration";
import { useControlPlaneScope } from "@/app/ScopeContext";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input, Textarea } from "@/components/ui/input";
import {
  WorkEmpty,
  WorkLoadingRows,
  WorkPage,
  WorkPageHeader,
  WorkPanel,
  WorkPanelHeader,
} from "@/features/work/WorkSurface";
import { formatRelative } from "@/lib/format";

function configValue(config: unknown, key: string) {
  if (typeof config !== "object" || config == null || !(key in config)) return "";
  const value = (config as Record<string, unknown>)[key];
  return typeof value === "string" ? value : "";
}

function triggerSummary(item: Automation) {
  if (item.triggerType === "cron") return configValue(item.triggerConfig, "schedule") || "Cron schedule";
  if (item.triggerType === "webhook") return "Incoming webhook";
  if (item.triggerType === "channel") return "Channel event";
  return item.triggerType.replace(/_/g, " ");
}

export default function AutomationsPage() {
  const scope = useControlPlaneScope();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [schedule, setSchedule] = useState("@every 1h");
  const [title, setTitle] = useState("");
  const [priority, setPriority] = useState("normal");
  const [triggeredId, setTriggeredId] = useState<string>();
  const items = useQuery({
    queryKey: ["automations", scope.tenant, scope.namespace],
    queryFn: () => listAutomations(scope.tenant, scope.namespace),
    refetchInterval: 10000,
  });
  const create = useMutation({
    mutationFn: () => createAutomation({
      tenant: scope.tenant,
      namespace: scope.namespace,
      name: name.trim(),
      description: description.trim(),
      enabled: true,
      triggerType: "cron",
      triggerConfig: { schedule: schedule.trim() },
      actionType: "create_issue",
      actionConfig: { title: title.trim(), priority },
    }),
    onSuccess: () => {
      setOpen(false);
      setName("");
      setDescription("");
      setTitle("");
      setPriority("normal");
      void qc.invalidateQueries({ queryKey: ["automations"] });
    },
  });
  const toggle = useMutation({
    mutationFn: (item: Automation) => updateAutomation(item.id, { enabled: !item.enabled, expectedVersion: item.version }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["automations"] }),
  });
  const run = useMutation({
    mutationFn: (item: Automation) => triggerAutomation(item.id),
    onSuccess: (_data, item) => {
      setTriggeredId(item.id);
      window.setTimeout(() => setTriggeredId((current) => current === item.id ? undefined : current), 3000);
      void qc.invalidateQueries({ queryKey: ["automations"] });
      void qc.invalidateQueries({ queryKey: ["issues"] });
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    if (name.trim() && title.trim() && schedule.trim()) create.mutate();
  }

  const rows = items.data?.items || [];
  const enabledCount = rows.filter((item) => item.enabled).length;

  return (
    <WorkPage>
      <WorkPageHeader
        title="Automations"
        description="Turn schedules, webhooks, and channel events into durable issues and comments."
        actions={
          <Button onClick={() => setOpen(true)}>
            <Plus className="h-4 w-4" /> New automation
          </Button>
        }
      />

      <WorkPanel>
        <WorkPanelHeader
          title="Automation rules"
          description={items.isLoading ? "Loading rules…" : `${enabledCount} enabled · ${rows.length} total`}
        />
        {items.isLoading ? (
          <WorkLoadingRows rows={5} />
        ) : items.isError ? (
          <WorkEmpty title="Automations could not be loaded" description="Check the control plane connection and try again." />
        ) : rows.length ? (
          <div className="divide-y divide-slate-100">
            {rows.map((item) => (
              <article key={item.id} className="flex flex-col gap-4 px-5 py-5 lg:flex-row lg:items-center">
                <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-slate-200 bg-slate-50 text-slate-500">
                  <Workflow className="h-4.5 w-4.5" />
                </span>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <h3 className="text-sm font-semibold text-slate-900">{item.name}</h3>
                    <Badge tone={item.enabled ? "success" : "default"}>{item.enabled ? "Enabled" : "Paused"}</Badge>
                  </div>
                  {item.description && <p className="mt-1 line-clamp-1 text-sm text-slate-500">{item.description}</p>}
                  <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-400">
                    <span className="inline-flex items-center gap-1.5"><Clock3 className="h-3.5 w-3.5" /> {triggerSummary(item)}</span>
                    <span>{item.triggerType.replace(/_/g, " ")} → {item.actionType.replace(/_/g, " ")}</span>
                    <span>{item.lastRunAt ? `Last ran ${formatRelative(item.lastRunAt)}` : "Never run"}</span>
                    {item.nextRunAt && <span>Next {new Date(item.nextRunAt).toLocaleString()}</span>}
                  </div>
                </div>
                <div className="flex shrink-0 flex-wrap items-center gap-2 pl-12 lg:pl-0">
                  <Button size="sm" variant="outline" disabled={!item.enabled || run.isPending} onClick={() => run.mutate(item)}>
                    <Play className="h-3.5 w-3.5" /> {triggeredId === item.id ? "Triggered" : "Run now"}
                  </Button>
                  <Button size="sm" variant="ghost" disabled={toggle.isPending} onClick={() => toggle.mutate(item)}>
                    <Power className="h-3.5 w-3.5" /> {item.enabled ? "Pause" : "Enable"}
                  </Button>
                </div>
              </article>
            ))}
          </div>
        ) : (
          <WorkEmpty
            title="No automations yet"
            description="Create a schedule to open recurring issues, or configure webhook and channel triggers through the API."
            action={<Button size="sm" onClick={() => setOpen(true)}>Create automation</Button>}
          />
        )}
        {(toggle.isError || run.isError) && <p className="border-t border-red-100 bg-red-50 px-5 py-3 text-sm text-red-700">The automation action could not be completed. Refresh and try again.</p>}
      </WorkPanel>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent size="md">
          <DialogHeader>
            <DialogTitle>Create automation</DialogTitle>
            <DialogDescription>Start with a recurring schedule that creates a new issue.</DialogDescription>
          </DialogHeader>
          <DialogBody>
            <form onSubmit={submit} className="space-y-5">
              <label className="block space-y-2 text-sm font-medium text-slate-700">
                Name
                <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="Weekly research review" required autoFocus />
              </label>
              <label className="block space-y-2 text-sm font-medium text-slate-700">
                Description <span className="font-normal text-slate-400">(optional)</span>
                <Textarea className="min-h-20" value={description} onChange={(event) => setDescription(event.target.value)} placeholder="What this rule is responsible for" />
              </label>
              <div className="grid gap-4 sm:grid-cols-2">
                <label className="block space-y-2 text-sm font-medium text-slate-700">
                  Schedule
                  <Input value={schedule} onChange={(event) => setSchedule(event.target.value)} placeholder="@every 1h" required />
                </label>
                <label className="block space-y-2 text-sm font-medium text-slate-700">
                  Issue priority
                  <select className="h-10 w-full rounded-lg border border-slate-200 bg-white px-3 text-sm" value={priority} onChange={(event) => setPriority(event.target.value)}>
                    <option value="low">Low</option>
                    <option value="normal">Normal</option>
                    <option value="high">High</option>
                    <option value="urgent">Urgent</option>
                  </select>
                </label>
              </div>
              <label className="block space-y-2 text-sm font-medium text-slate-700">
                Issue title
                <Input value={title} onChange={(event) => setTitle(event.target.value)} placeholder="Review the latest research signals" required />
              </label>
              {create.isError && <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">Unable to create the automation. Check the schedule and try again.</p>}
              <div className="flex justify-end gap-2 border-t border-slate-100 pt-4">
                <Button type="button" variant="ghost" onClick={() => setOpen(false)}>Cancel</Button>
                <Button type="submit" disabled={create.isPending || !name.trim() || !title.trim() || !schedule.trim()}>
                  {create.isPending ? "Creating…" : "Create automation"}
                </Button>
              </div>
            </form>
          </DialogBody>
        </DialogContent>
      </Dialog>
    </WorkPage>
  );
}
