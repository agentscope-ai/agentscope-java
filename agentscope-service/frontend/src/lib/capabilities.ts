export function hasCapability(capabilities: string[] | undefined, want: string): boolean {
  return (capabilities || []).includes(want);
}

export function canCompress(contractLevel: number, capabilities?: string[]): boolean {
  return contractLevel >= 3 && hasCapability(capabilities, 'session-command');
}

/** Terminate uses the same session-command capability as compress. */
export function canTerminate(contractLevel: number, capabilities?: string[]): boolean {
  return canCompress(contractLevel, capabilities);
}

export function canAbort(capabilities?: string[]): boolean {
  return hasCapability(capabilities, 'session-abort');
}

export function canQueryContext(capabilities?: string[]): boolean {
  return hasCapability(capabilities, 'context-query');
}

export function canQueryMessages(capabilities?: string[]): boolean {
  return hasCapability(capabilities, 'message-query');
}

export function canQueryTasks(capabilities?: string[]): boolean {
  return hasCapability(capabilities, 'task-query');
}

export function canQuerySubagentTasks(capabilities?: string[]): boolean {
  return hasCapability(capabilities, 'subagent-task-query');
}

export function canPlanMode(capabilities?: string[]): boolean {
  return hasCapability(capabilities, 'plan-mode');
}

export function canQuerySubagentInventory(capabilities?: string[]): boolean {
  return hasCapability(capabilities, 'subagent-inventory');
}
