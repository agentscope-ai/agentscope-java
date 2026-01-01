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

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export interface ConfigState {
  baseUrl: string
  userId: string
  chatId: string
}

export const useConfigStore = defineStore('config', () => {
  // Read initial baseUrl from window config (injected at runtime) or use default
  const getInitialBaseUrl = () => {
    const windowConfig = (window as any).__APP_CONFIG__
    if (windowConfig && windowConfig.API_BASE_URL && windowConfig.API_BASE_URL !== '__API_BASE_URL__') {
      return windowConfig.API_BASE_URL
    }
    // Default to localhost:10008 for local development
    return 'http://localhost:10008'
  }

  // State
  const baseUrl = ref(getInitialBaseUrl())
  const userId = ref('')
  const chatId = ref('')

  // Getters
  const apiUrl = computed(() => `${baseUrl.value}/api/assistant/chat`)

  // Actions
  function updateConfig(newConfig: Partial<ConfigState>) {
    if (newConfig.baseUrl !== undefined) {
      baseUrl.value = newConfig.baseUrl
    }
    if (newConfig.userId !== undefined) {
      userId.value = newConfig.userId
    }
    if (newConfig.chatId !== undefined) {
      chatId.value = newConfig.chatId
    }

    // Save to localStorage
    localStorage.setItem('milk-tea-config', JSON.stringify({
      baseUrl: baseUrl.value,
      userId: userId.value,
      chatId: chatId.value
    }))
  }

  function loadConfig() {
    const saved = localStorage.getItem('milk-tea-config')
    if (saved) {
      try {
        const config = JSON.parse(saved)
        baseUrl.value = config.baseUrl || 'http://localhost:10008'
        userId.value = config.userId || ''
        // Load saved chatId if exists
        chatId.value = config.chatId || ''
      } catch (error) {
        console.error('Failed to load config:', error)
      }
    }

    // Only generate new chatId when there is none
    if (!chatId.value) {
      generateNewChatId()
    }
  }

  function generateNewChatId() {
    chatId.value = Date.now().toString()
    updateConfig({ chatId: chatId.value })
  }

  function initializeChatId() {
    // Generate new chat_id on each initialization
    generateNewChatId()
  }

  return {
    baseUrl,
    userId,
    chatId,
    apiUrl,
    updateConfig,
    loadConfig,
    generateNewChatId,
    initializeChatId
  }
})


