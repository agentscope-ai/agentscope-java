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
      // Live fallback only; transcript reads do not need this capability.
      enabled = canQueryMessages(capabilities);
      tip =
        tip ||
        (!enabled
          ? 'Live message-query not advertised (control-plane transcript may still work)'
          : undefined);
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
