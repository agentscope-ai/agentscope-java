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
import { useI18n } from '@/i18n';
import type { SessionTurn } from '../api';
import {
  extractHistoryMessages,
  groupMessagesByTurns,
} from '../lib/groupMessagesByTurns';
import { MessageItems, MessagesList } from './MessagesList';
import { formatDateTime, formatDuration, formatNumber, statusLabel } from '../i18n';

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
  const { locale, t } = useI18n();
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

  const sourceLabel = source === 'transcript'
    ? t('operate.conversation.source.transcript')
    : source === 'dataplane'
      ? t('operate.conversation.source.dataplane')
      : null;

  const rangeLabel =
    total != null && loadedCount != null && total > 0
      ? t('operate.conversation.showingCount', {
          loaded: formatNumber(locale, loadedCount),
          total: formatNumber(locale, total),
        })
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
          {loadingEarlier ? t('common.loading') : t('operate.pagination.loadEarlier')}
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
          <CardTitle>{t('operate.conversation.title')}</CardTitle>
          <CardDescription>
            {t('operate.conversation.description')}
          </CardDescription>
        </div>
        <div className="flex shrink-0 rounded-lg border border-border p-0.5">
          <Button
            size="sm"
            variant={density === 'by-turn' ? 'secondary' : 'ghost'}
            className="h-8"
            onClick={() => setDensity('by-turn')}
          >
            {t('operate.conversation.byTurn')}
          </Button>
          <Button
            size="sm"
            variant={density === 'flat' ? 'secondary' : 'ghost'}
            className="h-8"
            onClick={() => setDensity('flat')}
          >
            {t('operate.conversation.flat')}
          </Button>
        </div>
      </CardHeader>
      <CardContent>
        {messagesError && (
          <p className="mb-3 text-sm text-red-600 whitespace-pre-wrap">{messagesError}</p>
        )}
        {density === 'flat' ? (
          sessionPending ? (
            <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
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
          <p className="text-sm text-muted-foreground">{t('chat.loadingConversation')}</p>
        ) : turnsEmpty && (!historyMessages || historyMessages.length === 0) && !messagesError ? (
          <p className="text-sm text-muted-foreground">
            {t('operate.conversation.empty')}
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
                        <span className="font-medium">{t('operate.conversation.beforeTurns')}</span>
                        <span className="text-muted-foreground">
                          {t(
                            g.messages.length === 1
                              ? 'operate.message.countOne'
                              : 'operate.message.countMany',
                            { count: formatNumber(locale, g.messages.length) },
                          )}
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

                const turn = g.turn;
                const key = `turn:${turn.turnIndex}`;
                const open = expanded.has(key);
                const selected =
                  selectedTurnIndex === turn.turnIndex || deepLinkTurnIndex === turn.turnIndex;
                return (
                  <div
                    key={turn.id || turn.turnIndex}
                    ref={deepLinkTurnIndex === turn.turnIndex ? scrollTargetRef : undefined}
                    className={`rounded-lg border border-border ${selected ? 'ring-1 ring-ring' : ''}`}
                  >
                    <button
                      type="button"
                      className="flex w-full flex-wrap items-center gap-x-3 gap-y-1.5 px-4 py-3 text-left text-sm hover:bg-muted/40"
                      onClick={() => {
                        toggle(key);
                        onSelectTurn?.(turn);
                      }}
                    >
                      <span className="font-mono text-muted-foreground">{open ? '▾' : '▸'}</span>
                      <span className="font-mono tabular-nums font-medium">#{turn.turnIndex}</span>
                      <Badge tone={statusTone(turn.status)}>{statusLabel(t, turn.status)}</Badge>
                      <span className="font-mono tabular-nums text-muted-foreground">
                        {formatDuration(t, locale, turn.durationMs)}
                      </span>
                      <span className="text-muted-foreground">{formatDateTime(locale, turn.startedAt)}</span>
                      <span className="min-w-0 flex-1 truncate text-muted-foreground">
                        {turn.userPreview || '—'}
                      </span>
                      <span className="text-muted-foreground">
                        {t(
                          g.messages.length === 1
                            ? 'operate.message.countOne'
                            : 'operate.message.countMany',
                          { count: formatNumber(locale, g.messages.length) },
                        )}
                      </span>
                    </button>
                    {open && (
                      <div className="border-t border-border px-4 py-3">
                        {messagesLoading && !historyMessages ? (
                          <p className="text-sm text-muted-foreground">{t('operate.conversation.loadingMessages')}</p>
                        ) : (
                          <MessageItems
                            messages={g.messages}
                            emptyLabel={t('operate.conversation.noTurnMessages')}
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
