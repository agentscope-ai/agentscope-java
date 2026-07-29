import { cn } from '@/lib/utils';
import { Badge } from '@/components/ui/badge';

export function StatusBadge({ status, className }: { status?: string; className?: string }) {
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
      {status || 'unknown'}
    </Badge>
  );
}
