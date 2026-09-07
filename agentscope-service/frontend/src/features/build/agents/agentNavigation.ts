import type { AgentDefinition } from "@/api/agents";

export type AgentDetailTabId =
  | "overview"
  | "activity"
  | "definition"
  | "runtime"
  | "connections"
  | "settings";
export interface AgentDetailTab {
  id: AgentDetailTabId;
  label: string;
}

export const definitionSections = [
  { id: "behavior", label: "Behavior" },
  { id: "workspace", label: "Workspace" },
  { id: "skills", label: "Skills" },
  { id: "tools", label: "Tools & MCP" },
  { id: "subagents", label: "Subagents" },
  { id: "versions", label: "Versions" },
] as const;

export function agentDetailTabs(runtimeKind?: string): AgentDetailTab[] {
  return [
    { id: "overview", label: "Overview" },
    { id: "activity", label: "Activity" },
    ...(runtimeKind === "managed"
      ? [{ id: "definition" as const, label: "Definition" }]
      : []),
    { id: "runtime", label: "Runtime configuration" },
    { id: "connections", label: "Connections" },
    { id: "settings", label: "Settings" },
  ];
}

export function resolveAgentDetailTab(
  requested: string | null,
  runtimeKind?: string,
): AgentDetailTabId {
  const aliases: Record<string, AgentDetailTabId> = {
    sessions: "activity",
    "related-work": "activity",
    entrypoints: "connections",
  };
  const tab = aliases[requested ?? ""] ?? requested;
  return agentDetailTabs(runtimeKind).some((item) => item.id === tab)
    ? (tab as AgentDetailTabId)
    : "overview";
}

export function agentServicePath(agentId: string): string {
  return `/agent-center/agents/${encodeURIComponent(agentId)}`;
}
export function agentDetailPath(
  agent: Pick<AgentDefinition, "id" | "runtimeKind">,
): string {
  return agentServicePath(agent.id);
}

export function legacyAgentManagePath(
  agentId: string,
  section: string,
  search = "",
): string {
  const params = new URLSearchParams(search);
  const base = agentServicePath(agentId);
  params.delete("tab");
  let path = base;
  if (section === "sessions") {
    params.set("tab", "activity");
    params.set("view", "sessions");
  } else if (section === "channels") {
    params.set("tab", "connections");
    params.set("connection", "channels");
  } else if (section === "chat")
    return `/work/chat?${new URLSearchParams({ ...Object.fromEntries(params), agent: agentId })}`;
  else
    path += `/definition/${definitionSections.some((item) => item.id === section) ? section : "behavior"}`;
  return `${path}${params.size ? `?${params}` : ""}`;
}
