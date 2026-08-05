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

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/lib/apiClient';
import { fetchSessionMessages, type SessionMessageItem, type SessionMessagePage } from '../api';

const PAGE_SIZE = 100;
const MAX_WINDOW = 500;

function formatMessagesError(err: unknown): string {
  if (err instanceof ApiError) {
    let detail = err.body || err.message;
    try {
      const parsed = JSON.parse(err.body) as { error?: string };
      if (parsed.error) detail = parsed.error;
    } catch {
      /* keep raw body */
    }
    if (err.status === 501) {
      return `Transcript unavailable and live message-query is not advertised (${detail}). Configure AISTIO_TRANSCRIPT_FS_ROOT (or object-store transcript) on the control plane, or enable message-query on a live instance.`;
    }
    if (err.status === 404) {
      return `Messages not found on data plane (${detail}). If the instance is gone, ensure control-plane transcript storage is configured.`;
    }
    if (err.status === 502 || err.status === 503) {
      return `Could not load messages (${detail}). Transcript miss fell back to a live instance that is unreachable.`;
    }
    return detail || `Failed to load messages (HTTP ${err.status})`;
  }
  return err instanceof Error ? err.message : String(err);
}

function mergeBySeq(base: SessionMessageItem[], extra: SessionMessageItem[]): SessionMessageItem[] {
  const map = new Map<number, SessionMessageItem>();
  for (const m of base) {
    if (m.seq != null) map.set(m.seq, m);
  }
  for (const m of extra) {
    if (m.seq != null) map.set(m.seq, m);
  }
  return [...map.values()].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0));
}

export function useSessionMessages(
  sessionId: string,
  opts: { agent?: string; namespace?: string; enabled: boolean; pollMs?: number },
) {
  const { agent, namespace, enabled, pollMs = 5_000 } = opts;
  const [messages, setMessages] = useState<SessionMessageItem[]>([]);
  const [offset, setOffset] = useState(0);
  const [total, setTotal] = useState(0);
  const [source, setSource] = useState<string | undefined>();
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadingEarlier, setLoadingEarlier] = useState(false);
  const [loadedEarlier, setLoadedEarlier] = useState(false);
  const hydratedRef = useRef(false);
  const stateRef = useRef({ offset: 0, messages: [] as SessionMessageItem[], loadedEarlier: false });

  stateRef.current = { offset, messages, loadedEarlier };

  const applyPage = useCallback((page: SessionMessagePage, mode: 'replace' | 'prepend' | 'mergeTail') => {
    setTotal(page.total);
    setSource(page.source);
    setError(null);
    if (mode === 'replace') {
      setMessages(page.messages || []);
      setOffset(page.offset);
      return;
    }
    if (mode === 'prepend') {
      setMessages((prev) => mergeBySeq(page.messages || [], prev));
      setOffset(page.offset);
      return;
    }
    setMessages((prev) => mergeBySeq(prev, page.messages || []));
    setTotal(page.total);
  }, []);

  const loadTail = useCallback(async () => {
    const page = await fetchSessionMessages(sessionId, {
      limit: PAGE_SIZE,
      fromEnd: true,
      agent,
      namespace,
    });
    applyPage(page, 'replace');
    setLoadedEarlier(false);
    hydratedRef.current = true;
  }, [sessionId, agent, namespace, applyPage]);

  const refresh = useCallback(async () => {
    if (!sessionId || !enabled) return;
    const { offset: off, messages: cur, loadedEarlier: earlier } = stateRef.current;
    try {
      if (!hydratedRef.current) {
        setLoading(true);
        await loadTail();
        return;
      }
      if (!earlier) {
        const page = await fetchSessionMessages(sessionId, {
          limit: PAGE_SIZE,
          fromEnd: true,
          agent,
          namespace,
        });
        applyPage(page, 'replace');
        return;
      }
      const span = Math.min(MAX_WINDOW, Math.max(PAGE_SIZE, cur.length));
      const page = await fetchSessionMessages(sessionId, {
        offset: off,
        limit: span,
        agent,
        namespace,
      });
      applyPage(page, 'replace');
      // Append any newer messages beyond the loaded window.
      if (page.total > page.offset + page.messages.length) {
        const tail = await fetchSessionMessages(sessionId, {
          limit: PAGE_SIZE,
          fromEnd: true,
          agent,
          namespace,
        });
        applyPage(tail, 'mergeTail');
      }
    } catch (err) {
      setError(formatMessagesError(err));
    } finally {
      setLoading(false);
    }
  }, [sessionId, enabled, agent, namespace, loadTail, applyPage]);

  const loadEarlier = useCallback(async () => {
    if (!sessionId || offset <= 0 || loadingEarlier) return;
    setLoadingEarlier(true);
    setError(null);
    try {
      const lim = Math.min(PAGE_SIZE, offset);
      const newOffset = Math.max(0, offset - lim);
      const page = await fetchSessionMessages(sessionId, {
        offset: newOffset,
        limit: lim,
        agent,
        namespace,
      });
      applyPage(page, 'prepend');
      setLoadedEarlier(true);
    } catch (err) {
      setError(formatMessagesError(err));
    } finally {
      setLoadingEarlier(false);
    }
  }, [sessionId, offset, loadingEarlier, agent, namespace, applyPage]);

  useEffect(() => {
    hydratedRef.current = false;
    setMessages([]);
    setOffset(0);
    setTotal(0);
    setSource(undefined);
    setError(null);
    setLoadedEarlier(false);
    if (!enabled || !sessionId) return;
    void refresh();
  }, [sessionId, agent, namespace, enabled]); // eslint-disable-line react-hooks/exhaustive-deps -- intentional reset on identity change

  useEffect(() => {
    if (!enabled || !sessionId || pollMs <= 0) return;
    const id = window.setInterval(() => {
      void refresh();
    }, pollMs);
    return () => window.clearInterval(id);
  }, [enabled, sessionId, pollMs, refresh]);

  const hasEarlier = offset > 0;
  const page: SessionMessagePage | null =
    messages.length > 0 || total > 0
      ? {
          sessionId,
          offset,
          limit: messages.length,
          total,
          messages,
          source,
        }
      : null;

  return {
    page,
    messages,
    total,
    offset,
    source,
    error,
    loading: loading && !hydratedRef.current,
    loadingEarlier,
    hasEarlier,
    loadEarlier,
    refresh,
    pageSize: PAGE_SIZE,
  };
}
