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

export function contextSummary(data?: Record<string, unknown> | null) {
  if (!data) return null;
  const messages = asArray(data.messages);
  const tools = toolNames(data.tools);
  return {
    messageCount: messages.length,
    toolCount: tools.length,
    model: typeof data.model === 'string' ? data.model : '',
    isCompacted: Boolean(data.isCompacted),
    totalTokens: data.totalTokens != null ? Number(data.totalTokens) : null,
    maxTokens: data.maxTokens != null ? Number(data.maxTokens) : null,
    planActive: Boolean(
      data.frameworkState &&
        typeof data.frameworkState === 'object' &&
        (data.frameworkState as { planActive?: unknown }).planActive,
    ),
  };
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
              {messages.length} effective messages
              {tools.length ? ` · ${tools.length} tools` : ''}
              {totalTokens != null
                ? ` · window ${Number(totalTokens).toLocaleString()}${maxTokens != null ? ` / ${Number(maxTokens).toLocaleString()}` : ''}`
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
              <pre className="max-h-48 overflow-auto whitespace-pre-wrap rounded-lg border border-border bg-muted/40 p-4 text-sm leading-relaxed">
                {systemPrompt}
              </pre>
            </div>
          )}

          {(planActive || planFile || planExcerpt) && (
            <div>
              <div className="mb-1.5 text-[13px] font-medium uppercase tracking-wide text-muted-foreground">
                Plan
              </div>
              <div className="space-y-2 rounded-lg border border-border bg-muted/40 p-4 text-sm leading-relaxed">
                <div>
                  {planActive ? 'active' : 'inactive'}
                  {planFile ? ` · ${planFile}` : ''}
                </div>
                {planExcerpt && (
                  <pre className="max-h-36 overflow-auto whitespace-pre-wrap text-sm">{planExcerpt}</pre>
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
                Effective messages ({messages.length}) — AgentState window for next model call
              </div>
              <div className="max-h-[50vh] space-y-2.5 overflow-auto">
                {messages.map((m, i) => {
                  const msg = m as { role?: string; content?: string; isCompaction?: boolean };
                  return <ContextMessageRow key={i} message={msg} />;
                })}
              </div>
            </div>
          )}
        </>
      ) : (
        <JsonViewer value={data} className="max-h-[50vh]" />
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

function previewText(content?: string, max = 96) {
  const text = String(content || '').replace(/\s+/g, ' ').trim();
  if (!text) return '—';
  if (text.length <= max) return text;
  return `${text.slice(0, max)}…`;
}

function ContextMessageRow({
  message,
}: {
  message: { role?: string; content?: string; isCompaction?: boolean };
}) {
  const [open, setOpen] = useState(false);
  return (
    <div className="rounded-lg border border-border text-sm">
      <button
        type="button"
        className="flex w-full flex-wrap items-center gap-x-2 gap-y-1.5 px-4 py-3 text-left hover:bg-muted/40"
        onClick={() => setOpen((v) => !v)}
      >
        <span className="font-mono text-muted-foreground">{open ? '▾' : '▸'}</span>
        <Badge tone={message.role === 'user' ? 'info' : 'default'}>{message.role || 'msg'}</Badge>
        {message.isCompaction && <Badge tone="warning">compaction</Badge>}
        {!open && (
          <span className="min-w-0 flex-1 truncate text-muted-foreground">
            {previewText(message.content)}
          </span>
        )}
      </button>
      {open && (
        <div className="border-t border-border px-4 py-3 whitespace-pre-wrap leading-relaxed text-muted-foreground">
          {String(message.content || '')}
        </div>
      )}
    </div>
  );
}
