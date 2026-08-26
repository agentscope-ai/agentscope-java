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
import { Badge } from '@/components/ui/badge';
import { type TranslationKey, useT } from '@/i18n';

const STATUS_LABELS: Record<string, TranslationKey> = {
  active: 'status.active',
  healthy: 'status.healthy',
  ready: 'status.ready',
  terminated: 'status.terminated',
  failed: 'status.failed',
  unhealthy: 'status.unhealthy',
  compressing: 'status.compressing',
  pending: 'status.pending',
};

export function StatusBadge({ status, className }: { status?: string; className?: string }) {
  const t = useT();
  const s = (status || '').toLowerCase();
  const tone =
    s === 'active' || s === 'healthy' || s === 'ready'
      ? 'success'
      : s === 'terminated' || s === 'failed' || s === 'unhealthy'
        ? 'danger'
        : s === 'compressing' || s === 'pending'
          ? 'warning'
          : 'default';
  return (
    <Badge tone={tone} className={cn('uppercase tracking-wide', className)}>
      {status ? (STATUS_LABELS[s] ? t(STATUS_LABELS[s]) : status) : t('status.unknown')}
    </Badge>
  );
}
