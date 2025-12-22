/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
  // 默认使用相对路径（前端和后端部署在同一个端口）
  const getInitialBaseUrl = () => {
    const windowConfig = (window as any).__APP_CONFIG__
    if (windowConfig && windowConfig.API_BASE_URL && windowConfig.API_BASE_URL !== '__API_BASE_URL__') {
      return windowConfig.API_BASE_URL
    }
    // 使用相对路径，前端和后端在同一 origin
    return ''
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
        baseUrl.value = config.baseUrl || ''
        userId.value = config.userId || ''
        // 加载保存的chatId，如果存在的话
        chatId.value = config.chatId || ''
      } catch (error) {
        console.error('Failed to load config:', error)
      }
    }
    
    // 仅当没有chatId时才生成新的
    if (!chatId.value) {
      generateNewChatId()
    }
  }

  function generateNewChatId() {
    chatId.value = Date.now().toString()
    updateConfig({ chatId: chatId.value })
  }

  function initializeChatId() {
    // 每次初始化都生成新的chat_id
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


