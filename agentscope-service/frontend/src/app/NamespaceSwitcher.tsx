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

/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { Check, ChevronDown, Plus, Settings2 } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { getUsername, isAdmin } from '@/lib/auth';
import { useAccountIdentity } from '@/lib/accountIdentity';
import { useControlPlaneScope } from './ScopeContext';

const menuItem = 'flex cursor-pointer items-center gap-3 rounded-lg px-3 py-2.5 text-sm text-slate-700 outline-none data-[highlighted]:bg-slate-100 data-[highlighted]:text-slate-950';

function Initial({ name }: { name: string }) {
  return <span aria-hidden="true" className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-slate-100 text-sm font-medium text-slate-600">{Array.from(name)[0]?.toLocaleUpperCase() || '?'}</span>;
}

export function NamespaceSwitcher() {
  useAccountIdentity();
  const navigate = useNavigate();
  const { tenant, namespace, namespaces, selectorVisible, setScope } = useControlPlaneScope();
  if (!selectorVisible) return null;
  const current = namespaces.find(n => n.tenant === tenant && n.name === namespace);
  const displayName = current?.displayName || namespace;
  const username = getUsername() || 'Account';

  return (
    <div className="border-b border-border px-3 py-3">
      <DropdownMenu.Root modal={false}>
        <DropdownMenu.Trigger asChild>
          <button type="button" aria-label="Namespace" className="flex w-full items-center gap-3 rounded-xl bg-slate-100/80 px-3 py-2.5 text-left transition-colors hover:bg-slate-200/70 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring data-[state=open]:bg-slate-200/70">
            <Initial name={displayName} />
            <span className="min-w-0 flex-1">
              <span className="block text-[11px] text-muted-foreground">Namespace</span>
              <span className="block truncate text-sm font-semibold text-foreground">{displayName}</span>
            </span>
            <ChevronDown aria-hidden="true" className="h-4 w-4 shrink-0 text-slate-500" />
          </button>
        </DropdownMenu.Trigger>
        <DropdownMenu.Portal>
          <DropdownMenu.Content align="start" sideOffset={6} collisionPadding={12} className="z-50 w-72 max-w-[calc(100vw-24px)] overflow-y-auto rounded-xl border border-border bg-white p-1.5 shadow-xl outline-none max-h-[var(--radix-dropdown-menu-content-available-height)]">
            <div className="flex items-center gap-3 px-3 py-3">
              <Initial name={username} />
              <div className="min-w-0">
                <div className="truncate text-sm font-semibold text-foreground">{username}</div>
                <div className="text-xs text-muted-foreground">Signed-in account</div>
              </div>
            </div>
            <DropdownMenu.Separator className="-mx-1.5 my-1 h-px bg-border" />
            <DropdownMenu.Label className="px-3 py-2 text-xs font-medium text-muted-foreground">Namespaces</DropdownMenu.Label>
            <DropdownMenu.RadioGroup value={JSON.stringify([tenant, namespace])} onValueChange={value => {
              const next = namespaces.find(n => JSON.stringify([n.tenant, n.name]) === value);
              if (next) setScope(next.tenant, next.name);
            }}>
              {namespaces.map(n => (
                <DropdownMenu.RadioItem key={JSON.stringify([n.tenant, n.name])} value={JSON.stringify([n.tenant, n.name])} textValue={n.displayName || n.name} className={menuItem}>
                  <Initial name={n.displayName || n.name} />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium">{n.displayName || n.name}</span>
                    <span className="block truncate text-xs text-muted-foreground">{n.kind === 'personal' ? 'Personal' : n.kind === 'global' ? 'Global' : 'Shared'} · {n.tenant}/{n.name}</span>
                  </span>
                  <DropdownMenu.ItemIndicator><Check aria-hidden="true" className="h-4 w-4 shrink-0" /></DropdownMenu.ItemIndicator>
                </DropdownMenu.RadioItem>
              ))}
            </DropdownMenu.RadioGroup>
            <DropdownMenu.Separator className="-mx-1.5 my-1 h-px bg-border" />
            <DropdownMenu.Item className={menuItem} onSelect={() => navigate(`/settings/namespaces/${encodeURIComponent(namespace)}?${new URLSearchParams({ tenant, namespace })}`)}>
              <Settings2 aria-hidden="true" className="h-4 w-4" />Namespace access
            </DropdownMenu.Item>
            {isAdmin() && <DropdownMenu.Item className={menuItem} onSelect={() => navigate(`/settings/namespaces?${new URLSearchParams({ create: 'true', tenant, namespace })}`)}>
              <Plus aria-hidden="true" className="h-4 w-4" />Create namespace
            </DropdownMenu.Item>}
          </DropdownMenu.Content>
        </DropdownMenu.Portal>
      </DropdownMenu.Root>
    </div>
  );
}
