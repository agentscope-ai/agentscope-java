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

import React, { useMemo, useState } from 'react';
import ToolCallBlock from './ToolCallBlock';

export interface MessageBlockTool {
  id: string;
  name: string;
  input?: string;
  result?: string;
}

export interface MessageBlockProps {
  role: 'user' | 'assistant' | 'system';
  text: string;
  tools: MessageBlockTool[];
  pending?: boolean;
  /** Soft auto-expand while actively streaming once the user opens it — not forced. */
  defaultOpen?: boolean;
}

const PREVIEW_CHARS = 120;
const EXPANDED_BODY_MAX_PX = 280;

function normalizePreview(text: string): string {
  return text.replace(/\s+/g, ' ').trim();
}

/** Single-line preview; while streaming show the trailing window (typewriter tip). */
export function messagePreviewLine(text: string, pending?: boolean): string {
  const t = normalizePreview(text);
  if (!t) {
    if (pending) return 'Generating…';
    return '';
  }
  if (t.length <= PREVIEW_CHARS) return t;
  // Pending: follow the typing tip; settled: keep the start of the message.
  return pending ? `…${t.slice(-PREVIEW_CHARS)}` : `${t.slice(0, PREVIEW_CHARS)}…`;
}

const roleStyles: Record<MessageBlockProps['role'], React.CSSProperties> = {
  user: {
    alignSelf: 'flex-end',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff',
    boxShadow: '0 2px 6px rgba(99,102,241,0.25)',
    border: 'none',
  },
  assistant: {
    alignSelf: 'flex-start',
    background: '#ffffff',
    color: '#0f172a',
    border: '1px solid #e2e8f0',
    boxShadow: '0 1px 2px rgba(15,23,42,0.04)',
  },
  system: {
    alignSelf: 'center',
    background: 'transparent',
    color: '#94a3b8',
    border: '1px dashed #e2e8f0',
    fontStyle: 'italic',
  },
};

const s: Record<string, React.CSSProperties> = {
  root: {
    maxWidth: '78%',
    borderRadius: 14,
    overflow: 'hidden',
    fontSize: '0.95rem',
    lineHeight: 1.6,
    flexShrink: 0,
    minHeight: 'fit-content',
  },
  header: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    width: '100%',
    padding: '10px 14px',
    border: 'none',
    background: 'transparent',
    cursor: 'pointer',
    textAlign: 'left',
    color: 'inherit',
    font: 'inherit',
    flexShrink: 0,
    minHeight: 40,
  },
  chevron: {
    flexShrink: 0,
    width: 14,
    fontSize: '0.75rem',
    opacity: 0.75,
  },
  role: {
    flexShrink: 0,
    fontSize: '0.72rem',
    fontWeight: 700,
    letterSpacing: '0.04em',
    textTransform: 'uppercase',
    opacity: 0.7,
  },
  preview: {
    flex: 1,
    minWidth: 0,
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.85rem',
    opacity: 0.92,
  },
  meta: {
    flexShrink: 0,
    fontSize: '0.72rem',
    opacity: 0.65,
  },
  body: {
    padding: '0 14px 12px',
    display: 'flex',
    flexDirection: 'column',
    gap: 10,
    maxHeight: EXPANDED_BODY_MAX_PX,
    overflowY: 'auto',
    overscrollBehavior: 'contain',
  },
  text: {
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
  },
  pending: {
    color: '#94a3b8',
  },
};

export default function MessageBlock({
  role,
  text,
  tools,
  pending,
  defaultOpen = false,
}: MessageBlockProps) {
  const [open, setOpen] = useState(defaultOpen);
  const preview = useMemo(() => messagePreviewLine(text, pending), [text, pending]);
  const toolHint = tools.length > 0
    ? `${tools.length} tool${tools.length === 1 ? '' : 's'}`
    : '';

  const empty = !text && tools.length === 0;

  return (
    <div style={{ ...s.root, ...roleStyles[role] }}>
      <button
        type="button"
        style={s.header}
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
      >
        <span style={s.chevron}>{open ? '▾' : '▸'}</span>
        <span style={s.role}>{role}</span>
        {!open && (
          <span style={s.preview} title={normalizePreview(text) || undefined}>
            {preview || (toolHint ? `${toolHint}…` : pending ? 'Generating…' : '(empty)')}
          </span>
        )}
        {!open && toolHint && preview && (
          <span style={s.meta}>{toolHint}</span>
        )}
        {open && <span style={{ flex: 1 }} />}
        {pending && <span style={s.meta}>streaming</span>}
      </button>

      {open && (
        <div style={s.body}>
          {tools.length > 0 && (
            <div>
              {tools.map((t) => (
                <ToolCallBlock
                  key={t.id}
                  toolName={t.name}
                  toolCallId={t.id}
                  result={t.result}
                />
              ))}
            </div>
          )}
          {text ? (
            <div style={s.text}>{text}</div>
          ) : pending ? (
            <div style={{ ...s.text, ...s.pending }}>…</div>
          ) : empty ? (
            <div style={{ ...s.text, ...s.pending }}>—</div>
          ) : null}
        </div>
      )}
    </div>
  );
}
