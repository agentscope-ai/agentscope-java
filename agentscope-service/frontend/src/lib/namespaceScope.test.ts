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
