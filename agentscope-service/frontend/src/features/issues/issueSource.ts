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
