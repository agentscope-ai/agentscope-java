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

export function EmptyState({
  title,
  description,
  action,
  className,
}: {
  title: string;
  description?: string;
  action?: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        'flex min-h-56 flex-col items-center justify-center rounded-2xl border border-slate-200/90 bg-white px-6 py-12 text-center shadow-[0_1px_2px_rgba(15,23,42,0.035)]',
        className,
      )}
    >
      <div className="h-10 w-10 rounded-full border border-slate-200 bg-slate-50" />
      <h3 className="mt-4 text-sm font-semibold text-slate-900">{title}</h3>
      {description && (
        <p className="mt-1 max-w-md text-sm leading-6 text-slate-500">{description}</p>
      )}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}
