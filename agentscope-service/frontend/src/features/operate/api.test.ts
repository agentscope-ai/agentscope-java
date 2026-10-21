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

import { describe, expect, it } from 'vitest';
import { agentSessionDetailPath, sessionDetailPath } from './api';

describe('sessionDetailPath', () => {
  it('uses the Agent-scoped detail route when ownership is known', () => {
    expect(agentSessionDetailPath('agent-id', { id: 'session-id' })).toBe(
      '/agent-center/agents/agent-id/sessions/session-id',
    );
  });

  it('opens the canonical Work Hub detail page for a stored session', () => {
    expect(sessionDetailPath({ id: 'store/id', sessionId: 'runtime-session' })).toBe(
      '/work/sessions/store%2Fid',
    );
  });

  it('preserves runtime lookup dimensions when no store id is available', () => {
    expect(
      sessionDetailPath({
        sessionId: 'runtime/id',
        agentName: 'paw agent',
        namespace: 'default',
      }),
    ).toBe(
      '/work/sessions/runtime%2Fid?agent=paw+agent&namespace=default',
    );
  });
});
