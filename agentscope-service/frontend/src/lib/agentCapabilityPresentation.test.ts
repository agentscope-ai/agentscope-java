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
import { describeAgentCapability } from './agentCapabilityPresentation';

describe('agent capability presentation', () => {
  it('maps an operable capability to its console destination', () => {
    expect(describeAgentCapability('session-abort')).toMatchObject({
      title: 'Turn interruption',
      category: 'Session control',
      actionLabel: 'Manage sessions',
      destination: 'sessions',
    });
  });

  it('keeps unknown runtime extensions visible without inventing an action', () => {
    expect(describeAgentCapability('custom_runtime_feature')).toEqual({
      title: 'Custom Runtime Feature',
      description: 'This runtime-specific capability has no dedicated console workflow mapped yet.',
      category: 'Extension',
    });
  });

  it('does not expose an action before the console implements the workflow', () => {
    expect(describeAgentCapability('export-transcript')).not.toHaveProperty('destination');
    expect(describeAgentCapability('subagent-task-command')).not.toHaveProperty('actionLabel');
  });
});
