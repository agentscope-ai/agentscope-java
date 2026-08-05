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

import { useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { JsonViewer } from '@/components/JsonViewer';
import {
  extractHistoryMessages,
  formatToolInput,
  messagePreviewText,
  type HistoryMessage,
} from '../lib/groupMessagesByTurns';

export function messagesSummary(data: unknown) {
  const messages = extractHistoryMessages(data);
  return messages ? { count: messages.length } : null;
}

function roleTone(role?: string): 'info' | 'success' | 'warning' | 'default' {
  switch ((role || '').toLowerCase()) {
    case 'user':
      return 'info';
    case 'assistant':
      return 'success';
    case 'tool':
      return 'warning';
    default:
      return 'default';
  }
}

function isToolResult(m: HistoryMessage): boolean {
  return (m.role || '').toLowerCase() === 'tool' || (m.toolOutput != null && m.toolOutput !== '');
}

function isToolUse(m: HistoryMessage): boolean {
  return !!m.toolName && !isToolResult(m) && (m.toolInput != null || (m.role || '').toLowerCase() === 'assistant');
}

function MessageBody({ message: m }: { message: HistoryMessage }) {
  if (isToolUse(m) || (m.toolName && m.toolInput != null && !isToolResult(m))) {
    const inputText = formatToolInput(m.toolInput);
    return (
      <div className="space-y-2">
        <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Input</div>
        {inputText ? (
          <pre className="max-h-80 overflow-auto whitespace-pre-wrap rounded-md bg-muted/50 px-3 py-2 font-mono text-[12px] leading-relaxed">
            {inputText}
          </pre>
        ) : (
          <p className="text-sm text-muted-foreground">No input recorded.</p>
        )}
        {m.content && m.content !== inputText && (
          <div className="text-sm text-muted-foreground whitespace-pre-wrap">{m.content}</div>
        )}
      </div>
    );
  }

  if (isToolResult(m) || m.toolName) {
    const out = m.toolOutput || m.content || '';
    return (
      <div className="space-y-2">
        <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Output</div>
        {out ? (
          <pre className="max-h-80 overflow-auto whitespace-pre-wrap rounded-md bg-muted/50 px-3 py-2 font-mono text-[12px] leading-relaxed">
            {out}
          </pre>
        ) : (
          <p className="text-sm text-muted-foreground">No output recorded.</p>
        )}
      </div>
    );
  }

  return (
    <div className="whitespace-pre-wrap leading-relaxed text-muted-foreground">
      {String(m.content || '') || '—'}
    </div>
  );
}

function MessageRow({ message: m }: { message: HistoryMessage }) {
  const [open, setOpen] = useState(false);
  const toolish = !!m.toolName || isToolResult(m);
  return (
    <div className="rounded-lg border border-border text-sm">
      <button
        type="button"
        className="flex w-full flex-wrap items-center gap-x-2 gap-y-1.5 px-4 py-3 text-left hover:bg-muted/40"
        onClick={() => setOpen((v) => !v)}
      >
        <span className="font-mono text-muted-foreground">{open ? '▾' : '▸'}</span>
        {m.seq != null && (
          <span className="font-mono tabular-nums text-muted-foreground">#{m.seq}</span>
        )}
        <Badge tone={roleTone(m.role)}>{m.role || 'message'}</Badge>
        {m.toolName && (
          <Badge tone="warning">
            {isToolResult(m) ? 'tool_result' : 'tool_use'} · {m.toolName}
          </Badge>
        )}
        {m.truncated && (
          <Badge tone="danger" title={m.originalSize != null ? `original ${m.originalSize} bytes` : undefined}>
            truncated{m.originalSize != null ? ` · ${m.originalSize}` : ''}
          </Badge>
        )}
        {m.toolCallId && (
          <span className="font-mono text-[11px] text-muted-foreground" title={m.toolCallId}>
            {m.toolCallId.length > 12 ? `${m.toolCallId.slice(0, 12)}…` : m.toolCallId}
          </span>
        )}
        {m.occurredAt && (
          <span className="text-sm text-muted-foreground">
            {new Date(m.occurredAt).toLocaleString()}
          </span>
        )}
        {!open && (
          <span className="min-w-0 flex-1 truncate text-muted-foreground">
            {messagePreviewText(m)}
          </span>
        )}
        {toolish && open && <span className="flex-1" />}
      </button>
      {open && (
        <div className="border-t border-border px-4 py-3">
          <MessageBody message={m} />
        </div>
      )}
    </div>
  );
}

export function MessageItems({
  messages,
  emptyLabel = 'No messages.',
}: {
  messages: HistoryMessage[];
  emptyLabel?: string;
}) {
  if (messages.length === 0) {
    return <p className="text-sm text-muted-foreground">{emptyLabel}</p>;
  }
  return (
    <div className="space-y-2.5">
      {messages.map((m, i) => (
        <MessageRow key={m.seq ?? m.orderIndex ?? i} message={m} />
      ))}
    </div>
  );
}

export function MessagesList({
  data,
  unavailableReason,
  loading,
  maxHeightClass = 'max-h-[60vh]',
}: {
  data?: unknown;
  unavailableReason?: string;
  loading?: boolean;
  maxHeightClass?: string;
}) {
  if (unavailableReason) {
    return <p className="text-sm text-muted-foreground">{unavailableReason}</p>;
  }
  if (loading) {
    return <p className="text-sm text-muted-foreground">Loading…</p>;
  }
  if (data == null) {
    return <p className="text-sm text-muted-foreground">No messages.</p>;
  }

  const messages = extractHistoryMessages(data);
  if (!messages) {
    return <JsonViewer value={data} className="max-h-[50vh]" />;
  }

  return (
    <div className={`${maxHeightClass} overflow-auto`}>
      <MessageItems messages={messages} />
    </div>
  );
}
