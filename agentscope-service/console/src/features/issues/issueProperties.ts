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

export type ChecklistItem = {
  id?: string;
  text?: string;
  required?: boolean;
  satisfied: boolean;
  [key: string]: unknown;
};

export type AcceptanceCriteria = {
  requiredResult?: boolean;
  minimumArtifacts?: number;
  minimumApprovals?: number;
  checklist?: ChecklistItem[];
  [key: string]: unknown;
};

export function parseCriteria(value: unknown): AcceptanceCriteria {
  if (!value || Array.isArray(value) || typeof value !== "object") return {};
  const criteria = value as AcceptanceCriteria;
  return {
    ...criteria,
    checklist: Array.isArray(criteria.checklist)
      ? criteria.checklist.filter((item) => item && typeof item === "object")
      : [],
  };
}

export function dateTimeLocal(value?: string) {
  if (!value) return "";
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return "";
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

export function dueDatePatch(value: string): { dueAt: string | null } {
  return { dueAt: value ? new Date(value).toISOString() : null };
}

export function sourceWebUrl(ref?: string) {
  if (!ref) return undefined;
  try {
    const url = new URL(ref);
    if ((url.protocol === "https:" || url.protocol === "http:") && !url.username && !url.password) return url.href;
  } catch { /* Opaque source references remain plain text. */ }
  return undefined;
}

export function saveIssueDownload(blob: Blob, filename: string) {
  const href = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = href;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  // Keep the URL alive until the browser has started consuming the download.
  window.setTimeout(() => URL.revokeObjectURL(href), 1000);
}
