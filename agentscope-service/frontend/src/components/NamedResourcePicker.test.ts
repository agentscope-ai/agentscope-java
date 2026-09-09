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
import { filterNamedResourceOptions, type NamedResourceOption } from './namedResourceOptions';

const options: NamedResourceOption[] = [
  { id: 'team-2', label: 'Release Team', secondary: 'Team' },
  { id: 'workflow-1', label: 'Incident Review', secondary: 'Workflow' },
  { id: 'team-1', label: 'Billing Team', secondary: 'Team' },
];

describe('filterNamedResourceOptions', () => {
  it('sorts resource names for predictable dropdowns', () => {
    expect(filterNamedResourceOptions(options, '').map(option => option.label))
      .toEqual(['Billing Team', 'Incident Review', 'Release Team']);
  });

  it('searches name, type label, and stable ID', () => {
    expect(filterNamedResourceOptions(options, 'workflow').map(option => option.id))
      .toEqual(['workflow-1']);
    expect(filterNamedResourceOptions(options, 'team-2').map(option => option.label))
      .toEqual(['Release Team']);
  });
});
