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

/* Copyright 2024-2026 the original author or authors. */

import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { getToken } from '@/lib/apiClient';

type CollaborationEvent = {
  aggregateType?: string;
  aggregateId?: string;
  eventType?: string;
};

// Realtime delivery is a cache-invalidation hint. REST remains authoritative,
// and the existing query intervals provide recovery after a disconnect.
export function useCollaborationEvents(tenant: string, namespace: string) {
  const queryClient = useQueryClient();
  useEffect(() => {
    let socket: WebSocket | undefined;
    let timer: number | undefined;
    let stopped = false;
    let retry = 1_000;

    const connect = () => {
      if (stopped) return;
      const token = getToken();
      const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const url = `${scheme}//${window.location.host}/api/v1/events?tenant=${encodeURIComponent(tenant)}&namespace=${encodeURIComponent(namespace)}`;
      const protocols = token ? ['aistio.v1', `aistio.jwt.${token}`] : ['aistio.v1'];
      socket = new WebSocket(url, protocols);
      socket.onopen = () => { retry = 1_000; };
      socket.onmessage = (message) => {
        try {
          const event = JSON.parse(String(message.data)) as CollaborationEvent;
          void queryClient.invalidateQueries({ queryKey: ['issues'] });
          void queryClient.invalidateQueries({ queryKey: ['tasks'] });
          void queryClient.invalidateQueries({ queryKey: ['inbox'] });
          void queryClient.invalidateQueries({ queryKey: ['inbox-item'] });
          void queryClient.invalidateQueries({ queryKey: ['inbox-summary'] });
          void queryClient.invalidateQueries({ queryKey: ['approval'] });
          void queryClient.invalidateQueries({ queryKey: ['approvals'] });
          void queryClient.invalidateQueries({ queryKey: ['teams'] });
          if (event.aggregateType === 'issue' && event.aggregateId) {
            void queryClient.invalidateQueries({ queryKey: ['issue', event.aggregateId] });
            void queryClient.invalidateQueries({ queryKey: ['comments', event.aggregateId] });
          }
          if (event.aggregateType === 'agent-task' && event.aggregateId) {
            void queryClient.invalidateQueries({ queryKey: ['task', event.aggregateId] });
          }
        } catch {
          // Unknown future event versions are safely ignored.
        }
      };
      socket.onclose = () => {
        if (stopped) return;
        timer = window.setTimeout(connect, retry);
        retry = Math.min(retry * 2, 30_000);
      };
    };

    connect();
    return () => {
      stopped = true;
      if (timer !== undefined) window.clearTimeout(timer);
      socket?.close();
    };
  }, [namespace, queryClient, tenant]);
}
