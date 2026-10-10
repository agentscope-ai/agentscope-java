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

import { api } from '@/lib/apiClient';

export type ChatStatus = 'active' | 'archived' | 'deleted';

export interface Chat {
  id: string;
  tenant: string;
  namespace: string;
  creatorRef: string;
  agentId: string;
  agentName: string;
  sessionId: string;
  runtimeSessionId: string;
  title: string;
  status: ChatStatus;
  pinned: boolean;
  lastReadSeq: number;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface ChatAgent {
  id: string;
  name: string;
  description?: string;
  capability: { state: string; reason: string };
}

export const listChats = (tenant: string, namespace: string, view: ChatStatus = 'active') =>
  api.get<{ items: Chat[] }>(`/api/v1/chats?tenant=${encodeURIComponent(tenant)}&namespace=${encodeURIComponent(namespace)}${view === 'active' ? '' : `&${view}=true`}`);

export const deleteChat = (chat: Chat) =>
  api.delete<{ chat: Chat }>(`/api/v1/chats/${encodeURIComponent(chat.id)}`);

export const listChatAgents = (tenant: string, namespace: string) =>
  api.get<{ items: ChatAgent[] }>(`/api/v1/chat-agents?tenant=${encodeURIComponent(tenant)}&namespace=${encodeURIComponent(namespace)}`);

export const createChat = (body: { tenant: string; namespace: string; agentId: string; title?: string }) =>
  api.post<{ chat: Chat }>('/api/v1/chats', body);

export const getChat = (id: string) =>
  api.get<{ chat: Chat }>(`/api/v1/chats/${encodeURIComponent(id)}`);

export const updateChat = (chat: Chat, patch: { title?: string; status?: ChatStatus; pinned?: boolean; lastReadSeq?: number }) =>
  api.patch<{ chat: Chat }>(`/api/v1/chats/${encodeURIComponent(chat.id)}`, { ...patch, version: chat.version });

export const sendChatTurn = (id: string, message: string) =>
  api.post<Record<string, unknown>>(`/api/v1/chats/${encodeURIComponent(id)}/turns`, { message });
