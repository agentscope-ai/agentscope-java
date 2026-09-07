import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import type { AgentTask } from "@/api/collaboration";
import { apiFetch } from "@/lib/apiClient";
import { type RuntimeSession } from "@/features/operate/api";
import { useControlPlaneScope } from "@/app/ScopeContext";
import { buildAgentActivities } from "./agentActivity";

// Both legacy endpoints paginate; tasks are priority ordered, so a first-page-only
// client cannot produce a trustworthy recent-work list. Read all pages, then join.
export async function collectActivityPages<T>(
  read: (offset: number) => Promise<T[]>,
  signal?: AbortSignal,
): Promise<T[]> {
  const items: T[] = [];
  for (let offset = 0; ; offset += 100) {
    signal?.throwIfAborted();
    const page = await read(offset);
    signal?.throwIfAborted();
    items.push(...page);
    if (page.length < 100) return items;
  }
}

export function useAgentActivity(agentId: string, enabled: boolean) {
  const scope = useControlPlaneScope();
  const tasks = useQuery({
    queryKey: ["agent-activity-tasks", agentId, scope.tenant, scope.namespace],
    queryFn: ({ signal }) =>
      collectActivityPages<AgentTask>(async (offset) => {
        const params = new URLSearchParams({
          agentId,
          tenant: scope.tenant,
          namespace: scope.namespace,
          limit: "100",
          offset: String(offset),
        });
        return (
          (
            await apiFetch<{ items: AgentTask[] }>(
              `/api/v1/agent-tasks?${params}`,
              { signal },
            )
          ).items || []
        );
      }, signal),
    enabled: enabled && !!agentId,
    refetchInterval: enabled ? 30_000 : false,
    refetchIntervalInBackground: false,
  });
  const sessions = useQuery({
    queryKey: [
      "agent-activity-sessions",
      agentId,
      scope.tenant,
      scope.namespace,
    ],
    queryFn: ({ signal }) =>
      collectActivityPages<RuntimeSession>(async (offset) => {
        const params = new URLSearchParams({
          agentId,
          tenant: scope.tenant,
          namespace: scope.namespace,
          limit: "100",
          offset: String(offset),
        });
        return (
          (
            await apiFetch<{ sessions: RuntimeSession[] }>(
              `/api/v1/sessions?${params}`,
              { signal },
            )
          ).sessions || []
        );
      }, signal),
    enabled: enabled && !!agentId,
    refetchInterval: enabled ? 30_000 : false,
    refetchIntervalInBackground: false,
  });
  const records = useMemo(
    () => buildAgentActivities(tasks.data || [], sessions.data || []),
    [tasks.data, sessions.data],
  );
  return {
    tasks,
    sessions,
    records,
  };
}
