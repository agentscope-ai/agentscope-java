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
