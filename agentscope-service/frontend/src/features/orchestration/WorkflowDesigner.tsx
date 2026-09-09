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

import { useState } from "react";
import type { DefinitionSpec } from "@/api/orchestration";
import { AgentPicker } from "@/components/AgentPicker";
import { Button } from "@/components/ui/button";
import { Input, Textarea } from "@/components/ui/input";
import { WorkflowGraph } from "./WorkflowGraph";
import { nodeTypes } from "./workflowModel";
export function WorkflowDesigner({
  spec,
  onChange,
  readOnly = false,
  teams,
  revisions,
}: {
  spec: DefinitionSpec;
  onChange: (v: DefinitionSpec) => void;
  readOnly?: boolean;
  teams: { id: string; name: string }[];
  revisions: { id: string; name: string }[];
}) {
  const [selected, setSelected] = useState(spec.nodes[0]?.key || "");
  const [kind, setKind] = useState("agent");
  const [key, setKey] = useState("");
  const node = spec.nodes.find((n) => n.key === selected);
  const patch = (fields: Record<string, unknown>) =>
    onChange({
      ...spec,
      nodes: spec.nodes.map((n) =>
        n.key === selected ? { ...n, ...fields } : n,
      ),
    });
  const add = () => {
    const n: DefinitionSpec["nodes"][number] = {
      key: key.trim(),
      type: kind,
      issueMode: "inherit",
      failurePolicy: "fail_fast",
    };
    if (kind === "join") n.join = { mode: "all" };
    if (kind === "timer") n.timer = { durationSeconds: 60 };
    onChange({ ...spec, nodes: [...spec.nodes, n] });
    setSelected(n.key);
    setKey("");
  };
  const object = (value: unknown) =>
    value && typeof value === "object"
      ? (value as Record<string, unknown>)
      : {};
  return (
    <div className="min-w-0 space-y-4">
      <div className="grid items-start gap-4 xl:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)]">
        <div className="min-w-0 space-y-4">
          <WorkflowGraph
            nodes={spec.nodes.map((n) => ({
              id: n.key,
              label: n.key,
              type: n.type,
            }))}
            edges={(spec.edges || []).map((e) => ({ from: e.from, to: e.to }))}
            selected={selected}
            onSelect={setSelected}
          />
          {!readOnly && (
            <form
              className="flex flex-wrap gap-2"
              onSubmit={(e) => {
                e.preventDefault();
                add();
              }}
            >
              <select
                aria-label="New node type"
                className="rounded-md border border-border bg-background p-2 text-sm"
                value={kind}
                onChange={(e) => setKind(e.target.value)}
              >
                {nodeTypes.map((type) => (
                  <option key={type}>{type}</option>
                ))}
              </select>
              <Input
                className="min-w-40 flex-1"
                aria-label="New node key"
                placeholder="Unique step name"
                value={key}
                onChange={(e) => setKey(e.target.value)}
              />
              <Button
                disabled={
                  !key.trim() || spec.nodes.some((n) => n.key === key.trim())
                }
              >
                Add node
              </Button>
            </form>
          )}
          <div className="rounded-xl border border-border p-4">
            <h3 className="font-medium">Connections & branch conditions</h3>
            <p className="mt-1 text-xs text-muted-foreground">
              A connection runs after its source reaches a matching state. Use a
              join node for all, any or quorum rules.
            </p>
            <div className="mt-3 space-y-3">
              {(spec.edges || []).map((edge, i) => (
                <fieldset
                  disabled={readOnly}
                  key={i}
                  className="grid gap-2 rounded-lg bg-muted/30 p-3"
                >
                  <div className="flex gap-2">
                    <select
                      aria-label={`Connection ${i + 1} source`}
                      className="min-w-0 flex-1 rounded border border-border p-2 text-sm"
                      value={edge.from}
                      onChange={(e) =>
                        onChange({
                          ...spec,
                          edges: spec.edges!.map((v, j) =>
                            j === i ? { ...v, from: e.target.value } : v,
                          ),
                        })
                      }
                    >
                      {spec.nodes.map((n) => (
                        <option key={n.key}>{n.key}</option>
                      ))}
                    </select>
                    <span className="py-2">→</span>
                    <select
                      aria-label={`Connection ${i + 1} target`}
                      className="min-w-0 flex-1 rounded border border-border p-2 text-sm"
                      value={edge.to}
                      onChange={(e) =>
                        onChange({
                          ...spec,
                          edges: spec.edges!.map((v, j) =>
                            j === i ? { ...v, to: e.target.value } : v,
                          ),
                        })
                      }
                    >
                      {spec.nodes.map((n) => (
                        <option key={n.key}>{n.key}</option>
                      ))}
                    </select>
                  </div>
                  <Input
                    aria-label={`Connection ${i + 1} states`}
                    value={(edge.on || ["succeeded"]).join(", ")}
                    placeholder="succeeded, skipped, failed"
                    onChange={(e) =>
                      onChange({
                        ...spec,
                        edges: spec.edges!.map((v, j) =>
                          j === i
                            ? {
                                ...v,
                                on: e.target.value
                                  .split(",")
                                  .map((s) => s.trim())
                                  .filter(Boolean),
                              }
                            : v,
                        ),
                      })
                    }
                  />
                  <Input
                    aria-label={`Connection ${i + 1} condition`}
                    placeholder="Optional CEL branch condition"
                    value={edge.condition || ""}
                    onChange={(e) =>
                      onChange({
                        ...spec,
                        edges: spec.edges!.map((v, j) =>
                          j === i ? { ...v, condition: e.target.value } : v,
                        ),
                      })
                    }
                  />
                  {!readOnly && (
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() =>
                        onChange({
                          ...spec,
                          edges: spec.edges!.filter((_, j) => j !== i),
                        })
                      }
                    >
                      Remove connection
                    </Button>
                  )}
                </fieldset>
              ))}
            </div>
            {!readOnly && (
              <Button
                className="mt-3"
                size="sm"
                variant="outline"
                disabled={spec.nodes.length < 2}
                onClick={() =>
                  onChange({
                    ...spec,
                    edges: [
                      ...(spec.edges || []),
                      {
                        from: spec.nodes[0].key,
                        to: spec.nodes[1].key,
                        on: ["succeeded"],
                      },
                    ],
                  })
                }
              >
                Add connection
              </Button>
            )}
          </div>
        </div>
        {node && (
          <div className="min-w-0 rounded-xl border border-border p-4">
            <h3 className="font-semibold">
              {node.key}{" "}
              <span className="text-sm font-normal text-muted-foreground">
                · {node.type}
              </span>
            </h3>
            <fieldset disabled={readOnly} className="mt-4 space-y-4">
              {node.type === "agent" && (
                <label className="block text-sm">
                  Agent
                  <AgentPicker
                    value={String(node.agentId || "")}
                    onChange={(agentId) => patch({ agentId })}
                    disabled={readOnly}
                    aria-label="Node Agent"
                  />
                </label>
              )}
              {node.type === "team" && (
                <label className="block text-sm">
                  Team
                  <select
                    aria-label="Node Team"
                    className="mt-1 block w-full rounded border border-border p-2"
                    value={String(node.teamRef || "")}
                    onChange={(e) => patch({ teamRef: e.target.value })}
                  >
                    <option value="">Select Team</option>
                    {teams.map((t) => (
                      <option key={t.id} value={t.id}>
                        {t.name}
                      </option>
                    ))}
                  </select>
                </label>
              )}
              {node.type === "subrun" && (
                <label className="block text-sm">
                  Published Workflow version
                  <select
                    aria-label="Sub-workflow revision"
                    className="mt-1 block w-full rounded border border-border p-2"
                    value={String(node.definitionRevisionId || "")}
                    onChange={(e) =>
                      patch({ definitionRevisionId: e.target.value })
                    }
                  >
                    <option value="">Select published version</option>
                    {revisions.map((r) => (
                      <option key={r.id} value={r.id}>
                        {r.name}
                      </option>
                    ))}
                  </select>
                </label>
              )}
              {node.type === "signal" && (
                <label className="block text-sm">
                  Signal name
                  <Input
                    value={String(node.signalName || "")}
                    onChange={(e) => patch({ signalName: e.target.value })}
                  />
                  <span className="mt-1 block text-xs text-muted-foreground">
                    The first matching signal is retained, including signals
                    received before this step.
                  </span>
                </label>
              )}
              {node.type === "timer" && (
                <label className="block text-sm">
                  Wait duration (seconds)
                  <Input
                    type="number"
                    min={1}
                    value={Number(object(node.timer).durationSeconds || 60)}
                    onChange={(e) =>
                      patch({
                        timer: { durationSeconds: Number(e.target.value) },
                      })
                    }
                  />
                </label>
              )}
              {node.type === "approval" && (
                <>
                  <label className="block text-sm">
                    Approver
                    <Input
                      value={String(object(node.approval).approverRef || "")}
                      onChange={(e) =>
                        patch({
                          approval: {
                            ...object(node.approval),
                            approverRef: e.target.value,
                          },
                        })
                      }
                    />
                  </label>
                  <label className="block text-sm">
                    Review prompt
                    <Textarea
                      value={String(object(node.approval).prompt || "")}
                      onChange={(e) =>
                        patch({
                          approval: {
                            ...object(node.approval),
                            prompt: e.target.value,
                          },
                        })
                      }
                    />
                  </label>
                </>
              )}
              {node.type === "join" && (
                <>
                  <label className="block text-sm">
                    Wait for
                    <select
                      className="mt-1 block w-full rounded border border-border p-2"
                      value={String(object(node.join).mode || "all")}
                      onChange={(e) =>
                        patch({
                          join: { ...object(node.join), mode: e.target.value },
                        })
                      }
                    >
                      <option value="all">All incoming paths</option>
                      <option value="any">Any incoming path</option>
                      <option value="quorum">A minimum number of paths</option>
                    </select>
                  </label>
                  {object(node.join).mode === "quorum" && (
                    <label className="block text-sm">
                      Required paths
                      <Input
                        type="number"
                        min={1}
                        value={Number(object(node.join).quorum || 1)}
                        onChange={(e) =>
                          patch({
                            join: {
                              ...object(node.join),
                              quorum: Number(e.target.value),
                            },
                          })
                        }
                      />
                    </label>
                  )}
                </>
              )}
              <label className="block text-sm">
                Run this step when (CEL)
                <Input
                  placeholder="Optional boolean expression"
                  value={String(node.condition || "")}
                  onChange={(e) => patch({ condition: e.target.value })}
                />
              </label>
              <label className="block text-sm">
                Work context
                <select
                  className="mt-1 block w-full rounded border border-border p-2"
                  value={String(node.issueMode || "inherit")}
                  onChange={(e) => patch({ issueMode: e.target.value })}
                >
                  <option value="inherit">Use the original Issue</option>
                  <option value="child">Create a child Issue</option>
                </select>
              </label>
              <InputMapping
                key={node.key}
                value={object(node.input)}
                onChange={(input) => patch({ input })}
              />
              <details>
                <summary className="cursor-pointer text-sm font-medium">
                  Failure handling & runtime
                </summary>
                <div className="mt-3 space-y-3">
                  <label className="block text-sm">
                    On failure
                    <select
                      className="mt-1 block w-full rounded border border-border p-2"
                      value={String(node.failurePolicy || "fail_fast")}
                      onChange={(e) => patch({ failurePolicy: e.target.value })}
                    >
                      <option value="fail_fast">Stop the workflow</option>
                      <option value="continue">
                        Continue eligible paths, keep failed outcome
                      </option>
                      <option value="partial_success">
                        Allow partial success
                      </option>
                    </select>
                  </label>
                  <label className="block text-sm">
                    Timeout (seconds, 0 = no node limit)
                    <Input
                      min={0}
                      type="number"
                      value={Number(node.timeoutSeconds || 0)}
                      onChange={(e) =>
                        patch({ timeoutSeconds: Number(e.target.value) })
                      }
                    />
                  </label>
                  <label className="block text-sm">
                    Maximum task attempts
                    <Input
                      min={1}
                      type="number"
                      value={Number(object(node.retry).maxAttempts || 1)}
                      onChange={(e) =>
                        patch({
                          retry: {
                            ...object(node.retry),
                            maxAttempts: Number(e.target.value),
                          },
                        })
                      }
                    />
                  </label>
                  <label className="block text-sm">
                    Retry delay (seconds)
                    <Input
                      min={0}
                      type="number"
                      value={Number(object(node.retry).backoffSeconds || 0)}
                      onChange={(e) =>
                        patch({
                          retry: {
                            ...object(node.retry),
                            backoffSeconds: Number(e.target.value),
                          },
                        })
                      }
                    />
                  </label>
                  <p className="text-xs text-muted-foreground">
                    Runtime candidate overrides and absolute timer dates are
                    available in advanced JSON.
                  </p>
                </div>
              </details>
            </fieldset>
            {!readOnly && (
              <Button
                className="mt-4"
                size="sm"
                variant="outline"
                onClick={() => {
                  onChange({
                    ...spec,
                    nodes: spec.nodes.filter((n) => n.key !== selected),
                    edges: (spec.edges || []).filter(
                      (e) => e.from !== selected && e.to !== selected,
                    ),
                  });
                  setSelected("");
                }}
              >
                Remove node
              </Button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
function InputMapping({
  value,
  onChange,
}: {
  value: Record<string, unknown>;
  onChange: (v: Record<string, string>) => void;
}) {
  const entries = Object.entries(value).map(([key, expression]) => [
    key,
    String(expression),
  ]);
  const update = (index: number, key: string, expression: string) => {
    onChange(
      Object.fromEntries(
        entries.map((entry, i) => (i === index ? [key, expression] : entry)),
      ),
    );
  };
  return (
    <div className="space-y-2">
      <div className="text-sm">Input mapping</div>
      {entries.map(([key, expression], i) => (
        <div key={i} className="flex gap-2">
          <Input
            aria-label={`Input ${i + 1} name`}
            className="w-28"
            value={key}
            onChange={(e) => {
              if (!entries.some(([k], j) => j !== i && k === e.target.value))
                update(i, e.target.value, expression);
            }}
          />
          <Input
            aria-label={`Input ${i + 1} expression`}
            className="min-w-0 flex-1 font-mono text-xs"
            value={expression}
            onChange={(e) => update(i, key, e.target.value)}
            placeholder="run.input.request"
          />
          <Button
            type="button"
            variant="ghost"
            aria-label={`Remove input ${key}`}
            onClick={() =>
              onChange(Object.fromEntries(entries.filter((_, j) => j !== i)))
            }
          >
            ×
          </Button>
        </div>
      ))}
      <Button
        type="button"
        variant="outline"
        size="sm"
        onClick={() => {
          let key = "input";
          let i = 1;
          while (key in value) key = `input${i++}`;
          onChange({ ...Object.fromEntries(entries), [key]: "run.input" });
        }}
      >
        Add input
      </Button>
      <p className="text-xs text-muted-foreground">
        Map each input name to a CEL expression, such as run.input.request.
        Changes are included in the draft immediately.
      </p>
    </div>
  );
}
