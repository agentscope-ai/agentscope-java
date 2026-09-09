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

import { useId, useState } from "react";
import { AgentIdentity } from "@/components/AgentPicker";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { resultSummary } from "@/features/build/agents/agentActivity";
import { stateLabel, teamTone, type TaskStep } from "./teamActivity";

export function TeamTaskMap({
  steps,
  selected,
  onSelect,
}: {
  steps: TaskStep[];
  selected?: string;
  onSelect: (id: string) => void;
}) {
  const marker = useId().replace(/:/g, "");
  const [expanded, setExpanded] = useState(false);
  const visible = expanded ? steps : steps.slice(0, 24);
  const byId = new Map(visible.map((step) => [step.id, step]));
  const depth = (step: TaskStep) => {
    let current = step;
    const seen = new Set([step.id]);
    let level = 0;
    while (
      current.parentId &&
      byId.has(current.parentId) &&
      !seen.has(current.parentId)
    ) {
      current = byId.get(current.parentId)!;
      seen.add(current.id);
      level++;
    }
    return Math.min(level, 5);
  };
  const columns = new Map<number, number>();
  const positions = new Map(
    visible.map((step) => {
      const column = depth(step);
      const row = columns.get(column) || 0;
      columns.set(column, row + 1);
      return [step.id, { x: column * 280 + 12, y: row * 180 + 12 }];
    }),
  );
  const width = Math.max(1, ...[...columns.keys()].map((n) => n + 1)) * 280;
  const height = Math.max(1, ...columns.values()) * 180;
  const linked = visible.filter(
    (step) => step.parentId && positions.has(step.parentId),
  );
  return (
    <div className="min-w-0 rounded-xl border border-border bg-slate-50">
      <div className="flex items-center justify-between gap-3 border-b border-border p-3 text-xs text-muted-foreground">
        <span>
          {steps.length} work steps · {linked.length} visible task links
        </span>
        <span>Click a step to inspect</span>
      </div>
      <div
        className="max-h-[560px] overflow-auto"
        tabIndex={0}
        role="region"
        aria-label="Scrollable collaboration task graph"
      >
        <div className="relative" style={{ width, height }}>
          <svg
            width={width}
            height={height}
            className="pointer-events-none absolute inset-0"
            aria-hidden="true"
          >
            <defs>
              <marker
                id={marker}
                markerWidth="8"
                markerHeight="8"
                refX="7"
                refY="4"
                orient="auto"
              >
                <path d="M0,0 L8,4 L0,8" fill="#94a3b8" />
              </marker>
            </defs>
            {linked.map((step) => {
              const from = positions.get(step.parentId!)!;
              const to = positions.get(step.id)!;
              const startX = from.x + 230;
              const startY = from.y + 72;
              const endX = to.x - 3;
              const endY = to.y + 72;
              return (
                <path
                  key={step.id}
                  d={`M ${startX} ${startY} C ${startX + 30} ${startY}, ${endX - 30} ${endY}, ${endX} ${endY}`}
                  fill="none"
                  stroke="#94a3b8"
                  strokeWidth="1.5"
                  markerEnd={`url(#${marker})`}
                />
              );
            })}
          </svg>
          {visible.map((step) => {
            const pos = positions.get(step.id)!;
            return (
              <button
                key={step.id}
                onClick={() => onSelect(step.id)}
                aria-pressed={selected === step.id}
                style={{ left: pos.x, top: pos.y, width: 230, height: 144 }}
                className={`absolute flex flex-col rounded-lg border border-border bg-white p-3 text-left shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary ${selected === step.id ? "border-primary ring-1 ring-primary" : "hover:border-slate-400"}`}
              >
                <span className="flex w-full items-center justify-between gap-1">
                  <span className="truncate text-xs font-medium text-muted-foreground">
                    #{steps.indexOf(step) + 1} ·{" "}
                    {step.latest.leaderTask
                      ? "Lead"
                      : step.latest.teamRole || "Worker"}
                  </span>
                  <Badge tone={teamTone(step.latest.status)}>
                    {stateLabel(step.latest.status)}
                  </Badge>
                </span>
                <span className="mt-2 truncate text-sm font-medium">
                  <AgentIdentity agentId={step.latest.agentId} showId={false} />
                </span>
                <span className="mt-1 line-clamp-2 text-xs text-muted-foreground">
                  {step.latest.taskTitle ||
                    step.latest.errorMessage ||
                    resultSummary(step.latest.result) ||
                    "No result recorded yet."}
                </span>
                <span className="mt-auto text-[11px] text-muted-foreground">
                  {step.tasks.length > 1
                    ? `${step.tasks.length - 1} retries · `
                    : ""}
                  {step.parentId && byId.has(step.parentId)
                    ? `${step.relation} from #${steps.findIndex((p) => p.id === step.parentId) + 1}`
                    : "No recorded parent task"}
                </span>
              </button>
            );
          })}
        </div>
      </div>
      {!linked.length && (
        <p className="border-t border-border p-3 text-xs text-muted-foreground">
          No explicit task links were recorded. Steps appear in creation order.
        </p>
      )}
      {steps.length > 24 && (
        <div className="border-t border-border p-2">
          <Button
            size="sm"
            variant="ghost"
            onClick={() => setExpanded(!expanded)}
          >
            {expanded
              ? "Show first 24 steps"
              : `Show all ${steps.length} steps`}
          </Button>
        </div>
      )}
    </div>
  );
}
