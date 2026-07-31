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
  messagesUnavailableReason,
  sessionPending,
  selectedTurnIndex,
  deepLinkTurnIndex,
  onSelectTurn,
}: {
  turns?: SessionTurn[];
  turnsLoading?: boolean;
  messagesData?: unknown;
  messagesLoading?: boolean;
  messagesUnavailableReason?: string;
  /** True while the parent session query has not resolved (capabilities unknown). */
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

  // Scroll to deep-linked turn header without expanding it.
  useEffect(() => {
    if (density !== 'by-turn' || deepLinkTurnIndex == null) return;
    if (didScrollRef.current === deepLinkTurnIndex) return;
    const el = scrollTargetRef.current;
    if (!el) return;
    didScrollRef.current = deepLinkTurnIndex;
    el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
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

  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-3 space-y-0">
        <div>
          <CardTitle>Conversation history</CardTitle>
          <CardDescription>
            Session transcript by turn (ops summary + messages) or as a flat message list.
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
        {density === 'flat' ? (
          sessionPending ? (
            <p className="text-sm text-muted-foreground">Loading…</p>
          ) : (
            <MessagesList
              data={messagesData}
              unavailableReason={messagesUnavailableReason}
              loading={!!messagesLoading}
              maxHeightClass="max-h-[70vh]"
            />
          )
        ) : messagesUnavailableReason ? (
          <p className="text-sm text-muted-foreground">{messagesUnavailableReason}</p>
        ) : showLoading ? (
          <p className="text-sm text-muted-foreground">Loading conversation…</p>
        ) : turnsEmpty && (!historyMessages || historyMessages.length === 0) ? (
          <p className="text-sm text-muted-foreground">
            No turns or messages recorded yet. Turns open when phase becomes active; messages come
            from message-query.
          </p>
        ) : historyMessages == null && messagesData != null ? (
          <JsonViewer value={messagesData} className="max-h-[50vh]" />
        ) : (
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
        )}
      </CardContent>
    </Card>
  );
}
