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

import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import {
  type Locale,
  translate,
  type TranslationFunction,
} from './translate';

export const LOCALE_STORAGE_KEY = 'agentscope.locale';

type I18nContextValue = {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  t: TranslationFunction;
};

const I18nContext = createContext<I18nContextValue | undefined>(undefined);

function isLocale(value: string | null): value is Locale {
  return value === 'zh' || value === 'en';
}

export function detectLocale(): Locale {
  try {
    if (typeof window !== 'undefined') {
      const stored = window.localStorage.getItem(LOCALE_STORAGE_KEY);
      if (isLocale(stored)) return stored;
    }
  } catch {
    // Storage can be disabled by browser privacy settings.
  }

  try {
    if (
      typeof navigator !== 'undefined' &&
      navigator.language.toLowerCase().startsWith('zh')
    ) {
      return 'zh';
    }
  } catch {
    // Access to navigator can be restricted in non-browser runtimes.
  }

  return 'en';
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>(detectLocale);

  const setLocale = useCallback((nextLocale: Locale) => {
    setLocaleState(nextLocale);

    try {
      if (typeof window !== 'undefined') {
        window.localStorage.setItem(LOCALE_STORAGE_KEY, nextLocale);
      }
    } catch {
      // Locale switching must still work when storage is unavailable.
    }
  }, []);

  useEffect(() => {
    if (typeof window === 'undefined') return;

    const handleStorage = (event: StorageEvent) => {
      if (
        event.key !== LOCALE_STORAGE_KEY ||
        !isLocale(event.newValue)
      ) {
        return;
      }

      try {
        if (
          event.storageArea !== null &&
          event.storageArea !== window.localStorage
        ) {
          return;
        }
      } catch {
        // Ignore events that cannot be verified as local-storage updates.
        return;
      }

      setLocaleState(event.newValue);
    };

    window.addEventListener('storage', handleStorage);
    return () => window.removeEventListener('storage', handleStorage);
  }, []);

  useEffect(() => {
    if (typeof document !== 'undefined') {
      document.documentElement.lang = locale;
    }
  }, [locale]);

  const t = useCallback<TranslationFunction>(
    (key, params) => translate(locale, key, params),
    [locale],
  );
  const value = useMemo(
    () => ({ locale, setLocale, t }),
    [locale, setLocale, t],
  );

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nContextValue {
  const context = useContext(I18nContext);
  if (!context) {
    throw new Error('useI18n must be used within an I18nProvider');
  }
  return context;
}

export function useT(): TranslationFunction {
  return useI18n().t;
}
