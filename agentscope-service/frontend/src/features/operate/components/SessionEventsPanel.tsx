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

import { useCallback, useEffect, useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { useI18n } from '@/i18n';
import { fetchSessionEvents, type SessionEventItem } from '../api';
import { formatToolInput } from '../lib/groupMessagesByTurns';
import { formatDateTime, formatNumber, messageRoleLabel } from '../i18n';

const PAGE_SIZE = 50;

function eventKey(e: SessionEventItem, i: number) {
  return e.id != null ? `id:${e.id}` : `seq:${e.seq ?? i}`;
}

function EventRow({ event: e }: { event: SessionEventItem }) {
  const { locale, t } = useI18n();
  const [open, setOpen] = useState(false);
  const hasDetail = !!(e.toolInput || e.toolOutput || (e.content && e.content.length > 120));
  return (
    <div className="rounded-lg border border-border text-sm">
      <button
        type="button"
        className="flex w-full flex-wrap items-center gap-x-2 gap-y-1.5 px-4 py-3 text-left hover:bg-muted/40"
        onClick={() => hasDetail && setOpen((v) => !v)}
        disabled={!hasDetail}
      >
        {hasDetail && (
          <span className="font-mono text-muted-foreground">{open ? '▾' : '▸'}</span>
        )}
        <span className="font-mono tabular-nums text-muted-foreground">#{e.seq}</span>
        <Badge tone="default">{e.eventType || t('operate.events.event')}</Badge>
        {e.role && <Badge tone="info">{messageRoleLabel(t, e.role)}</Badge>}
        {e.toolName && <Badge tone="warning">{e.toolName}</Badge>}
        {e.occurredAt && (
          <span className="text-muted-foreground">{formatDateTime(locale, e.occurredAt)}</span>
        )}
        {(e.tokensIn || e.tokensOut) && (
          <span className="font-mono text-[11px] text-muted-foreground">
            {t('operate.events.tokenCounts', {
              input: formatNumber(locale, e.tokensIn ?? 0),
              output: formatNumber(locale, e.tokensOut ?? 0),
            })}
          </span>
        )}
        {!open && (
          <span className="min-w-0 flex-1 truncate text-muted-foreground">
            {String(e.content || e.toolOutput || '').replace(/\s+/g, ' ').trim() || '—'}
          </span>
        )}
      </button>
      {open && (
        <div className="space-y-2 border-t border-border px-4 py-3">
          {e.content && (
            <pre className="max-h-48 overflow-auto whitespace-pre-wrap text-[12px] text-muted-foreground">
              {e.content}
            </pre>
          )}
          {e.toolInput != null && (
            <div>
              <div className="text-xs font-medium uppercase text-muted-foreground">{t('operate.events.toolInput')}</div>
              <pre className="max-h-48 overflow-auto whitespace-pre-wrap rounded-md bg-muted/50 px-3 py-2 font-mono text-[12px]">
                {formatToolInput(e.toolInput)}
              </pre>
            </div>
          )}
          {e.toolOutput && (
            <div>
              <div className="text-xs font-medium uppercase text-muted-foreground">{t('operate.events.toolOutput')}</div>
              <pre className="max-h-48 overflow-auto whitespace-pre-wrap rounded-md bg-muted/50 px-3 py-2 font-mono text-[12px]">
                {e.toolOutput}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export function SessionEventsPanel({ sessionId, enabled }: { sessionId: string; enabled: boolean }) {
  const { locale, t } = useI18n();
  const [events, setEvents] = useState<SessionEventItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [loadedOlder, setLoadedOlder] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [hasMore, setHasMore] = useState(false);

  const loadNewest = useCallback(async () => {
    if (!sessionId || !enabled) return;
    setLoading(true);
    setError(null);
    try {
      const res = await fetchSessionEvents(sessionId, { limit: PAGE_SIZE });
      const list = res.events || [];
      setEvents(list);
      setHasMore(list.length >= PAGE_SIZE);
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  }, [sessionId, enabled]);

  const loadOlder = useCallback(async () => {
    if (!sessionId || events.length === 0) return;
    const oldest = events[0];
    const before = oldest.seq ?? oldest.occurredAt;
    if (before == null) return;
    setLoadingOlder(true);
    setError(null);
    try {
      const res = await fetchSessionEvents(sessionId, { limit: PAGE_SIZE, before });
      const older = res.events || [];
      if (older.length === 0) {
        setHasMore(false);
        return;
      }
      setEvents((prev) => {
        const seen = new Set(prev.map((e, i) => eventKey(e, i)));
        const merged = [...older.filter((e, i) => !seen.has(eventKey(e, i))), ...prev];
        return merged;
      });
      setLoadedOlder(true);
      setHasMore(older.length >= PAGE_SIZE);
    } catch (err) {
      setError(err);
    } finally {
      setLoadingOlder(false);
    }
  }, [sessionId, events]);

  useEffect(() => {
    setEvents([]);
    setHasMore(false);
    setLoadedOlder(false);
    if (enabled && sessionId) void loadNewest();
  }, [sessionId, enabled]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!enabled || !sessionId || loadedOlder) return;
    const id = window.setInterval(() => {
      void loadNewest();
    }, 10_000);
    return () => window.clearInterval(id);
  }, [enabled, sessionId, loadedOlder, loadNewest]);

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('operate.events.title')}</CardTitle>
        <CardDescription>
          {t('operate.events.description')}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-2.5">
        {error != null && (
          <p className="text-sm text-red-600">
            {error instanceof Error ? error.message : t('operate.events.loadFailed')}
          </p>
        )}
        {loading && events.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('operate.events.loading')}</p>
        ) : events.length === 0 ? (
          <div className="space-y-1 text-sm text-muted-foreground">
            <p>{t('operate.events.empty')}</p>
            <p>
              {t('operate.events.emptyHintBefore')}{' '}
              <code className="rounded bg-muted px-1 py-0.5 text-[12px]">claw.aistio.enable-events: false</code>
              {t('operate.events.emptyHintAfter')}
            </p>
          </div>
        ) : (
          <>
            <div className="flex flex-wrap items-center gap-2">
              {hasMore && (
                <Button size="sm" variant="outline" disabled={loadingOlder} onClick={() => void loadOlder()}>
                  {loadingOlder ? t('common.loading') : t('operate.pagination.loadOlder')}
                </Button>
              )}
              <span className="text-sm text-muted-foreground">
                {t(
                  events.length === 1
                    ? 'operate.events.loadedOne'
                    : 'operate.events.loadedMany',
                  { count: formatNumber(locale, events.length) },
                )}
              </span>
            </div>
            <div className="max-h-80 space-y-2.5 overflow-auto">
              {events.map((e, i) => (
                <EventRow key={eventKey(e, i)} event={e} />
              ))}
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}
