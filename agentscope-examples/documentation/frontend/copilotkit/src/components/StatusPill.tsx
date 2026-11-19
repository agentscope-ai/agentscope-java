import type { ReactNode } from "react";

export function StatusPill({
  icon,
  label,
  value,
}: {
  icon: ReactNode;
  label: string;
  value: string;
}) {
  return (
    <div className="rounded-2xl border border-border bg-secondary/40 p-3 transition-all duration-300 hover:border-champagne/25 hover:bg-secondary/70 hover:shadow-[0_14px_36px_oklch(0_0_0/0.28)]">
      <div className="mb-2 flex items-center gap-2 text-champagne">
        {icon}
        <span className="text-xs font-semibold tracking-wide">{label}</span>
      </div>
      <p className="truncate text-sm font-semibold text-foreground">{value}</p>
    </div>
  );
}
