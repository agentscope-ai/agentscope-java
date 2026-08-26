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

import { StrictMode } from 'react';
import { act, render, renderHook, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  detectLocale,
  I18nProvider,
  LOCALE_STORAGE_KEY,
  useI18n,
} from './I18nProvider';

function Consumer() {
  const { locale, setLocale, t } = useI18n();
  return (
    <div>
      <span>{locale}</span>
      <span>{t('language.english')}</span>
      <button type="button" onClick={() => setLocale(locale === 'en' ? 'zh' : 'en')}>
        switch
      </button>
    </div>
  );
}

describe('detectLocale', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('prefers a valid stored locale', () => {
    window.localStorage.setItem(LOCALE_STORAGE_KEY, 'en');
    vi.spyOn(window.navigator, 'language', 'get').mockReturnValue('zh-CN');
    expect(detectLocale()).toBe('en');
  });

  it.each(['zh-CN', 'zh-TW'])('detects Chinese browser locale %s', (language) => {
    vi.spyOn(window.navigator, 'language', 'get').mockReturnValue(language);
    expect(detectLocale()).toBe('zh');
  });

  it('falls back to English for an invalid stored and non-Chinese locale', () => {
    window.localStorage.setItem(LOCALE_STORAGE_KEY, 'invalid');
    vi.spyOn(window.navigator, 'language', 'get').mockReturnValue('en-US');
    expect(detectLocale()).toBe('en');
  });

  it('survives storage read failures', () => {
    vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });
    vi.spyOn(window.navigator, 'language', 'get').mockReturnValue('zh-CN');
    expect(detectLocale()).toBe('zh');
  });

  it('survives navigator access failures', () => {
    vi.spyOn(window.navigator, 'language', 'get').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });
    expect(detectLocale()).toBe('en');
  });
});

describe('I18nProvider', () => {
  beforeEach(() => {
    window.localStorage.clear();
    document.documentElement.lang = '';
    vi.spyOn(window.navigator, 'language', 'get').mockReturnValue('en-US');
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('does not persist a browser-detected locale before user selection', () => {
    const setItem = vi.spyOn(window.localStorage, 'setItem');

    render(
      <I18nProvider>
        <Consumer />
      </I18nProvider>,
    );

    expect(setItem).not.toHaveBeenCalled();
    expect(document.documentElement.lang).toBe('en');
  });

  it('switches locale, persists it, and updates the document language', () => {
    render(
      <I18nProvider>
        <Consumer />
      </I18nProvider>,
    );

    expect(screen.getByText('en')).toBeInTheDocument();
    expect(screen.getByText('English')).toBeInTheDocument();
    expect(document.documentElement.lang).toBe('en');

    act(() => screen.getByRole('button', { name: 'switch' }).click());

    expect(screen.getByText('zh')).toBeInTheDocument();
    expect(screen.getByText('英文')).toBeInTheDocument();
    expect(window.localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('zh');
    expect(document.documentElement.lang).toBe('zh');
  });

  it('keeps switching when storage writes fail under StrictMode', () => {
    vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });

    render(
      <StrictMode>
        <I18nProvider>
          <Consumer />
        </I18nProvider>
      </StrictMode>,
    );

    act(() => screen.getByRole('button', { name: 'switch' }).click());

    expect(screen.getByText('zh')).toBeInTheDocument();
    expect(document.documentElement.lang).toBe('zh');
  });

  it('throws a clear error outside the provider', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    expect(() => renderHook(() => useI18n())).toThrow(
      'useI18n must be used within an I18nProvider',
    );
  });
});
