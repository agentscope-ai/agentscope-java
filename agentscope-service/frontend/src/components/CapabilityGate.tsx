import { Button } from '@/components/ui/button';
import { canCompress, canQueryContext, canQueryMessages } from '@/lib/capabilities';

export function CapabilityGate({
  contractLevel = 0,
  capabilities,
  action,
  children,
  reason,
}: {
  contractLevel?: number;
  capabilities?: string[];
  action: 'compress' | 'context' | 'messages';
  children: (enabled: boolean, tip?: string) => React.ReactNode;
  reason?: string;
}) {
  let enabled = false;
  let tip = reason;
  if (action === 'compress') {
    enabled = canCompress(contractLevel, capabilities);
    tip = tip || (!enabled ? 'Data plane must advertise session-command (contract level ≥ 3)' : undefined);
  } else if (action === 'context') {
    enabled = canQueryContext(capabilities);
    tip = tip || (!enabled ? 'Data plane must advertise context-query' : undefined);
  } else {
    enabled = canQueryMessages(capabilities);
    tip = tip || (!enabled ? 'Data plane must advertise message-query' : undefined);
  }
  return <>{children(enabled, tip)}</>;
}

export function DisabledAction({ tip, label }: { tip?: string; label: string }) {
  return (
    <Button variant="outline" size="sm" disabled title={tip}>
      {label}
    </Button>
  );
}
