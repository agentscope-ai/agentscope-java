import { cn } from '@/lib/utils';

export function PressureGauge({
  value,
  className,
}: {
  value?: number | null;
  className?: string;
}) {
  const ratio = Math.max(0, Math.min(1, value ?? 0));
  const pct = Math.round(ratio * 100);
  const tone =
    ratio >= 0.85 ? 'bg-red-500' : ratio >= 0.7 ? 'bg-amber-500' : 'bg-emerald-500';
  return (
    <div className={cn('flex items-center gap-2', className)} title={`Context pressure ${pct}%`}>
      <div className="h-2 w-24 overflow-hidden rounded-full bg-slate-100">
        <div className={cn('h-full rounded-full transition-all', tone)} style={{ width: `${pct}%` }} />
      </div>
      <span className="text-xs tabular-nums text-muted-foreground">{pct}%</span>
    </div>
  );
}
