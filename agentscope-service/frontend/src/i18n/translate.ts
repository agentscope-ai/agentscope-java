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

import { en, type TranslationKey } from './messages/en';
import { zh } from './messages/zh';

export type Locale = 'zh' | 'en';
export type TranslationParams = Record<string, string | number>;
export type TranslationFunction = (
  key: TranslationKey,
  params?: TranslationParams,
) => string;

export type TranslationCatalog = Record<
  Locale,
  Readonly<Partial<Record<TranslationKey, string>>>
>;

export const messages: TranslationCatalog = { en, zh };

export function interpolate(
  template: string,
  params?: TranslationParams,
): string {
  if (!params) return template;

  return template.replace(/\{(\w+)\}/g, (placeholder, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name)
      ? String(params[name])
      : placeholder,
  );
}

export function translateFromCatalog(
  catalog: TranslationCatalog,
  locale: Locale,
  key: TranslationKey,
  params?: TranslationParams,
): string {
  const template = catalog[locale][key] ?? catalog.en[key] ?? key;
  return interpolate(template, params);
}

export function translate(
  locale: Locale,
  key: TranslationKey,
  params?: TranslationParams,
): string {
  return translateFromCatalog(messages, locale, key, params);
}

export type { TranslationKey };
