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

import {
  endpointErrorMessage,
  endpointSlug,
  nextEndpointName,
  nextEndpointSlug,
} from './endpointNaming';

describe('Endpoint naming', () => {
  it('normalizes and increments duplicate slugs', () => {
    expect(endpointSlug(' Team 1 / API ')).toBe('team-1-api');
    expect(nextEndpointSlug('team1-a1b2c3', ['team1-a1b2c3'])).toBe('team1-a1b2c3-2');
    expect(nextEndpointSlug('team1-a1b2c3', ['team1-a1b2c3', 'team1-a1b2c3-2']))
      .toBe('team1-a1b2c3-3');
  });

  it('keeps generated slugs within the API limit', () => {
    const result = nextEndpointSlug('a'.repeat(48), ['a'.repeat(48)]);
    expect(result).toHaveLength(48);
    expect(result.endsWith('-2')).toBe(true);
  });

  it('increments duplicate names case-insensitively', () => {
    expect(nextEndpointName('Team API', ['team api', 'Team API 2'])).toBe('Team API 3');
  });

  it('extracts control-plane error messages', () => {
    expect(endpointErrorMessage(new Error('{"error":"Endpoint slug is already in use"}'), 'failed'))
      .toBe('Endpoint slug is already in use');
    expect(endpointErrorMessage(new Error('Network error'), 'failed')).toBe('Network error');
  });
});
