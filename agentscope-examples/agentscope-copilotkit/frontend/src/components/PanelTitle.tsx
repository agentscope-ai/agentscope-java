import type { ReactNode } from "react";

export function PanelTitle({
  eyebrow,
  title,
  icon,
}: {
  eyebrow: string;
  title: string;
  icon: ReactNode;
}) {
  return (
    <div className="mb-4 flex items-start justify-between gap-3">
      <div>
        <p className="eyebrow">{eyebrow}</p>
        <h2 className="font-display text-xl font-semibold tracking-tight text-foreground">
          {title}
        </h2>
      </div>
      <div className="rounded-2xl border border-champagne/20 bg-champagne/10 p-2.5 text-champagne">
        {icon}
      </div>
    </div>
  );
}
