import { describe, it, expect } from 'vitest';
import { namespaceCan, resolveAuthorizedNamespace, type NamespaceSummary } from './namespaceScope';
describe('namespace access', () => {
  const items: NamespaceSummary[] = [
    { tenant: 'acme', name: 'personal', displayName: 'Personal', kind: 'personal', roles: ['admin'] },
    { tenant: 'acme', name: 'team', displayName: 'Team', kind: 'shared', roles: ['member'] },
  ];
  it('discards stale or forged saved namespace selections', () => {
    expect(resolveAuthorizedNamespace(items, 'acme', 'secret', 'personal')?.name).toBe('personal');
    expect(resolveAuthorizedNamespace(items, 'acme', 'team', 'personal')?.name).toBe('team');
  });
  it('keeps data auditing separate from administration', () => {
    expect(namespaceCan(['admin'], 'audit')).toBe(false);
    expect(namespaceCan(['auditor'], 'audit')).toBe(true);
    expect(namespaceCan(['viewer'], 'write')).toBe(false);
    expect(namespaceCan(['member'], 'configure')).toBe(false);
  });
});
