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

import type { DefinitionSpec } from "@/api/orchestration";
export const nodeTypes = [
  "agent",
  "team",
  "condition",
  "join",
  "approval",
  "timer",
  "signal",
  "subrun",
] as const;
export function parseWorkflow(text: string): {
  spec?: DefinitionSpec;
  error?: string;
} {
  try {
    const value: unknown = JSON.parse(text);
    if (!value || typeof value !== "object" || Array.isArray(value))
      return { error: "The workflow must be a JSON object." };
    const spec = value as DefinitionSpec;
    if (!Array.isArray(spec.nodes)) return { error: "nodes must be an array." };
    if (
      spec.nodes.some(
        (n) =>
          !n ||
          typeof n !== "object" ||
          typeof n.key !== "string" ||
          typeof n.type !== "string",
      )
    )
      return { error: "Every node needs a key and type." };
    if (
      spec.edges !== undefined &&
      (!Array.isArray(spec.edges) ||
        spec.edges.some(
          (e) => !e || typeof e.from !== "string" || typeof e.to !== "string",
        ))
    )
      return { error: "Every edge needs from and to node keys." };
    return { spec };
  } catch (error) {
    return { error: String(error) };
  }
}
export function graphLayout(
  ids: string[],
  edges: { from: string; to: string }[],
) {
  const depth = new Map(ids.map((id) => [id, 0]));
  const pending = new Set(ids);
  for (let round = 0; round < ids.length; round++) {
    let changed = false;
    for (const id of pending) {
      const parents = edges
        .filter((e) => e.to === id && depth.has(e.from))
        .map((e) => e.from);
      if (parents.some((p) => pending.has(p))) continue;
      depth.set(
        id,
        parents.length ? Math.max(...parents.map((p) => depth.get(p)!)) + 1 : 0,
      );
      pending.delete(id);
      changed = true;
    }
    if (!changed) break;
  }
  const rows = new Map<number, number>();
  return ids.map((id) => {
    const column = depth.get(id) || 0;
    const row = rows.get(column) || 0;
    rows.set(column, row + 1);
    return { id, x: column * 260 + 16, y: row * 120 + 16 };
  });
}
export function summarizeOutput(value: unknown): string {
  if (typeof value === "string") return value;
  if (value == null) return "";
  if (typeof value === "object") {
    const v = value as Record<string, unknown>;
    for (const key of ["summary", "text", "content", "result", "output"])
      if (v[key] !== undefined) return summarizeOutput(v[key]);
  }
  return JSON.stringify(value, null, 2);
}
