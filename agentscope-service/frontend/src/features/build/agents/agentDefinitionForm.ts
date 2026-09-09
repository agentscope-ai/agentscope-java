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

import type { AgentCreateRequest, AgentDefinition } from "@/api/agents";

export type DefinitionFormSection =
  | "all"
  | "behavior"
  | "workspace"
  | "runtime"
  | "settings"
  | "versions";
export interface DefinitionFormValues {
  name: string;
  description: string;
  model: string;
  system: string;
  maxIters: string;
  workspaceId: string;
  defaultEnvironmentId: string;
  defaultVaultIds: string[];
  defaultMemoryStoreIds: string[];
  version?: number;
}

/** Only send the edited slice; updateAgent merges untouched fields from the current definition. */
export function definitionFormPatch(
  agent: AgentDefinition,
  section: DefinitionFormSection,
  values: DefinitionFormValues,
): AgentCreateRequest {
  const show = (part: DefinitionFormSection) =>
    section === "all" || section === part;
  if (values.version == null)
    throw new Error("Missing agent version for optimistic lock");
  const maxIters = Number(values.maxIters);
  if (
    show("behavior") &&
    (!Number.isInteger(maxIters) || maxIters < 1 || maxIters > 64)
  ) {
    throw new Error("Max iterations must be an integer between 1 and 64.");
  }
  return {
    name: show("settings") ? values.name.trim() || agent.name : agent.name,
    ...(show("settings") ? { description: values.description.trim() } : {}),
    ...(show("behavior")
      ? { model: values.model.trim(), system: values.system, maxIters }
      : {}),
    ...(show("workspace") ? { workspaceId: values.workspaceId || "" } : {}),
    ...(show("runtime")
      ? {
          defaultEnvironmentId: values.defaultEnvironmentId || "",
          defaultVaultIds: values.defaultVaultIds,
          defaultMemoryStoreIds: values.defaultMemoryStoreIds,
        }
      : {}),
    version: values.version,
  };
}
