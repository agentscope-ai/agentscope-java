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
