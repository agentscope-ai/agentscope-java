import { CircleDashed, CircleDot, CircleCheck, Target } from "lucide-react";

import { PanelTitle } from "@/components/PanelTitle";
import { Badge } from "@/components/ui/badge";
import type { PlanState, PlanTaskState } from "@/copilot/types";
import { cn } from "@/lib/utils";

const PHASE_LABEL: Record<string, string> = {
  idle: "未开始",
  planning: "规划中",
  executing: "执行中",
  completed: "已完成",
};

const STATE_META: Record<PlanTaskState, { label: string; icon: typeof CircleDot; tone: string }> = {
  pending: { label: "待开始", icon: CircleDashed, tone: "text-muted-foreground" },
  in_progress: { label: "进行中", icon: CircleDot, tone: "text-champagne" },
  completed: { label: "已完成", icon: CircleCheck, tone: "text-aurora" },
};

/**
 * Live view of the agent's task list.
 *
 * Every frame here arrives as shared state: `todo_write` mutates AgentScope's task context, the
 * workbench middleware mirrors it, and the AG-UI converter ships the difference as a STATE_DELTA.
 * Nothing on this panel is polled or locally derived.
 */
export function PlanBoard({ plan }: { plan: PlanState }) {
  const percent = Math.max(0, Math.min(100, Math.round(plan.progress)));

  return (
    <section className="grid gap-4">
      <PanelTitle eyebrow="Plan Mode" title="计划看板" icon={<Target className="size-5" />} />

      <div className="grid gap-3 rounded-2xl border border-border bg-card/60 p-4">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="text-xs font-semibold text-muted-foreground">当前目标</p>
            <p className="mt-1 text-sm leading-6 font-semibold text-foreground">
              {plan.goal ?? "尚未设定，试试让 Agent 先声明目标再列任务"}
            </p>
          </div>
          <Badge className="shrink-0 rounded-full border border-champagne/25 bg-champagne/10 font-semibold text-champagne hover:bg-champagne/15">
            {PHASE_LABEL[plan.phase] ?? plan.phase}
          </Badge>
        </div>

        <div className="grid gap-1.5">
          <div className="flex items-baseline justify-between text-xs text-muted-foreground">
            <span>
              已完成 {plan.completed} / {plan.total}
            </span>
            <span className="font-display text-sm font-semibold text-foreground">{percent}%</span>
          </div>
          <div className="h-1.5 overflow-hidden rounded-full bg-border/60">
            <div
              className="h-full rounded-full bg-linear-to-r from-champagne to-aurora transition-[width] duration-700"
              style={{ width: `${percent}%` }}
            />
          </div>
        </div>
      </div>

      {plan.tasks.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-border px-4 py-6 text-center text-xs leading-5 text-muted-foreground">
          还没有任务。让 Agent 制定一个多步计划，任务清单会随每一步推进实时出现在这里。
        </p>
      ) : (
        <ol className="grid gap-1">
          {plan.tasks.map((task, index) => {
            const meta = STATE_META[task.state] ?? STATE_META.pending;
            const Icon = meta.icon;
            return (
              <li
                key={task.id}
                className="grid grid-cols-[auto_1fr_auto] items-center gap-3 rounded-xl px-3 py-2.5 transition-colors hover:bg-secondary/40"
              >
                <Icon className={cn("size-4 shrink-0", meta.tone)} />
                <div className="min-w-0">
                  <p
                    className={cn(
                      "truncate text-sm font-medium",
                      task.state === "completed"
                        ? "text-muted-foreground line-through"
                        : "text-foreground",
                    )}
                  >
                    {index + 1}. {task.title}
                  </p>
                </div>
                <span className={cn("shrink-0 text-[0.7rem] font-semibold", meta.tone)}>
                  {meta.label}
                </span>
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}
