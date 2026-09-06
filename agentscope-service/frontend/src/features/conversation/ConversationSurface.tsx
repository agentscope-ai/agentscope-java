/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { Activity, Bot, ChevronDown, ChevronRight, CircleAlert, Send, User } from 'lucide-react';
import { type ReactNode, useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import type {
  ConversationContentBlock,
  ConversationEvent,
  ConversationMessage,
  ConversationRole,
} from './model';

export interface ConversationComposer {
  value: string;
  onChange: (value: string) => void;
  onSubmit: (value: string) => void | Promise<void>;
  disabled?: boolean;
  busy?: boolean;
  placeholder?: string;
}

export interface ConversationSurfaceProps {
  messages: ConversationMessage[];
  events: ConversationEvent[];
  source?: string;
  loading?: boolean;
  error?: string | null;
  className?: string;
  emptyMessage?: string;
  composer?: ConversationComposer;
  accessory?: ReactNode;
  headerActions?: ReactNode;
  onLoadEarlierMessages?: () => void;
  loadingEarlierMessages?: boolean;
  hasEarlierMessages?: boolean;
  onLoadEarlierEvents?: () => void;
  loadingEarlierEvents?: boolean;
  hasEarlierEvents?: boolean;
  defaultView?: 'conversation' | 'events';
}

function roleTone(role: ConversationRole): 'info' | 'success' | 'warning' | 'danger' | 'default' {
  if (role === 'user') return 'info';
  if (role === 'assistant') return 'success';
  if (role === 'tool') return 'warning';
  if (role === 'error') return 'danger';
  return 'default';
}

function pretty(value: unknown): string {
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function ToolBlock({ block }: { block: ConversationContentBlock }) {
  const [open, setOpen] = useState(false);
  const hasBody = !!block.text || !!block.result || block.data != null;
  const failed = ['error', 'denied', 'interrupted'].includes(block.toolState || '');
  return (
    <div className={cn('overflow-hidden rounded-xl border bg-muted/20', failed ? 'border-red-300' : 'border-border')}>
      <button
        type="button"
        className="flex w-full items-center gap-2 px-3.5 py-2.5 text-left text-sm hover:bg-muted/50"
        onClick={() => hasBody && setOpen((value) => !value)}
      >
        {hasBody ? open ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" /> : null}
        <span className={cn('h-2 w-2 rounded-full', failed ? 'bg-red-500' : 'bg-amber-500')} />
        <span className="font-medium">{block.toolName || 'Tool call'}</span>
        {block.toolState && <Badge tone={failed ? 'danger' : block.toolState === 'success' ? 'success' : 'warning'}>{block.toolState}</Badge>}
        {block.callId && <code className="ml-auto max-w-48 truncate text-[11px] text-muted-foreground">{block.callId}</code>}
      </button>
      {open && (
        <div className="grid gap-3 border-t border-border p-3 md:grid-cols-2">
          {(block.text || block.data != null) && (
            <section className="min-w-0">
              <div className="mb-1.5 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Input</div>
              <pre className="max-h-64 overflow-auto whitespace-pre-wrap rounded-lg bg-slate-950 p-3 font-mono text-xs text-slate-100">{pretty(block.text || block.data)}</pre>
            </section>
          )}
          <section className="min-w-0">
            <div className="mb-1.5 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Output</div>
            {block.result ? <pre className="max-h-64 overflow-auto whitespace-pre-wrap rounded-lg bg-slate-950 p-3 font-mono text-xs text-slate-100">{block.result}</pre> : <p className="text-sm text-muted-foreground">Waiting for result…</p>}
          </section>
        </div>
      )}
    </div>
  );
}

function MessageRow({ message }: { message: ConversationMessage }) {
  const isUser = message.role === 'user';
  const isError = message.role === 'error';
  const Icon = isUser ? User : isError ? CircleAlert : Bot;
  return (
    <article className={cn('group flex gap-3', isUser && 'flex-row-reverse')} data-message-id={message.id}>
      <div className={cn('mt-1 flex h-7 w-7 shrink-0 items-center justify-center rounded-full', isUser ? 'bg-indigo-100 text-indigo-700' : isError ? 'bg-red-100 text-red-700' : 'bg-slate-100 text-slate-700')}>
        <Icon className="h-3.5 w-3.5" />
      </div>
      <div className={cn('min-w-0 max-w-[82%]', isUser && 'text-right')}>
        <div className={cn('mb-1.5 flex items-center gap-2 text-xs text-muted-foreground', isUser && 'justify-end')}>
          <span className="font-medium capitalize">{message.role}</span>
          {message.turnIndex != null && <span>turn {message.turnIndex}</span>}
          {message.occurredAt && <span>{new Date(message.occurredAt).toLocaleTimeString()}</span>}
          {message.state === 'streaming' && <Badge tone="info">streaming</Badge>}
          {message.truncated && <Badge tone="danger">truncated{message.originalSize ? ` · ${message.originalSize} B` : ''}</Badge>}
        </div>
        <div className={cn('space-y-3 text-left', isUser && 'rounded-2xl rounded-tr-md bg-indigo-600 px-4 py-3 text-white', isError && 'rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-red-800')}>
          {message.blocks.map((block) => {
            if (block.kind === 'tool') return <ToolBlock key={block.id} block={block} />;
            if (block.kind === 'data') return <pre key={block.id} className="overflow-auto whitespace-pre-wrap rounded-lg bg-slate-950 p-3 font-mono text-xs text-slate-100">{pretty(block.data)}</pre>;
            if (message.role === 'assistant') return <div key={block.id} className="md-text leading-7"><ReactMarkdown>{block.text || ''}</ReactMarkdown></div>;
            return <div key={block.id} className="whitespace-pre-wrap leading-6">{block.text || '—'}</div>;
          })}
        </div>
      </div>
    </article>
  );
}

function eventTone(event: ConversationEvent): 'info' | 'success' | 'warning' | 'danger' | 'default' {
  if (event.category === 'error') return 'danger';
  if (event.category === 'tool') return 'warning';
  if (event.category === 'message') return 'info';
  if (event.category === 'turn') return 'success';
  return 'default';
}

function EventRow({ event }: { event: ConversationEvent }) {
  const [open, setOpen] = useState(false);
  const hasPayload = event.payload != null;
  return (
    <article className="relative pl-7">
      <span className="absolute left-[7px] top-4 h-2.5 w-2.5 rounded-full border-2 border-background bg-slate-400 ring-1 ring-border" />
      <div className="rounded-xl border border-border bg-background">
        <button type="button" className="flex w-full flex-wrap items-center gap-2 px-3.5 py-3 text-left text-sm hover:bg-muted/40" onClick={() => hasPayload && setOpen((value) => !value)}>
          {hasPayload ? open ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" /> : null}
          {event.seq != null && <code className="text-xs text-muted-foreground">#{event.seq}</code>}
          <Badge tone={eventTone(event)}>{event.type}</Badge>
          {event.role && <Badge tone={roleTone(event.role)}>{event.role}</Badge>}
          {event.durationMs != null && <span className="text-xs text-muted-foreground">{event.durationMs} ms</span>}
          {(event.tokensIn || event.tokensOut) && <span className="text-xs text-muted-foreground">tokens {event.tokensIn || 0}/{event.tokensOut || 0}</span>}
          <span className="min-w-0 flex-1 truncate text-muted-foreground">{event.summary || '—'}</span>
          {event.occurredAt && <time className="text-xs text-muted-foreground">{new Date(event.occurredAt).toLocaleString()}</time>}
        </button>
        {open && <pre className="max-h-96 overflow-auto whitespace-pre-wrap border-t border-border bg-slate-950 p-4 font-mono text-xs text-slate-100">{pretty(event.payload)}</pre>}
      </div>
    </article>
  );
}

export function ConversationSurface({
  messages,
  events,
  source,
  loading,
  error,
  className,
  emptyMessage = 'No messages yet.',
  composer,
  accessory,
  headerActions,
  onLoadEarlierMessages,
  loadingEarlierMessages,
  hasEarlierMessages,
  onLoadEarlierEvents,
  loadingEarlierEvents,
  hasEarlierEvents,
  defaultView = 'conversation',
}: ConversationSurfaceProps) {
  const [view, setView] = useState(defaultView);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const followRef = useRef(true);
  const canSubmit = !!composer && !composer.disabled && !composer.busy && !!composer.value.trim();
  // Adapters supply chronological input. Preserve that order for stream-only
  // frames without a durable sequence; sorting them as seq=0 would move live
  // deltas above the restored history.
  const eventList = events;

  useEffect(() => {
    const node = scrollRef.current;
    if (view === 'conversation' && node && followRef.current) node.scrollTop = node.scrollHeight;
  }, [messages, accessory, view]);

  return (
    <section className={cn('flex min-h-[32rem] flex-col overflow-hidden rounded-2xl border border-border bg-background shadow-sm', className)}>
      <header className="flex flex-wrap items-center gap-3 border-b border-border px-4 py-3">
        <div className="flex rounded-lg bg-muted p-1">
          <button type="button" className={cn('rounded-md px-3 py-1.5 text-sm font-medium', view === 'conversation' ? 'bg-background shadow-sm' : 'text-muted-foreground')} onClick={() => setView('conversation')}>Conversation <span className="ml-1 text-xs opacity-60">{messages.length}</span></button>
          <button type="button" className={cn('rounded-md px-3 py-1.5 text-sm font-medium', view === 'events' ? 'bg-background shadow-sm' : 'text-muted-foreground')} onClick={() => setView('events')}><Activity className="mr-1 inline h-3.5 w-3.5" />Events <span className="ml-1 text-xs opacity-60">{events.length}</span></button>
        </div>
        {source && <Badge>{source}</Badge>}
        <span className="flex-1" />
        {headerActions}
      </header>

      {error && <div className="border-b border-red-200 bg-red-50 px-4 py-2.5 text-sm text-red-700">{error}</div>}

      <div
        ref={scrollRef}
        className="min-h-0 flex-1 overflow-y-auto bg-slate-50/50 px-4 py-5 sm:px-7"
        onScroll={(event) => {
          const node = event.currentTarget;
          followRef.current = node.scrollHeight - node.scrollTop - node.clientHeight < 96;
        }}
      >
        {view === 'conversation' ? (
          <div className="mx-auto max-w-4xl space-y-7">
            {hasEarlierMessages && <div className="text-center"><Button type="button" size="sm" variant="outline" disabled={loadingEarlierMessages} onClick={onLoadEarlierMessages}>{loadingEarlierMessages ? 'Loading…' : 'Load earlier messages'}</Button></div>}
            {loading && messages.length === 0 ? <p className="py-16 text-center text-sm text-muted-foreground">Loading conversation…</p> : messages.length === 0 ? <p className="py-16 text-center text-sm text-muted-foreground">{emptyMessage}</p> : messages.map((message) => <MessageRow key={message.id} message={message} />)}
            {accessory}
          </div>
        ) : (
          <div className="relative mx-auto max-w-5xl space-y-2 before:absolute before:bottom-4 before:left-3 before:top-4 before:w-px before:bg-border">
            {hasEarlierEvents && <div className="relative z-10 pb-2 text-center"><Button type="button" size="sm" variant="outline" disabled={loadingEarlierEvents} onClick={onLoadEarlierEvents}>{loadingEarlierEvents ? 'Loading…' : 'Load earlier events'}</Button></div>}
            {loading && eventList.length === 0 ? <p className="relative py-16 text-center text-sm text-muted-foreground">Loading events…</p> : eventList.length === 0 ? <p className="relative py-16 text-center text-sm text-muted-foreground">No events recorded.</p> : eventList.map((event) => <EventRow key={event.id} event={event} />)}
          </div>
        )}
      </div>

      {composer && (
        <form
          className="flex shrink-0 items-end gap-2 border-t border-border bg-background p-3.5"
          onSubmit={(event) => {
            event.preventDefault();
            if (canSubmit) void composer.onSubmit(composer.value.trim());
          }}
        >
          <textarea
            className="max-h-40 min-h-11 flex-1 resize-none rounded-xl border border-border bg-background px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-ring disabled:bg-muted"
            rows={1}
            value={composer.value}
            onChange={(event) => composer.onChange(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                event.preventDefault();
                if (canSubmit) void composer.onSubmit(composer.value.trim());
              }
            }}
            disabled={composer.disabled || composer.busy}
            placeholder={composer.placeholder || 'Send a message…'}
          />
          <Button type="submit" size="icon" disabled={!canSubmit} aria-label="Send message"><Send className="h-4 w-4" /></Button>
        </form>
      )}
    </section>
  );
}
