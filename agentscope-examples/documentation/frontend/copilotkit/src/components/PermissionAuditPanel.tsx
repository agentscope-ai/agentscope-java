import { CircleSlash, Clock, ShieldCheck, ShieldHalf } from "lucide-react";

import { PanelTitle } from "@/components/PanelTitle";
import type { PermissionAuditEntry } from "@/copilot/types";
import { cn } from "@/lib/utils";

const BEHAVIOR_META: Record<
  string,
  { label: string; hint: string; icon: typeof ShieldCheck; ring: string }
> = {
  ASK: {
    label: "ASK",
    hint: "已挂起，等待用户裁决",
    icon: Clock,
    ring: "border-champagne/30 bg-champagne/[0.08] text-champagne",
  },
  ALLOW: {
    label: "ALLOW",
    hint: "已放行",
    icon: ShieldCheck,
    ring: "border-aurora/30 bg-aurora/[0.08] text-aurora",
  },
  DENY: {
    label: "DENY",
    hint: "已拦截",
    icon: CircleSlash,
    ring: "border-destructive/30 bg-destructive/[0.08] text-destructive",
  },
};

/**
 * The ask → decide trail of AgentScope's permission engine.
 *
 * Three different mechanisms feed this list, and the demo prompts exercise each one: a rule-based
 * ASK that suspends the run into an AG-UI interrupt, a rule-based DENY that no mode can lift, and a
 * tool's own `checkPermissions` deciding from its arguments.
 */
export function PermissionAuditPanel({ entries }: { entries: PermissionAuditEntry[] }) {
  return (
    <section className="grid gap-4">
      <PanelTitle
        eyebrow="Permission Engine"
        title="权限审计"
        icon={<ShieldHalf className="size-5" />}
      />

      {entries.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-border px-4 py-6 text-center text-xs leading-5 text-muted-foreground">
          还没有权限决策记录。请求一次生产发布会触发 ASK 并弹出确认卡片，请求清库则会被 DENY 直接拦下。
        </p>
      ) : (
        <ul className="grid gap-2">
          {entries.map((entry, index) => {
            const meta = BEHAVIOR_META[entry.behavior] ?? BEHAVIOR_META.ASK;
            const Icon = meta.icon;
            return (
              <li
                key={`${entry.at}-${entry.tool}-${index}`}
                className="grid gap-2 rounded-2xl border border-border bg-card/60 p-3 transition-shadow duration-300 hover:shadow-[0_12px_32px_oklch(0_0_0/0.28)]"
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="truncate font-mono text-xs font-semibold text-foreground">
                    {entry.tool}
                  </span>
                  <span
                    className={cn(
                      "inline-flex shrink-0 items-center gap-1 rounded-full border px-2 py-0.5 text-[0.65rem] font-semibold",
                      meta.ring,
                    )}
                  >
                    <Icon className="size-3" />
                    {meta.label}
                  </span>
                </div>
                {entry.detail ? (
                  <pre className="m-0 overflow-x-auto rounded-lg bg-secondary/40 px-2.5 py-2 font-mono text-[0.68rem] leading-5 text-muted-foreground">
                    {entry.detail}
                  </pre>
                ) : null}
                <p className="flex items-center justify-between gap-2 text-[0.68rem] text-muted-foreground">
                  <span>{meta.hint}</span>
                  <span>{entry.at}</span>
                </p>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
