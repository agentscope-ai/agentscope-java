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
import { ref } from 'vue'

export interface Message {
  id: string
  type: 'user' | 'assistant'
  content: string
  timestamp: number
  isStreaming?: boolean
}

export const useChatStore = defineStore('chat', () => {
  // State
  const messages = ref<Message[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  // Actions
  function addMessage(message: Omit<Message, 'id' | 'timestamp'>) {
    const newMessage: Message = {
      ...message,
      id: Date.now().toString(),
      timestamp: Date.now()
    }
    messages.value.push(newMessage)
  }

  function updateLastMessage(content: string, isStreaming = false) {
    const lastMessage = messages.value[messages.value.length - 1]
    if (lastMessage && lastMessage.type === 'assistant') {
      lastMessage.content = content
      lastMessage.isStreaming = isStreaming
    }
  }

  function clearMessages() {
    messages.value = []
    error.value = null
  }

  function setLoading(loading: boolean) {
    isLoading.value = loading
  }

  function setError(errorMessage: string | null) {
    error.value = errorMessage
  }

  function removeMessage(id: string) {
    const index = messages.value.findIndex(msg => msg.id === id)
    if (index > -1) {
      messages.value.splice(index, 1)
    }
  }

  return {
    messages,
    isLoading,
    error,
    addMessage,
    updateLastMessage,
    clearMessages,
    setLoading,
    setError,
    removeMessage
  }
})


