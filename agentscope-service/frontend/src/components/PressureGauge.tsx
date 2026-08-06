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
