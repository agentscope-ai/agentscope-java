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

import { useId } from "react";
import { Badge } from "@/components/ui/badge";
import { graphLayout } from "./workflowModel";
import { teamTone, stateLabel } from "@/features/teams/teamActivity";
export function WorkflowGraph({
  nodes,
  edges,
  selected,
  onSelect,
}: {
  nodes: { id: string; label: string; type: string; state?: string }[];
  edges: { from: string; to: string; label?: string }[];
  selected?: string;
  onSelect: (id: string) => void;
}) {
  const marker = useId().replace(/:/g, "");
  const points = graphLayout(
    nodes.map((n) => n.id),
    edges,
  );
  const positions = new Map(points.map((p) => [p.id, p]));
  const width = Math.max(480, ...points.map((p) => p.x + 244));
  const height = Math.max(140, ...points.map((p) => p.y + 112));
  return (
    <div
      role="region"
      aria-label="Workflow graph"
      tabIndex={0}
      className="min-w-0 max-w-full max-h-[600px] overflow-auto rounded-xl border border-border bg-slate-50"
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
              markerWidth="7"
              markerHeight="7"
              refX="6"
              refY="3.5"
              orient="auto"
            >
              <path d="M0,0 L7,3.5 L0,7" fill="#94a3b8" />
            </marker>
          </defs>
          {edges.map((edge, i) => {
            const a = positions.get(edge.from),
              b = positions.get(edge.to);
            if (!a || !b) return null;
            return (
              <g key={i}>
                <path
                  d={`M${a.x + 208},${a.y + 42} C${a.x + 235},${a.y + 42} ${b.x - 25},${b.y + 42} ${b.x - 3},${b.y + 42}`}
                  fill="none"
                  stroke="#94a3b8"
                  strokeWidth="1.5"
                  strokeDasharray={edge.label ? "4 3" : undefined}
                  markerEnd={`url(#${marker})`}
                />
                {edge.label && (
                  <text
                    x={(a.x + 208 + b.x) / 2}
                    y={(a.y + b.y) / 2 + 31}
                    textAnchor="middle"
                    fontSize="9"
                    fill="#64748b"
                  >
                    {edge.label}
                  </text>
                )}
              </g>
            );
          })}
        </svg>
        {nodes.map((node) => {
          const p = positions.get(node.id)!;
          return (
            <button
              key={node.id}
              aria-pressed={selected === node.id}
              onClick={() => onSelect(node.id)}
              style={{ left: p.x, top: p.y, width: 208, height: 88 }}
              className={`absolute rounded-lg border bg-white p-3 text-left shadow-sm ${selected === node.id ? "border-primary ring-1 ring-primary" : "border-border hover:border-slate-400"}`}
            >
              <span className="flex items-center justify-between gap-1 text-xs text-muted-foreground">
                <span>{node.type}</span>
                {node.state && (
                  <Badge tone={teamTone(node.state)}>
                    {stateLabel(node.state)}
                  </Badge>
                )}
              </span>
              <span className="mt-2 block truncate text-sm font-medium">
                {node.label}
              </span>
            </button>
          );
        })}
      </div>
      {!nodes.length && (
        <p className="p-4 text-sm text-muted-foreground">
          Add a node to begin designing this workflow.
        </p>
      )}
    </div>
  );
}
