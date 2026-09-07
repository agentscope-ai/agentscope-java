export const tabs = [
  ["overview", "Overview"],
  ["activity", "Activity"],
  ["orchestration", "Team orchestration"],
  ["connections", "Connections"],
  ["settings", "Settings"],
] as const;
export function normalizeTeamTab(value: string | null) {
  if (value === "members" || value === "coordination") return "orchestration";
  if (value === "endpoints") return "connections";
  return tabs.some(([key]) => key === value) ? value! : "overview";
}
