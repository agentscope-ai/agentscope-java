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

/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { mergeContiguousEvents } from '@/features/conversation/eventCursor';
import { fetchSessionEvents, streamSessionEvents, type SessionEventItem } from '../api';

const PAGE_SIZE = 100;

function keyOf(event: SessionEventItem, index: number): string {
  return event.id != null ? `id:${event.id}` : `seq:${event.seq ?? index}`;
}

function merge(older: SessionEventItem[], newer: SessionEventItem[]): SessionEventItem[] {
  const seen = new Set<string>();
  return [...older, ...newer].filter((event, index) => {
    const key = keyOf(event, index);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export function useSessionEvents(
  sessionId: string,
  options: { agentId?: string; chatId?: string; enabled: boolean },
) {
  const { agentId, chatId, enabled } = options;
  const [events, setEvents] = useState<SessionEventItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadingEarlier, setLoadingEarlier] = useState(false);
  const [hasEarlier, setHasEarlier] = useState(false);
  const [streamReady, setStreamReady] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const eventsRef = useRef<SessionEventItem[]>([]);
  const loadedEarlierRef = useRef(false);
  const cursorRef = useRef(0);
  const bufferedLiveRef = useRef<Map<number, SessionEventItem>>(new Map());
  const repairingRef = useRef<Promise<void> | null>(null);
  const repairTimerRef = useRef<number | null>(null);
  const generationRef = useRef(0);
  eventsRef.current = events;

  const refresh = useCallback(async () => {
    if (!sessionId || !enabled) return;
    const generation = generationRef.current;
    setLoading(eventsRef.current.length === 0);
    try {
      const result = await fetchSessionEvents(sessionId, { limit: PAGE_SIZE, agentId, chatId });
      if (generation !== generationRef.current) return;
      const incoming = result.events || [];
      cursorRef.current = Math.max(cursorRef.current, ...incoming.map((event) => event.seq ?? 0));
      setEvents((current) => merge(current, incoming));
      if (!loadedEarlierRef.current) {
        setHasEarlier((result.events || []).length >= PAGE_SIZE);
      }
      setError(null);
    } catch (cause) {
      if (generation === generationRef.current) {
        setError(cause instanceof Error ? cause.message : String(cause));
      }
    } finally {
      if (generation === generationRef.current) {
        setLoading(false);
        setStreamReady(true);
      }
    }
  }, [agentId, chatId, enabled, sessionId]);

  const loadEarlier = useCallback(async () => {
    const oldest = eventsRef.current[0];
    const before = oldest?.seq ?? oldest?.occurredAt;
    if (!sessionId || before == null || loadingEarlier) return;
    const generation = generationRef.current;
    setLoadingEarlier(true);
    try {
      const result = await fetchSessionEvents(sessionId, { limit: PAGE_SIZE, before, agentId, chatId });
      if (generation !== generationRef.current) return;
      loadedEarlierRef.current = true;
      setEvents((current) => merge(result.events || [], current));
      setHasEarlier((result.events || []).length >= PAGE_SIZE);
      setError(null);
    } catch (cause) {
      if (generation === generationRef.current) {
        setError(cause instanceof Error ? cause.message : String(cause));
      }
    } finally {
      if (generation === generationRef.current) setLoadingEarlier(false);
    }
  }, [agentId, chatId, loadingEarlier, sessionId]);

  const repairGap = useCallback(function repairGap() {
    if (repairingRef.current) return repairingRef.current;
    const generation = generationRef.current;
    const repair = (async () => {
      let retry = false;
      try {
        // A forward read is authoritative: it covers anything committed while
        // history and the live stream were being attached.
        while (true) {
          const after = cursorRef.current;
          const result = await fetchSessionEvents(sessionId, {
            limit: 500,
            after,
            agentId,
            chatId,
          });
          if (generation !== generationRef.current) return;
          const incoming = result.events || [];
          if (incoming.length === 0) break;
          const merged = mergeContiguousEvents(
            cursorRef.current,
            bufferedLiveRef.current,
            incoming,
            event => event.seq ?? 0,
          );
          cursorRef.current = merged.cursor;
          const accepted = merged.accepted;
          if (accepted.length > 0) {
            setEvents((current) => merge(current, accepted));
          }
          if (cursorRef.current === after) {
            retry = bufferedLiveRef.current.size > 0;
            break;
          }
          if (incoming.length < 500) break;
        }
        retry ||= bufferedLiveRef.current.size > 0;
        setError(null);
      } catch (cause) {
        if (generation !== generationRef.current) return;
        setError(cause instanceof Error ? cause.message : String(cause));
        retry = true;
      } finally {
        if (generation === generationRef.current) repairingRef.current = null;
      }
      if (retry && generation === generationRef.current && repairTimerRef.current == null) {
        repairTimerRef.current = window.setTimeout(() => {
          repairTimerRef.current = null;
          void repairGap();
        }, 1_000);
      }
    })();
    repairingRef.current = repair;
    return repair;
  }, [agentId, chatId, sessionId]);

  useEffect(() => {
    const generation = generationRef.current + 1;
    generationRef.current = generation;
    setEvents([]);
    loadedEarlierRef.current = false;
    cursorRef.current = 0;
    bufferedLiveRef.current.clear();
    repairingRef.current = null;
    if (repairTimerRef.current != null) window.clearTimeout(repairTimerRef.current);
    repairTimerRef.current = null;
    setStreamReady(false);
    setHasEarlier(false);
    setError(null);
    if (enabled && sessionId) void refresh();
    return () => {
      if (generationRef.current === generation) generationRef.current += 1;
      if (repairTimerRef.current != null) window.clearTimeout(repairTimerRef.current);
      repairTimerRef.current = null;
    };
  }, [agentId, chatId, enabled, sessionId]); // eslint-disable-line react-hooks/exhaustive-deps -- identity reset

  useEffect(() => {
    if (!enabled || !sessionId || !streamReady) return;
    const handle = streamSessionEvents(
      sessionId,
      (event) => {
        const seq = event.seq ?? 0;
        if (seq <= 0) {
          setEvents((current) => merge(current, [event]));
          return;
        }
        const merged = mergeContiguousEvents(
          cursorRef.current,
          bufferedLiveRef.current,
          [event],
          item => item.seq ?? 0,
        );
        cursorRef.current = merged.cursor;
        if (merged.accepted.length > 0) {
          setEvents((current) => merge(current, merged.accepted));
        }
        if (bufferedLiveRef.current.size > 0) void repairGap();
        setError(null);
      },
      (cause) => setError(cause.message),
      { agentId, chatId, getAfter: () => cursorRef.current, onOpen: () => setError(null) },
    );
    return handle.close;
  }, [agentId, chatId, enabled, repairGap, sessionId, streamReady]);

  return { events, loading, loadingEarlier, hasEarlier, error, loadEarlier, refresh };
}
