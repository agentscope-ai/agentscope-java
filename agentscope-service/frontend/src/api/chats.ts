import { api } from '@/lib/apiClient';

export type ChatStatus = 'active' | 'archived';

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

export const listChats = (tenant: string, namespace: string, archived = false) =>
  api.get<{ items: Chat[] }>(`/api/v1/chats?tenant=${encodeURIComponent(tenant)}&namespace=${encodeURIComponent(namespace)}${archived ? '&archived=true' : ''}`);

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
