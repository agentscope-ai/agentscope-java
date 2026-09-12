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

import type { HTMLAttributes, ReactNode } from "react";

import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

export function WorkPage({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div className="min-h-full bg-white">
      <div
        className={cn(
          "mx-auto max-w-[1440px] space-y-7 px-5 py-7 sm:px-8 sm:py-9 lg:px-10",
          className,
        )}
        {...props}
      />
    </div>
  );
}

export function WorkPageHeader({
  title,
  description,
  actions,
}: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div className="min-w-0">
        <h1 className="text-[28px] font-semibold tracking-[-0.025em] text-slate-950 sm:text-[32px]">
          {title}
        </h1>
        {description && (
          <p className="mt-1.5 max-w-3xl text-[15px] leading-6 text-slate-500">
            {description}
          </p>
        )}
      </div>
      {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
    </header>
  );
}

export function WorkPanel({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        "overflow-hidden rounded-2xl border border-slate-200/90 bg-white shadow-[0_1px_2px_rgba(15,23,42,0.035)]",
        className,
      )}
      {...props}
    />
  );
}

export function WorkPanelHeader({
  title,
  description,
  action,
  className,
}: {
  title: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex items-start justify-between gap-4 border-b border-slate-100 px-5 py-4", className)}>
      <div>
        <h2 className="text-[15px] font-semibold text-slate-900">{title}</h2>
        {description && <p className="mt-1 text-sm text-slate-500">{description}</p>}
      </div>
      {action && <div className="shrink-0">{action}</div>}
    </div>
  );
}

const statusMeta: Record<string, { label: string; tone: "default" | "success" | "warning" | "danger" | "info" }> = {
  backlog: { label: "Backlog", tone: "default" },
  todo: { label: "Todo", tone: "default" },
  open: { label: "Open", tone: "info" },
  in_progress: { label: "In progress", tone: "info" },
  in_review: { label: "In review", tone: "warning" },
  blocked: { label: "Blocked", tone: "danger" },
  done: { label: "Done", tone: "success" },
  completed: { label: "Completed", tone: "success" },
  approved: { label: "Approved", tone: "success" },
  rejected: { label: "Rejected", tone: "danger" },
  failed: { label: "Failed", tone: "danger" },
  cancelled: { label: "Cancelled", tone: "default" },
  pending: { label: "Pending", tone: "warning" },
};

export function WorkStatusBadge({ status, className }: { status: string; className?: string }) {
  const meta = statusMeta[status] || {
    label: status.replace(/_/g, " "),
    tone: "default" as const,
  };
  return (
    <Badge tone={meta.tone} className={cn("capitalize", className)}>
      {meta.label}
    </Badge>
  );
}

export function WorkEmpty({
  title,
  description,
  action,
  className,
}: {
  title: string;
  description: string;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex min-h-48 flex-col items-center justify-center px-6 py-12 text-center", className)}>
      <div className="h-10 w-10 rounded-full border border-slate-200 bg-slate-50" />
      <h3 className="mt-4 text-sm font-semibold text-slate-900">{title}</h3>
      <p className="mt-1 max-w-md text-sm leading-6 text-slate-500">{description}</p>
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function WorkLoadingRows({ rows = 4 }: { rows?: number }) {
  return (
    <div className="divide-y divide-slate-100" aria-label="Loading">
      {Array.from({ length: rows }).map((_, index) => (
        <div key={index} className="flex animate-pulse items-center gap-4 px-5 py-4">
          <div className="h-8 w-8 rounded-lg bg-slate-100" />
          <div className="flex-1 space-y-2">
            <div className="h-3 w-2/5 rounded bg-slate-100" />
            <div className="h-2.5 w-1/4 rounded bg-slate-100" />
          </div>
          <div className="h-6 w-16 rounded-full bg-slate-100" />
        </div>
      ))}
    </div>
  );
}
