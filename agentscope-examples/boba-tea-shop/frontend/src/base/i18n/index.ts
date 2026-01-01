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

import { createI18n } from 'vue-i18n'
import zh from './zh'
import en from './en'

const STORAGE_KEY = 'boba-tea-shop-locale'

// Get saved locale from localStorage, default to browser language or 'zh'
const getDefaultLocale = (): string => {
  // First check localStorage
  const savedLocale = localStorage.getItem(STORAGE_KEY)
  if (savedLocale && ['zh', 'en'].includes(savedLocale)) {
    return savedLocale
  }

  // Then check browser language
  const browserLang = navigator.language.toLowerCase()
  if (browserLang.startsWith('zh')) {
    return 'zh'
  }
  if (browserLang.startsWith('en')) {
    return 'en'
  }

  // Default to Chinese
  return 'zh'
}

const messages = {
  zh,
  en
}

export const i18n = createI18n({
  legacy: false,
  locale: getDefaultLocale(),
  fallbackLocale: 'en',
  messages
})

// Helper function to switch locale
export const setLocale = (locale: string) => {
  if (['zh', 'en'].includes(locale)) {
    i18n.global.locale.value = locale as 'zh' | 'en'
    localStorage.setItem(STORAGE_KEY, locale)
    // Update document lang attribute for accessibility
    document.documentElement.lang = locale === 'zh' ? 'zh-CN' : 'en'
  }
}

// Helper function to get current locale
export const getLocale = (): string => {
  return i18n.global.locale.value
}

// Helper function to toggle between zh and en
export const toggleLocale = () => {
  const currentLocale = getLocale()
  const newLocale = currentLocale === 'zh' ? 'en' : 'zh'
  setLocale(newLocale)
  return newLocale
}
