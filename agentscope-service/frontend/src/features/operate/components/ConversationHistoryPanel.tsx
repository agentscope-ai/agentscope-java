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

import { useEffect, useMemo, useRef, useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { JsonViewer } from '@/components/JsonViewer';
import type { SessionTurn } from '../api';
import {
  extractHistoryMessages,
  groupMessagesByTurns,
} from '../lib/groupMessagesByTurns';
import { MessageItems, MessagesList } from './MessagesList';

function formatDuration(ms?: number) {
  if (ms == null || ms < 0) return '—';
  const sec = Math.floor(ms / 1000);
  if (sec < 60) return `${sec}s`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ${sec % 60}s`;
  const hr = Math.floor(min / 60);
  return `${hr}h ${min % 60}m`;
}

function formatTime(v?: string) {
  if (!v) return '—';
  try {
    return new Date(v).toLocaleString();
  } catch {
    return v;
  }
}

function statusTone(status?: string): 'default' | 'success' | 'warning' | 'danger' | 'info' {
  switch ((status || '').toLowerCase()) {
    case 'running':
      return 'warning';
    case 'completed':
      return 'success';
    case 'aborted':
    case 'failed':
      return 'danger';
    default:
      return 'default';
  }
}

type Density = 'by-turn' | 'flat';

export function ConversationHistoryPanel({
  turns = [],
  turnsLoading,
  messagesData,
  messagesLoading,
  messagesError,
  source,
  total,
  loadedCount,
  hasEarlier,
  loadingEarlier,
  onLoadEarlier,
  sessionPending,
  selectedTurnIndex,
  deepLinkTurnIndex,
  onSelectTurn,
}: {
  turns?: SessionTurn[];
  turnsLoading?: boolean;
  messagesData?: unknown;
  messagesLoading?: boolean;
  messagesError?: string | null;
  source?: string;
  total?: number;
  loadedCount?: number;
  hasEarlier?: boolean;
  loadingEarlier?: boolean;
  onLoadEarlier?: () => void;
  /** True while the parent session query has not resolved. */
  sessionPending?: boolean;
  selectedTurnIndex?: number | null;
  /** When set (e.g. from ?turn=), scroll to that turn header (still collapsed). */
  deepLinkTurnIndex?: number | null;
  onSelectTurn?: (turn: SessionTurn) => void;
}) {
  const [density, setDensity] = useState<Density>('by-turn');
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const scrollTargetRef = useRef<HTMLDivElement | null>(null);
  const didScrollRef = useRef<number | null>(null);

  const historyMessages = useMemo(
    () => extractHistoryMessages(messagesData),
    [messagesData],
  );

  const groups = useMemo(
    () => groupMessagesByTurns(turns, historyMessages || []),
    [turns, historyMessages],
  );

  useEffect(() => {
    if (density !== 'by-turn' || deepLinkTurnIndex == null) return;
    if (didScrollRef.current === deepLinkTurnIndex) return;
    const el = scrollTargetRef.current;
    if (el) {
      didScrollRef.current = deepLinkTurnIndex;
      el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
  }, [density, deepLinkTurnIndex, groups]);

  function toggle(key: string) {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  const turnsEmpty = !turnsLoading && turns.length === 0;
  const showLoading =
    sessionPending ||
    (messagesLoading && historyMessages == null) ||
    (turnsLoading && turns.length === 0 && historyMessages == null);

  const sourceLabel =
    source === 'transcript' ? 'transcript store' : source === 'dataplane' ? 'live instance' : null;

  const rangeLabel =
    total != null && loadedCount != null && total > 0
      ? `Showing ${loadedCount.toLocaleString()} of ${total.toLocaleString()}`
      : null;

  const pager = (
    <div className="flex flex-wrap items-center gap-2 pb-2">
      {hasEarlier && (
        <Button
          size="sm"
          variant="outline"
          disabled={!!loadingEarlier}
          onClick={() => onLoadEarlier?.()}
        >
          {loadingEarlier ? 'Loading…' : 'Load earlier'}
        </Button>
      )}
      {rangeLabel && <span className="text-sm text-muted-foreground">{rangeLabel}</span>}
      {sourceLabel && <Badge tone="info">{sourceLabel}</Badge>}
    </div>
  );

  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-3 space-y-0">
        <div>
          <CardTitle>Conversation history</CardTitle>
          <CardDescription>
            Message-level transcript (tools structured). Prefer control-plane transcript; live
            instance is fallback only.
          </CardDescription>
        </div>
        <div className="flex shrink-0 rounded-lg border border-border p-0.5">
          <Button
            size="sm"
            variant={density === 'by-turn' ? 'secondary' : 'ghost'}
            className="h-8"
            onClick={() => setDensity('by-turn')}
          >
            By turn
          </Button>
          <Button
            size="sm"
            variant={density === 'flat' ? 'secondary' : 'ghost'}
            className="h-8"
            onClick={() => setDensity('flat')}
          >
            Flat
          </Button>
        </div>
      </CardHeader>
      <CardContent>
        {messagesError && (
          <p className="mb-3 text-sm text-red-600 whitespace-pre-wrap">{messagesError}</p>
        )}
        {density === 'flat' ? (
          sessionPending ? (
            <p className="text-sm text-muted-foreground">Loading…</p>
          ) : (
            <>
              {pager}
              <MessagesList
                data={messagesData}
                loading={!!messagesLoading}
                maxHeightClass="max-h-[70vh]"
              />
            </>
          )
        ) : showLoading ? (
          <p className="text-sm text-muted-foreground">Loading conversation…</p>
        ) : turnsEmpty && (!historyMessages || historyMessages.length === 0) && !messagesError ? (
          <p className="text-sm text-muted-foreground">
            No turns or messages recorded yet. Messages come from the control-plane transcript (or
            live message-query fallback). Turns open when phase becomes active.
          </p>
        ) : historyMessages == null && messagesData != null ? (
          <JsonViewer value={messagesData} className="max-h-[50vh]" />
        ) : (
          <>
            {pager}
            <div className="max-h-[70vh] space-y-2 overflow-auto">
              {groups.map((g) => {
                if (g.kind === 'before') {
                  const key = 'before';
                  const open = expanded.has(key);
                  return (
                    <div key={key} className="rounded-lg border border-border">
                      <button
                        type="button"
                        className="flex w-full items-center gap-3 px-4 py-3 text-left text-sm hover:bg-muted/40"
                        onClick={() => toggle(key)}
                      >
                        <span className="font-mono text-muted-foreground">{open ? '▾' : '▸'}</span>
                        <span className="font-medium">Before recorded turns</span>
                        <span className="text-muted-foreground">
                          {g.messages.length} msg{g.messages.length === 1 ? '' : 's'}
                        </span>
                      </button>
                      {open && (
                        <div className="border-t border-border px-4 py-3">
                          <MessageItems messages={g.messages} />
                        </div>
                      )}
                    </div>
                  );
                }

                const t = g.turn;
                const key = `turn:${t.turnIndex}`;
                const open = expanded.has(key);
                const selected =
                  selectedTurnIndex === t.turnIndex || deepLinkTurnIndex === t.turnIndex;
                return (
                  <div
                    key={t.id || t.turnIndex}
                    ref={deepLinkTurnIndex === t.turnIndex ? scrollTargetRef : undefined}
                    className={`rounded-lg border border-border ${selected ? 'ring-1 ring-ring' : ''}`}
                  >
                    <button
                      type="button"
                      className="flex w-full flex-wrap items-center gap-x-3 gap-y-1.5 px-4 py-3 text-left text-sm hover:bg-muted/40"
                      onClick={() => {
                        toggle(key);
                        onSelectTurn?.(t);
                      }}
                    >
                      <span className="font-mono text-muted-foreground">{open ? '▾' : '▸'}</span>
                      <span className="font-mono tabular-nums font-medium">#{t.turnIndex}</span>
                      <Badge tone={statusTone(t.status)}>{t.status}</Badge>
                      <span className="font-mono tabular-nums text-muted-foreground">
                        {formatDuration(t.durationMs)}
                      </span>
                      <span className="text-muted-foreground">{formatTime(t.startedAt)}</span>
                      <span className="min-w-0 flex-1 truncate text-muted-foreground">
                        {t.userPreview || '—'}
                      </span>
                      <span className="text-muted-foreground">
                        {g.messages.length} msg{g.messages.length === 1 ? '' : 's'}
                      </span>
                    </button>
                    {open && (
                      <div className="border-t border-border px-4 py-3">
                        {messagesLoading && !historyMessages ? (
                          <p className="text-sm text-muted-foreground">Loading messages…</p>
                        ) : (
                          <MessageItems
                            messages={g.messages}
                            emptyLabel="No messages attributed to this turn."
                          />
                        )}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}
