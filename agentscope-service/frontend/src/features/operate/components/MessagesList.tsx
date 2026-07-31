import { useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { JsonViewer } from '@/components/JsonViewer';
import {
  extractHistoryMessages,
  type HistoryMessage,
} from '../lib/groupMessagesByTurns';

export function messagesSummary(data: unknown) {
  const messages = extractHistoryMessages(data);
  return messages ? { count: messages.length } : null;
}

function previewText(content?: string, max = 96) {
  const text = String(content || '').replace(/\s+/g, ' ').trim();
  if (!text) return '—';
  if (text.length <= max) return text;
  return `${text.slice(0, max)}…`;
}

function MessageRow({ message: m }: { message: HistoryMessage }) {
  const [open, setOpen] = useState(false);
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
        <Badge tone={m.role === 'user' ? 'info' : m.role === 'assistant' ? 'success' : 'default'}>
          {m.role || 'message'}
        </Badge>
        {m.toolName && <Badge tone="warning">{m.toolName}</Badge>}
        {m.occurredAt && (
          <span className="text-sm text-muted-foreground">
            {new Date(m.occurredAt).toLocaleString()}
          </span>
        )}
        {!open && (
          <span className="min-w-0 flex-1 truncate text-muted-foreground">
            {previewText(m.content)}
          </span>
        )}
      </button>
      {open && (
        <div className="border-t border-border px-4 py-3 whitespace-pre-wrap leading-relaxed text-muted-foreground">
          {String(m.content || '')}
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
  // Only treat as loading when explicitly fetching. Disabled queries leave
  // data undefined with isLoading=false — do not spin forever on null data.
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
