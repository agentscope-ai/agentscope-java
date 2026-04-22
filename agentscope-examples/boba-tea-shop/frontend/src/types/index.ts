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


export interface Message {
  id: string
  type: 'user' | 'assistant'
  content: string
  timestamp: number
  isStreaming?: boolean
}

export interface ChatConfig {
  baseUrl: string
  userId: string
  chatId: string
}

export interface ApiResponse<T = any> {
  success: boolean
  data?: T
  error?: string
  message?: string
}

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

export interface ExampleQuestion {
  id: string
  text: string
  category: 'menu' | 'order' | 'price' | 'feedback'
}

export interface Feature {
  id: string
  title: string
  description: string
  icon: string
  color: string
}


