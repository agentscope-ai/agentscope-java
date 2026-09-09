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

import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import McpConnectionsEditor, { editorTransport } from './McpConnectionsEditor';
import type { McpServerSpec } from '../api/agents';

describe('MCP transport configuration', () => {
  it.each(['http', 'streamable-http', 'streamablehttp'])('maps %s to the Streamable HTTP editor option', transport => {
    expect(editorTransport({ name: 'github', transport })).toBe('http');
  });

  it('infers legacy transports and preserves SSE', () => {
    expect(editorTransport({ name: 'remote', url: 'https://example.com/mcp' })).toBe('http');
    expect(editorTransport({ name: 'local', command: 'mcp' })).toBe('stdio');
    expect(editorTransport({ name: 'events', transport: 'sse' })).toBe('sse');
  });

  const render = (server: McpServerSpec) => renderToStaticMarkup(
    <McpConnectionsEditor servers={[server]} tools={[]} onSave={async () => {}} />,
  );

  it.each(['http', 'streamable-http', 'sse'])('warns about ignored env on %s without exposing its values', transport => {
    const html = render({ name: 'github', transport, env: { GITHUB_PERSONAL_ACCESS_TOKEN: 'private-test-value' } });
    expect(html).toContain('This remote connection ignores them');
    expect(html).not.toContain('private-test-value');
  });

  it('does not warn for stdio env or a remote connection without env', () => {
    expect(render({ name: 'local', transport: 'stdio', env: { TOKEN: 'secret' } })).not.toContain('role="alert"');
    expect(render({ name: 'remote', url: 'https://example.com/mcp', env: {} })).not.toContain('role="alert"');
  });
});
