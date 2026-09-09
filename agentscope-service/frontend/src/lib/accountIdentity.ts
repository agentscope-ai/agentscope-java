// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
import { useSyncExternalStore } from 'react';

export type AccountIdentity = { userId: string; username: string; roles: string[] };
let live: { token: string; account: AccountIdentity } | undefined;
const listeners = new Set<() => void>();
export function setAccountIdentity(token: string, account: AccountIdentity) {
  if (live?.token === token && JSON.stringify(live.account) === JSON.stringify(account)) return;
  live = { token, account };
  listeners.forEach(listener => listener());
}
export function clearAccountIdentity() { live = undefined; listeners.forEach(listener => listener()); }
export function getAccountIdentity(token: string | null) { return token && live?.token === token ? live.account : undefined; }
export function useAccountIdentity() {
  return useSyncExternalStore(callback => { listeners.add(callback); return () => { listeners.delete(callback); }; }, () => live, () => undefined)?.account;
}
