export type IssueSource = "all" | "work" | "endpoint_jobs";

export interface IssueSourceFilters {
  kind?: string;
  includeOperational?: boolean;
}

export function issueSourceFromParam(value: string | null): IssueSource {
  return value === "work" || value === "endpoint_jobs" ? value : "all";
}

export function filtersForIssueSource(source: IssueSource): IssueSourceFilters {
  if (source === "endpoint_jobs") {
    return { kind: "endpoint_job", includeOperational: true };
  }
  if (source === "all") {
    return { includeOperational: true };
  }
  return {};
}
