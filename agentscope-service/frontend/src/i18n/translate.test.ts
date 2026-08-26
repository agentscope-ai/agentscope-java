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
import { en } from './messages/en';
import { zh } from './messages/zh';
import {
  interpolate,
  messages,
  translate,
  translateFromCatalog,
  type TranslationCatalog,
} from './translate';

describe('translation catalog', () => {
  it('keeps English and Chinese keys paired and values non-empty', () => {
    expect(Object.keys(zh).sort()).toEqual(Object.keys(en).sort());
    expect(Object.values(messages).flatMap(Object.values)).toSatisfy(
      (values: string[]) => values.every((value) => value.trim().length > 0),
    );
  });

  it('falls back to English when the selected locale lacks a value', () => {
    const catalog: TranslationCatalog = {
      en,
      zh: {},
    };

    expect(translateFromCatalog(catalog, 'zh', 'language.english')).toBe(
      'English',
    );
  });

  it('returns the selected locale value', () => {
    expect(translate('zh', 'language.switchToEnglish')).toBe('切换到英文');
  });

  it('keeps interpolation parameters paired across locales', () => {
    const tokens = (value: string) =>
      Array.from(value.matchAll(/\{(\w+)\}/g), (match) => match[1]).sort();

    for (const key of Object.keys(en) as Array<keyof typeof en>) {
      expect(tokens(zh[key]), key).toEqual(tokens(en[key]));
    }
  });

  it('retains the consequences of destructive actions in both locales', () => {
    expect(
      translate('en', 'managed.memory.confirmRedact', { path: 'profile.md' }),
    ).toContain('Version history will be cleared');
    expect(
      translate('zh', 'managed.memory.confirmRedact', { path: 'profile.md' }),
    ).toContain('清除版本历史');
    expect(
      translate('en', 'managed.channels.confirmDelete', {
        channelId: 'support',
      }),
    ).toContain('all bindings');
    expect(translate('en', 'managed.vaults.confirmDelete')).toContain(
      'all credentials',
    );
    expect(translate('zh', 'managed.vaults.confirmArchive')).toContain(
      '不再向会话注入凭据',
    );
  });
});

describe('interpolate', () => {
  it('replaces string, number, zero, and empty-string parameters', () => {
    expect(
      interpolate('{name}:{count}:{zero}:{empty}', {
        name: 'agent',
        count: 2,
        zero: 0,
        empty: '',
      }),
    ).toBe('agent:2:0:');
  });

  it('leaves missing parameters visible', () => {
    expect(interpolate('Hello, {name}', {})).toBe('Hello, {name}');
  });
});
