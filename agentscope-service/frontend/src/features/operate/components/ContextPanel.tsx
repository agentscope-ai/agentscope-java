import { useState } from 'react';
import { Badge } from '@/components/ui/badge';
import { JsonViewer } from '@/components/JsonViewer';

function asArray(v: unknown): unknown[] {
  if (Array.isArray(v)) return v;
  if (typeof v === 'string') {
    try {
      const parsed = JSON.parse(v);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }
  return [];
}

function toolNames(tools: unknown): string[] {
  return asArray(tools)
    .map((t) => {
      if (typeof t === 'string') return t;
      if (t && typeof t === 'object' && 'name' in t) return String((t as { name: unknown }).name);
      return '';
    })
    .filter(Boolean);
}

export function ContextPanel({
  data,
  unavailableReason,
  error,
  loading,
}: {
  data?: Record<string, unknown> | null;
  unavailableReason?: string;
  error?: boolean;
  loading?: boolean;
}) {
  const [showRaw, setShowRaw] = useState(false);

  if (unavailableReason) {
    return <p className="text-sm text-muted-foreground">{unavailableReason}</p>;
  }
  if (error) {
    return <p className="text-sm text-red-600">Failed to load context.</p>;
  }
  if (loading || !data) {
    return <p className="text-sm text-muted-foreground">Loading…</p>;
  }

  const systemPrompt = typeof data.systemPrompt === 'string' ? data.systemPrompt : '';
  const messages = asArray(data.messages);
  const tools = toolNames(data.tools);
  const isCompacted = Boolean(data.isCompacted);
  const compactionSummary =
    typeof data.compactionSummary === 'string' ? data.compactionSummary : '';
  const totalTokens = data.totalTokens;
  const maxTokens = data.maxTokens;
  const model = typeof data.model === 'string' ? data.model : '';
  const frameworkState =
    data.frameworkState && typeof data.frameworkState === 'object'
      ? (data.frameworkState as Record<string, unknown>)
      : null;
  const planActive = Boolean(frameworkState?.planActive);
  const planFile =
    typeof frameworkState?.currentPlanFile === 'string' ? frameworkState.currentPlanFile : '';
  const planExcerpt =
    typeof frameworkState?.planExcerpt === 'string' ? frameworkState.planExcerpt : '';
  const promptSource =
    typeof frameworkState?.systemPromptSource === 'string'
      ? frameworkState.systemPromptSource
      : '';
  const hasStructured = Boolean(
    systemPrompt || messages.length || tools.length || compactionSummary || model || planActive,
  );

  return (
    <div className="space-y-5">
      {hasStructured ? (
        <>
          <div className="flex flex-wrap items-center gap-2">
            {isCompacted && <Badge tone="warning">compacted</Badge>}
            {planActive && <Badge tone="info">plan mode</Badge>}
            {model && <Badge tone="info">{model}</Badge>}
            <span className="text-sm text-muted-foreground">
              {messages.length} messages
              {tools.length ? ` · ${tools.length} tools` : ''}
              {totalTokens != null
                ? ` · ${Number(totalTokens).toLocaleString()}${maxTokens != null ? ` / ${Number(maxTokens).toLocaleString()}` : ''} tokens`
                : ''}
            </span>
          </div>

          {systemPrompt && (
            <div>
              <div className="mb-1.5 text-[13px] font-medium uppercase tracking-wide text-muted-foreground">
                System prompt
                {promptSource === 'effective'
                  ? ' (last model call)'
                  : promptSource === 'base'
                    ? ' (builder base — no turn sampled yet)'
                    : ''}
              </div>
              <pre className="max-h-36 overflow-auto whitespace-pre-wrap rounded-lg border border-border bg-muted/40 p-4 text-sm leading-relaxed">
                {systemPrompt}
              </pre>
            </div>
          )}

          {(planActive || planFile || planExcerpt) && (
            <div>
              <div className="mb-1.5 text-[13px] font-medium uppercase tracking-wide text-muted-foreground">
                Plan
              </div>
              <div className="rounded-lg border border-border bg-muted/40 p-4 text-sm leading-relaxed space-y-2">
                <div>
                  {planActive ? 'active' : 'inactive'}
                  {planFile ? ` · ${planFile}` : ''}
                </div>
                {planExcerpt && (
                  <pre className="max-h-28 overflow-auto whitespace-pre-wrap text-sm">{planExcerpt}</pre>
                )}
              </div>
            </div>
          )}

          {compactionSummary && (
            <div>
              <div className="mb-1.5 text-[13px] font-medium uppercase tracking-wide text-muted-foreground">
                Compaction
              </div>
              <p className="rounded-lg border border-border bg-muted/40 p-4 text-sm leading-relaxed">
                {compactionSummary}
              </p>
            </div>
          )}

          {tools.length > 0 && (
            <div>
              <div className="mb-1.5 text-[13px] font-medium uppercase tracking-wide text-muted-foreground">
                Tools (session-effective)
              </div>
              <div className="flex flex-wrap gap-1.5">
                {tools.map((t) => (
                  <Badge key={t} tone="info">
                    {t}
                  </Badge>
                ))}
              </div>
            </div>
          )}

          {messages.length > 0 && (
            <div>
              <div className="mb-1.5 text-[13px] font-medium uppercase tracking-wide text-muted-foreground">
                Effective messages ({messages.length})
              </div>
              <div className="max-h-52 space-y-2.5 overflow-auto">
                {messages.slice(0, 40).map((m, i) => {
                  const msg = m as { role?: string; content?: string; isCompaction?: boolean };
                  return (
                    <div key={i} className="rounded-lg border border-border px-4 py-3 text-sm">
                      <div className="mb-1.5 flex items-center gap-2">
                        <Badge tone={msg.role === 'user' ? 'info' : 'default'}>{msg.role || 'msg'}</Badge>
                        {msg.isCompaction && <Badge tone="warning">compaction</Badge>}
                      </div>
                      <div className="whitespace-pre-wrap leading-relaxed text-muted-foreground">
                        {String(msg.content || '').slice(0, 500)}
                        {String(msg.content || '').length > 500 ? '…' : ''}
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </>
      ) : (
        <JsonViewer value={data} className="max-h-96" />
      )}

      {hasStructured && (
        <div>
          <button
            type="button"
            className="text-sm text-muted-foreground underline-offset-2 hover:underline"
            onClick={() => setShowRaw((v) => !v)}
          >
            {showRaw ? 'Hide raw' : 'Show raw'}
          </button>
          {showRaw && <JsonViewer value={data} className="mt-2 max-h-64" />}
        </div>
      )}
    </div>
  );
}
