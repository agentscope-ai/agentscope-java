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

import { describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { StaticRouter } from 'react-router-dom/server';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import DefinitionsPage from './DefinitionsPage';

const scope = vi.hoisted(() => ({
  tenant: 'default', namespace: 'personal', roles: [] as string[],
  scopedPath: (path: string) => path,
  loginRoles: [] as string[],
}));
vi.mock('@/app/ScopeContext', () => ({ useControlPlaneScope: () => scope }));
// A global admin must not override a read-only namespace; a namespace admin
// must not need a legacy role in the login token.
vi.mock('@/api/auth', () => ({ getRoles: () => scope.loginRoles }));

function renderList(roles: string[], empty: boolean, loginRoles: string[] = ['user']) {
  scope.roles = roles;
  scope.loginRoles = loginRoles;
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(['workflow-list', 'default', 'personal', 0, false], {
    definitions: empty ? [] : [{
      id: 'workflow-1', name: 'Research', draftSpec: { nodes: [] }, updatedAt: 0,
    }],
  });
  const html = renderToStaticMarkup(
    <StaticRouter location="/agent-center/workflows">
      <QueryClientProvider client={client}><DefinitionsPage /></QueryClientProvider>
    </StaticRouter>,
  );
  client.clear();
  return html;
}

describe('Workflow creation entry uses namespace permissions', () => {
  it.each(['admin', 'developer'])('offers creation for namespace %s', (role) => {
    expect(renderList([role], false)).toContain('Create Workflow</button>');
    expect(renderList([role], true).match(/Create Workflow<\/button>/g)).toHaveLength(2);
  });

  it.each(['viewer', 'member', 'operator', 'auditor', 'agent_developer'])(
    'does not grant definition management to namespace %s based on the login role', (role) => {
      expect(renderList([role], false, ['admin'])).not.toContain('Create Workflow</button>');
      const empty = renderList([role], true, ['admin']);
      expect(empty).not.toContain('Create Workflow</button>');
      expect(empty).toContain('No workflows are available in this namespace yet.');
    },
  );

  it('does not offer creation before authorized scope is available', () => {
    expect(renderList([], true)).not.toContain('Create Workflow</button>');
  });
});
