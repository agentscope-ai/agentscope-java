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
import { useNavigate } from "react-router-dom";
import { type AgentDefinition, archiveAgent } from "@/api/agents";
import { api } from "@/lib/apiClient";
import { useControlPlaneScope } from "@/app/ScopeContext";
import { Button } from "@/components/ui/button";
import { Input, Textarea } from "@/components/ui/input";
import { Card, CardHeader, CardTitle, CardContent } from "@/components/ui/card";

export function AgentGeneralSettings({
  agent,
  canEdit,
  onSaved,
}: {
  agent: AgentDefinition;
  canEdit: boolean;
  onSaved: () => Promise<unknown>;
}) {
  const [name, setName] = useState(agent.name);
  const [description, setDescription] = useState(agent.description || "");
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const scope = useControlPlaneScope();
  const navigate = useNavigate();
  async function save() {
    setSaving(true);
    setError("");
    setMessage("");
    try {
      await api.patch(`/api/v1/agents/${encodeURIComponent(agent.id)}`, {
        displayName: name.trim(),
        description: description.trim(),
        version: agent.catalogVersion,
      });
      await onSaved();
      setMessage("Saved.");
    } catch (error) {
      setError(error instanceof Error ? error.message : "Save failed");
    } finally {
      setSaving(false);
    }
  }
  return (
    <Card>
      <CardHeader>
        <CardTitle>Agent settings</CardTitle>
      </CardHeader>
      <CardContent className="space-y-5">
        <label className="grid gap-2 text-sm">
          Name
          <Input
            value={name}
            onChange={(e) => setName(e.target.value)}
            disabled={!canEdit}
          />
        </label>
        <label className="grid gap-2 text-sm">
          Description
          <Textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            disabled={!canEdit}
          />
        </label>
        <dl className="grid gap-3 text-sm">
          <div>
            <dt className="text-muted-foreground">Agent ID</dt>
            <dd className="break-all font-mono text-xs">{agent.id}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Type</dt>
            <dd>{agent.runtimeKind}</dd>
          </div>
        </dl>
        {canEdit && (
          <div className="flex gap-3">
            <Button
              onClick={() => void save()}
              disabled={saving || !name.trim()}
            >
              Save changes
            </Button>
            <Button
              variant="outline"
              disabled={saving || agent.status === "archived"}
              onClick={async () => {
                if (
                  !window.confirm(
                    `Archive ${agent.name}? It will no longer accept new work.`,
                  )
                )
                  return;
                setSaving(true);
                setError("");
                try {
                  await archiveAgent(agent.id);
                  navigate(scope.scopedPath("/agent-center/agents"));
                } catch (error) {
                  setError(
                    error instanceof Error ? error.message : "Archive failed",
                  );
                } finally {
                  setSaving(false);
                }
              }}
            >
              Archive Agent
            </Button>
          </div>
        )}
        {message && (
          <p role="status" className="text-sm text-emerald-700">
            {message}
          </p>
        )}
        {error && (
          <p role="alert" className="text-sm text-red-600">
            {error}
          </p>
        )}
      </CardContent>
    </Card>
  );
}
