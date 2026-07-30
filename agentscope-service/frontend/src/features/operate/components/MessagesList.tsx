import { Badge } from '@/components/ui/badge';
import { JsonViewer } from '@/components/JsonViewer';

type MessageLike = {
  seq?: number;
  role?: string;
  content?: string;
  toolName?: string;
  occurredAt?: string;
};

function extractMessages(data: unknown): MessageLike[] | null {
  if (!data || typeof data !== 'object') return null;
  const obj = data as Record<string, unknown>;
  const list = Array.isArray(obj.messages) ? obj.messages : Array.isArray(data) ? data : null;
  if (!list) return null;
  return list.map((m) => (m && typeof m === 'object' ? (m as MessageLike) : { content: String(m) }));
}

export function MessagesList({
  data,
  unavailableReason,
  loading,
}: {
  data?: unknown;
  unavailableReason?: string;
  loading?: boolean;
}) {
  if (unavailableReason) {
    return <p className="text-sm text-muted-foreground">{unavailableReason}</p>;
  }
  if (loading || data == null) {
    return <p className="text-sm text-muted-foreground">Loading…</p>;
  }

  const messages = extractMessages(data);
  if (!messages) {
    return <JsonViewer value={data} className="max-h-96" />;
  }
  if (messages.length === 0) {
    return <p className="text-sm text-muted-foreground">No messages.</p>;
  }

  return (
    <div className="max-h-96 space-y-2.5 overflow-auto">
      {messages.map((m, i) => (
        <div key={m.seq ?? i} className="rounded-lg border border-border px-4 py-3 text-sm">
          <div className="mb-1.5 flex flex-wrap items-center gap-2">
            {m.seq != null && <span className="font-mono tabular-nums text-muted-foreground">#{m.seq}</span>}
            <Badge tone={m.role === 'user' ? 'info' : m.role === 'assistant' ? 'success' : 'default'}>
              {m.role || 'message'}
            </Badge>
            {m.toolName && <Badge tone="warning">{m.toolName}</Badge>}
            {m.occurredAt && (
              <span className="text-sm text-muted-foreground">{new Date(m.occurredAt).toLocaleString()}</span>
            )}
          </div>
          <div className="whitespace-pre-wrap leading-relaxed text-muted-foreground">
            {String(m.content || '').slice(0, 800)}
            {String(m.content || '').length > 800 ? '…' : ''}
          </div>
        </div>
      ))}
    </div>
  );
}
