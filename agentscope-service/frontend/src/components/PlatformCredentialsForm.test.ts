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
import type { ChannelFieldSpec, ChannelTypeSpec } from '../api/channels';
import {
  credentialsFromProperties,
  propertiesFromCredentials,
} from './PlatformCredentialsForm';

const field = (key: string): ChannelFieldSpec => ({
  key,
  label: key,
  required: false,
  secret: false,
  inputType: 'text',
});

const spec: ChannelTypeSpec = {
  type: 'prototype-keys',
  label: 'Prototype keys',
  transport: 'stream',
  fields: [field('constructor'), field('__proto__')],
};

describe('PlatformCredentialsForm property conversion', () => {
  it('does not read inherited values from empty records', () => {
    const credentials = credentialsFromProperties(spec, {});
    const properties = propertiesFromCredentials(spec, {}, false);

    expect(credentials.constructor).toBe('');
    expect(credentials.__proto__).toBe('');
    expect(Object.prototype.hasOwnProperty.call(credentials, 'constructor')).toBe(true);
    expect(Object.prototype.hasOwnProperty.call(credentials, '__proto__')).toBe(true);
    expect(Object.keys(properties)).toEqual([]);
  });

  it('round-trips constructor and __proto__ as own data properties', () => {
    const original = Object.fromEntries([
      ['constructor', 'constructor-value'],
      ['__proto__', 'proto-value'],
    ]);

    const credentials = credentialsFromProperties(spec, original);
    const properties = propertiesFromCredentials(spec, credentials, false);

    expect(Object.prototype.hasOwnProperty.call(properties, 'constructor')).toBe(true);
    expect(Object.prototype.hasOwnProperty.call(properties, '__proto__')).toBe(true);
    expect(properties.constructor).toBe('constructor-value');
    expect(properties.__proto__).toBe('proto-value');
  });
});
