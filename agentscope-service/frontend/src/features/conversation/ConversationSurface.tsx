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

import { Activity, Brain, Clock, Wrench, Bot, ChevronDown, ChevronRight, CircleAlert, Send, User } from 'lucide-react';
import { type ReactNode, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { presentEvent, durationLabel, record } from './eventPresentation';
import ReactMarkdown from 'react-markdown';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import type {
  ConversationContentBlock,
  ConversationEvent,
  ConversationMessage,
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
  const hasBody = true;
  const failed = ['error', 'failed', 'denied', 'interrupted'].includes(block.toolState || '');
  return (
    <div className={cn('overflow-hidden rounded-xl border bg-muted/20', failed ? 'border-red-300' : 'border-border')}>
      <button
        type="button"
        className="flex w-full items-center gap-2 px-3.5 py-2.5 text-left text-sm hover:bg-muted/50"
        aria-expanded={open}
        onClick={() => hasBody && setOpen((value) => !value)}
      >
        {hasBody ? open ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" /> : null}
        <Wrench className={cn('h-4 w-4 shrink-0', failed ? 'text-red-600' : 'text-slate-500')} />
        <span className="font-medium">{block.toolName || 'Tool call'}</span>
        <Badge tone={failed ? 'danger' : block.result !== undefined ? 'success' : 'default'}>{failed ? block.toolState : block.result !== undefined ? 'Completed' : block.toolState === 'unavailable' ? 'Result not recorded' : 'Awaiting result'}</Badge>
        {block.durationMs != null && <span className="ml-auto text-xs text-muted-foreground">{durationLabel(block.durationMs)}</span>}
      </button>
      {open && (
        <div className="grid gap-3 border-t border-border p-3">
          {(block.text || block.data != null) && (
            <section className="min-w-0">
              <div className="mb-1.5 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Input</div>
              <pre className="max-h-64 overflow-auto whitespace-pre-wrap rounded-lg bg-slate-950 p-3 font-mono text-xs text-slate-100">{pretty(block.text || block.data)}</pre>
            </section>
          )}
          <section className="min-w-0">
            <div className="mb-1.5 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Output</div>
            {block.result !== undefined ? <pre className="max-h-64 overflow-auto whitespace-pre-wrap rounded-lg bg-slate-950 p-3 font-mono text-xs text-slate-100">{pretty(block.result) || '(Empty result)'}</pre> : <p className="text-sm text-muted-foreground">{block.toolState === 'unavailable' ? 'No result is present in the loaded history.' : 'Waiting for result…'}</p>}
          </section>
          <div className="break-all text-xs text-muted-foreground">{block.eventSeq != null && `Events #${block.eventSeq}${block.resultSeq != null ? ` → #${block.resultSeq}` : ''}`}{block.callId && <div>Call ID: <code>{block.callId}</code></div>}</div>
        </div>
      )}
    </div>
  );
}

function ProcessBlock({ block }: { block: ConversationContentBlock }) {
  const thinking = block.kind === 'thinking';
  if (!thinking) return <div className="flex items-center gap-2 py-1 text-xs text-muted-foreground"><Clock className="h-3.5 w-3.5" /><span>{block.toolState === 'running' ? 'Requesting model…' : block.toolState === 'unavailable' ? 'Model request · end not recorded' : 'Model request finished'}</span>{block.durationMs != null && <span>· {durationLabel(block.durationMs)}</span>}</div>;
  return <details className="rounded-lg border border-violet-200 bg-violet-50/40 text-sm"><summary className="flex cursor-pointer list-none items-center gap-2 px-3 py-2.5 text-violet-700"><Brain className="h-4 w-4" /><span className="font-medium">Thinking</span><ChevronDown className="ml-auto h-3.5 w-3.5" /></summary><div className="md-text max-h-80 overflow-auto border-t border-violet-100 px-4 py-3 leading-6 text-slate-600"><ReactMarkdown>{block.text || 'No thinking text was recorded.'}</ReactMarkdown></div></details>;
}

function MessageRow({ message }: { message: ConversationMessage }) {
  const process = message.blocks.every(block => ['tool', 'thinking', 'model'].includes(block.kind));
  if (process) return <article className="ml-10 min-w-0 space-y-2" data-message-id={message.id}>{message.blocks.map(block => block.kind === 'tool' ? <ToolBlock key={block.id} block={block} /> : <ProcessBlock key={block.id} block={block} />)}{message.truncated && <p className="text-xs text-amber-700">Recorded content was truncated{message.originalSize ? ` · original size ${message.originalSize}` : ''}.</p>}</article>;
  const isUser = message.role === 'user';
  const isError = message.role === 'error';
  const Icon = isUser ? User : isError ? CircleAlert : Bot;
  return (
    <article className={cn('group flex gap-3', isUser && 'flex-row-reverse')} data-message-id={message.id}>
      <div className={cn('mt-1 flex h-7 w-7 shrink-0 items-center justify-center rounded-full', isUser ? 'bg-indigo-100 text-indigo-700' : isError ? 'bg-red-100 text-red-700' : 'bg-slate-100 text-slate-700')}>
        <Icon className="h-3.5 w-3.5" />
      </div>
      <div className={cn('min-w-0 w-full max-w-[90%]', isUser && 'text-right')}>
        <div className={cn('mb-1.5 flex items-center gap-2 text-xs text-muted-foreground', isUser && 'justify-end')}>
          <span className="font-medium capitalize">{message.role}</span>
          {message.turnIndex != null && <span>turn {message.turnIndex}</span>}
          {message.occurredAt && <span>{new Date(message.occurredAt).toLocaleTimeString()}</span>}
          {message.state === 'streaming' && <Badge tone="info">streaming</Badge>}
          {message.truncated && <Badge tone="danger">truncated{message.originalSize ? ` · ${message.originalSize} B` : ''}</Badge>}
        </div>
        <div className={cn('space-y-3 text-left', isUser && 'rounded-2xl rounded-tr-md bg-indigo-600 px-4 py-3 text-white', isError && 'rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-red-800')}>
          {message.blocks.map((block) => {
            if (block.kind === 'thinking' || block.kind === 'model') return <ProcessBlock key={block.id} block={block} />;
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

function EventRow({ event }: { event: ConversationEvent }) {
  const [open, setOpen] = useState(false);
  const info = presentEvent(event);
  const raw = record(event.payload);
  const content = event.summary !== raw.toolName ? event.summary : undefined;
  return (
    <article className="relative pl-7" data-event-id={event.id}>
      <span className={cn('absolute left-[7px] top-5 h-2.5 w-2.5 rounded-full border-2 border-background ring-1 ring-border', event.category === 'error' ? 'bg-red-500' : 'bg-slate-400')} />
      <div className="overflow-hidden rounded-xl border border-border bg-background">
        <button type="button" aria-expanded={open} className="flex w-full items-start gap-3 px-4 py-3 text-left text-sm hover:bg-muted/40" onClick={() => setOpen(value => !value)}>
          {open ? <ChevronDown className="mt-1 h-3.5 w-3.5 shrink-0" /> : <ChevronRight className="mt-1 h-3.5 w-3.5 shrink-0" />}
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <span className={cn('font-medium', event.category === 'error' && 'text-red-600')}>{info.title}</span>
              {event.durationMs != null && <span className="text-xs text-muted-foreground">{durationLabel(event.durationMs)}</span>}
              <span className="text-xs text-muted-foreground">{info.source}</span>
              {event.occurredAt && <time dateTime={event.occurredAt} title={new Date(event.occurredAt).toLocaleString()} className="ml-auto text-xs text-muted-foreground">{new Date(event.occurredAt).toLocaleTimeString()}</time>}
            </div>
            {!open && content && <p className="mt-1 truncate text-muted-foreground">{content}</p>}
          </div>
        </button>
        {open && <div className="space-y-3 border-t border-border px-4 py-3 text-sm">
          {(event.tokensIn != null || event.tokensOut != null) && <p className="text-xs text-muted-foreground">Tokens · input {event.tokensIn ?? '—'} · output {event.tokensOut ?? '—'}</p>}
          {content && <pre className="max-h-60 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-muted/50 p-3 text-sm">{pretty(content)}</pre>}
          {raw.toolInput != null && <details><summary className="cursor-pointer font-medium">Tool input</summary><pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap rounded-lg bg-muted p-3 text-xs">{pretty(raw.toolInput)}</pre></details>}
          <details>
            <summary className="cursor-pointer text-xs text-muted-foreground">Diagnostic details</summary>
            <div className="mt-3 space-y-3">
              <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground"><code>{event.type}</code>{event.seq != null && <span>Event #{event.seq}</span>}</div>
              <dl className="space-y-2">{[...info.relations, ...info.diagnostics].map(item => <div key={item.label} className="grid gap-1 sm:grid-cols-[9rem_1fr]"><dt className="text-xs text-muted-foreground" title={item.description}>{item.label}</dt><dd className="min-w-0 break-all font-mono text-xs">{item.href ? <Link className="text-indigo-600 hover:underline" to={item.href}>{item.value} ↗</Link> : item.value}</dd></div>)}</dl>
              <details><summary className="cursor-pointer text-xs text-muted-foreground">Original JSON</summary><pre className="mt-2 max-h-96 overflow-auto whitespace-pre-wrap break-all rounded-lg bg-slate-950 p-3 font-mono text-xs text-slate-100">{pretty(event.payload)}</pre></details>
            </div>
          </details>
        </div>}
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
  const [filter, setFilter] = useState('all');
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const followRef = useRef(true);
  const canSubmit = !!composer && !composer.disabled && !composer.busy && !!composer.value.trim();
  // Adapters supply chronological input. Preserve that order for stream-only
  // frames without a durable sequence; sorting them as seq=0 would move live
  // deltas above the restored history.
  const eventList = events.filter(event => filter === 'all' || event.category === filter);

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

      {view === 'events' && <div className="flex flex-wrap items-center gap-3 border-b border-border px-4 py-2 text-xs text-muted-foreground"><label className="flex items-center gap-2">Show <select aria-label="Filter events" className="rounded-md border border-border bg-background px-2 py-1.5 text-foreground" value={filter} onChange={e => setFilter(e.target.value)}>{['all', 'message', 'model', 'tool', 'turn', 'lifecycle', 'error', 'other'].map(value => <option key={value} value={value}>{value === 'all' ? 'All events' : value}</option>)}</select></label><span>{eventList.length} / {events.length}</span></div>}
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
          <div className="mx-auto max-w-4xl space-y-4">
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
