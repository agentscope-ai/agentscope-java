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

import axios from 'axios'
import { useConfigStore } from '@/stores/config'

export interface ChatRequest {
  chat_id: string
  user_id: string
  user_query: string
}

export interface ChatResponse {
  success: boolean
  data?: string
  error?: string
}

export class ChatApiService {
  private configStore = useConfigStore()

  async sendMessage(query: string): Promise<ReadableStream<Uint8Array> | null> {
    try {
      // Build URL parameters, ensure proper encoding
      const params = new URLSearchParams({
        chat_id: this.configStore.chatId,
        user_id: this.configStore.userId,
        user_query: query
      })

      const url = `${this.configStore.apiUrl}?${params}`

      const response = await fetch(url, {
        method: 'GET',
        mode: 'cors',
        credentials: 'omit',
        headers: {
          'Accept': 'text/event-stream',
        }
      })

      if (!response.ok) {
        const errorText = await response.text()
        console.error('API Error Response:', errorText)
        throw new Error(`HTTP error! status: ${response.status}, message: ${errorText}`)
      }

      if (!response.body) {
        throw new Error('No response body')
      }

      return response.body
    } catch (error) {
      console.error('Chat API error:', error)
      throw error
    }
  }

  async testConnection(): Promise<boolean> {
    try {
      const response = await axios.get(`${this.configStore.baseUrl}/actuator/health`, {
        timeout: 5000
      })
      return response.status === 200
    } catch (error) {
      console.error('Connection test failed:', error)
      return false
    }
  }
}

export const chatApiService = new ChatApiService()


