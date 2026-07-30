import { Button } from '@/components/ui/button';
import {
  canAbort,
  canCompress,
  canQueryContext,
  canQueryMessages,
  canQueryTasks,
  canTerminate,
} from '@/lib/capabilities';

export type CapabilityAction = 'compress' | 'terminate' | 'abort' | 'context' | 'messages' | 'tasks';

export function CapabilityGate({
  contractLevel = 0,
  capabilities,
  action,
  children,
  reason,
}: {
  contractLevel?: number;
  capabilities?: string[];
  action: CapabilityAction;
  children: (enabled: boolean, tip?: string) => React.ReactNode;
  reason?: string;
}) {
  let enabled = false;
  let tip = reason;
  switch (action) {
    case 'compress':
      enabled = canCompress(contractLevel, capabilities);
      tip = tip || (!enabled ? 'Data plane must advertise session-command (contract level ≥ 3)' : undefined);
      break;
    case 'terminate':
      enabled = canTerminate(contractLevel, capabilities);
      tip = tip || (!enabled ? 'Data plane must advertise session-command (contract level ≥ 3)' : undefined);
      break;
    case 'abort':
      enabled = canAbort(capabilities);
      tip = tip || (!enabled ? 'Data plane must advertise session-abort' : undefined);
      break;
    case 'context':
      enabled = canQueryContext(capabilities);
      tip = tip || (!enabled ? 'Data plane must advertise context-query' : undefined);
      break;
    case 'messages':
      enabled = canQueryMessages(capabilities);
      tip = tip || (!enabled ? 'Data plane must advertise message-query' : undefined);
      break;
    case 'tasks':
      enabled = canQueryTasks(capabilities);
      tip = tip || (!enabled ? 'Data plane must advertise task-query' : undefined);
      break;
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
