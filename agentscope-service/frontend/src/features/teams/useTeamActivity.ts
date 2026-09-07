import { useQuery } from "@tanstack/react-query";
import { useControlPlaneScope } from "@/app/ScopeContext";
import { apiFetch } from "@/lib/apiClient";
import type { OrchestrationRun } from "@/api/orchestration";
import { collectActivityPages } from "@/features/build/agents/useAgentActivity";
import { groupTeamActivity, type TeamTask } from "./teamActivity";

// Existing task lists sort by priority and oldest first. Exhaust pagination before
// computing totals/recent work, and bound detail requests to avoid a request burst.
async function readBatched<T>(
  ids: string[],
  read: (id: string) => Promise<T>,
  signal: AbortSignal,
) {
  const values: T[] = [];
  let failures = 0;
  for (let offset = 0; offset < ids.length; offset += 6) {
    signal.throwIfAborted();
    const results = await Promise.allSettled(
      ids.slice(offset, offset + 6).map(read),
    );
    signal.throwIfAborted();
    for (const result of results) {
      if (result.status === "fulfilled") values.push(result.value);
      else failures++;
    }
  }
  return { values, failures };
}
export function useTeamActivity(teamId: string, enabled: boolean) {
  const scope = useControlPlaneScope();
  return useQuery({
    queryKey: ["team-activity", scope.tenant, scope.namespace, teamId],
    enabled: enabled && !!teamId,
    refetchInterval: enabled ? 30_000 : false,
    refetchIntervalInBackground: false,
    queryFn: async ({ signal }) => {
      const params = new URLSearchParams({
        tenant: scope.tenant,
        namespace: scope.namespace,
      });
      const tasks = await collectActivityPages<TeamTask>(
        async (offset) =>
          (
            await apiFetch<{ items: TeamTask[] }>(
              `/api/v1/agent-tasks?${params}&teamId=${encodeURIComponent(teamId)}&limit=100&offset=${offset}`,
              { signal },
            )
          ).items || [],
        signal,
      );
      const unique = [
        ...new Map(tasks.map((task) => [task.id, task])).values(),
      ];
      const ids = [
        ...new Set(
          unique
            .map((task) => task.orchestrationRunId)
            .filter(
              (id) => id && id !== "00000000-0000-0000-0000-000000000000",
            ),
        ),
      ];
      const runs = await readBatched(
        ids,
        async (id) =>
          (
            await apiFetch<{ run: OrchestrationRun }>(
              `/api/v1/orchestration-runs/${encodeURIComponent(id)}?${params}`,
              { signal },
            )
          ).run,
        signal,
      );
      const issueIds = [
        ...new Set(
          [
            ...runs.values.map((run) => run.rootIssueId),
            ...unique.map((task) => task.issueId),
          ].filter(Boolean),
        ),
      ];
      const issues = await readBatched(
        issueIds,
        async (id) =>
          (
            await apiFetch<{ issue: { id: string; title: string } }>(
              `/api/v1/issues/${encodeURIComponent(id)}?${params}`,
              { signal },
            )
          ).issue,
        signal,
      );
      const titles = Object.fromEntries(
        issues.values.filter(Boolean).map((issue) => [issue.id, issue.title]),
      );
      return {
        records: groupTeamActivity(
          unique.map((task) => ({ ...task, taskTitle: titles[task.issueId] })),
          runs.values,
          Object.fromEntries(
            issues.values
              .filter(Boolean)
              .map((issue) => [issue.id, issue.title]),
          ),
        ),
        missingRuns: runs.failures,
        missingIssues: issues.failures,
      };
    },
  });
}
