export function hasCapability(capabilities: string[] | undefined, want: string): boolean {
  return (capabilities || []).includes(want);
}

export function canCompress(contractLevel: number, capabilities?: string[]): boolean {
  return contractLevel >= 3 && hasCapability(capabilities, 'session-command');
}

export function canQueryContext(capabilities?: string[]): boolean {
  return hasCapability(capabilities, 'context-query');
}

export function canQueryMessages(capabilities?: string[]): boolean {
  return hasCapability(capabilities, 'message-query');
}
