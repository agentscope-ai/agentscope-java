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

import React, { useEffect, useMemo, useState } from 'react';
import { Highlight, themes } from 'prism-react-renderer';

interface Props {
  toolName: string;
  toolCallId: string;
  /** Serialized tool-call input (JSON args). */
  input?: string;
  result?: string;
}

/** Unescapes residual \\n / \\t / \\" sequences from double-encoded tool output. */
function unescapeText(s: string): string {
  return s
    .replace(/\\n/g, '\n')
    .replace(/\\t/g, '\t')
    .replace(/\\r/g, '')
    .replace(/\\"/g, '"')
    .replace(/\\\\/g, '\\');
}

/** Unescapes JSON-encoded tool payloads and pretty-prints JSON for readability. */
function formatToolContent(raw?: string): string {
  if (raw == null) return '';
  const trimmed = raw.trim();
  if (trimmed.startsWith('"')) {
    try {
      const parsed = JSON.parse(trimmed);
      if (typeof parsed === 'string') {
        return /\\[ntr"\\]/.test(parsed) ? unescapeText(parsed) : parsed;
      }
      return JSON.stringify(parsed, null, 2);
    } catch {
      // fall through to raw display
    }
  }
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      return JSON.stringify(JSON.parse(trimmed), null, 2);
    } catch {
      // fall through to raw display
    }
  }
  return raw;
}

const s: Record<string, React.CSSProperties> = {
  wrapper: {
    background: '#f8fafc',
    border: '1px solid #e2e8f0',
    borderRadius: 9,
    margin: '0.5rem 0',
    overflow: 'hidden',
    fontSize: '0.9rem',
    // Keep the block at natural height so the parent bubble body scrolls
    // instead of compressing this card (flex-shrink would clip content).
    flexShrink: 0,
  },
  header: {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    padding: '0.6rem 0.9rem',
    cursor: 'pointer',
    userSelect: 'none',
    background: '#eef2ff',
    borderBottom: '1px solid #e2e8f0',
  },
  icon: { color: '#6366f1', fontWeight: 700, fontSize: '0.82rem' },
  name: { color: '#3730a3', fontWeight: 600 },
  id: { color: '#94a3b8', marginLeft: 'auto', fontSize: '0.78rem', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' },
  body: {
    padding: '0.85rem 1rem',
    color: '#334155',
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-all',
    maxHeight: 320,
    overflowY: 'auto',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.85rem',
    lineHeight: 1.55,
  },
  label: {
    fontSize: '0.72rem',
    fontWeight: 700,
    textTransform: 'uppercase',
    letterSpacing: '0.06em',
    color: '#94a3b8',
    marginBottom: 4,
  },
  inputSection: {
    padding: '0.7rem 1rem 0.3rem',
  },
  resultSection: {
    padding: '0.3rem 1rem 0.85rem',
  },
};

/** Renders tool output with grep-style `file:line:content` lines cleaned up. */
function renderOutput(text: string): React.ReactNode[] {
  const lines = text.split('\n');
  return lines.map((line, i) => {
    const m = line.match(/^(?:\.\.\/)+([^:]+?):(\d+):(.*)$/);
    if (m) {
      return (
        <div key={i} style={{ display: 'flex' }}>
          <span style={{ color: '#6366f1', fontWeight: 600 }}>{m[1]}</span>
          <span style={{ color: '#94a3b8' }}>:{m[2]}:</span>
          <span style={{ flex: 1 }}>{m[3]}</span>
        </div>
      );
    }
    return (
      <div key={i} style={{ display: 'flex' }}>
        <span style={{
          color: '#cbd5e1', minWidth: '2.4em', textAlign: 'right',
          paddingRight: 10, userSelect: 'none', flexShrink: 0,
        }}>
          {i + 1}
        </span>
        <span style={{ flex: 1, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
          {line || '\u00a0'}
        </span>
      </div>
    );
  });
}

const GUTTER: React.CSSProperties = {
  color: '#cbd5e1', minWidth: '2.4em', textAlign: 'right',
  paddingRight: 10, userSelect: 'none', flexShrink: 0,
};

/** Heuristic language detection for syntax-highlightable tool content. */
function detectCodeLanguage(text: string): string | null {
  const t = text.trimStart();
  if (t.startsWith('{') || t.startsWith('[')) return 'json';
  if (t.startsWith('<')) return 'xml';
  if (t.startsWith('package ') || t.startsWith('import java') || t.startsWith('public ') || t.startsWith('@Override')) return 'java';
  if (t.startsWith('#!/')) return 'bash';
  if (t.startsWith('function ') || t.startsWith('const ') || t.startsWith('let ') || t.startsWith('export ')) return 'javascript';
  return null;
}

function CodeBlock({ code, language }: { code: string; language: string }) {
  return (
    <div style={{
      maxHeight: 320, overflowY: 'auto', fontSize: '0.85rem', lineHeight: 1.55,
      fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    }}>
      <Highlight theme={themes.github} code={code} language={language}>
        {({ style, tokens, getTokenProps }) => (
          <div style={{ ...style, margin: 0, padding: '0.6rem 1rem', backgroundColor: 'transparent' }}>
            {tokens.map((line, i) => (
              <div key={i} style={{ display: 'flex' }}>
                <span style={GUTTER}>{i + 1}</span>
                <span style={{ flex: 1, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                  {line.map((token, key) => (
                    <span key={key} {...getTokenProps({ token })} />
                  ))}
                </span>
              </div>
            ))}
          </div>
        )}
      </Highlight>
    </div>
  );
}

/** Picks syntax highlighting for code-like content, else the plain/grep renderer. */
function renderContent(text: string): React.ReactNode {
  const lang = detectCodeLanguage(text);
  if (lang) {
    return <CodeBlock code={text} language={lang} />;
  }
  return <div style={s.body}>{renderOutput(text)}</div>;
}

export default function ToolCallBlock({ toolName, toolCallId, input, result }: Props) {
  // Open while the tool is producing output, collapse once the result lands.
  const [open, setOpen] = useState(result == null);
  useEffect(() => {
    if (result != null) setOpen(false);
  }, [result]);
  const inputText = useMemo(() => formatToolContent(input), [input]);
  const resultText = useMemo(() => formatToolContent(result), [result]);
  return (
    <div style={s.wrapper}>
      <div style={s.header} onClick={() => setOpen(o => !o)}>
        <span style={s.icon}>{open ? '▼' : '▶'}</span>
        <span style={s.name}>Tool: {toolName}</span>
        <span style={s.id}>{toolCallId.slice(0, 10)}</span>
      </div>
      {open && (inputText || resultText) && (
        <>
          {inputText != null && (
            <div style={s.inputSection}>
              <div style={s.label}>Input</div>
              {renderContent(inputText)}
            </div>
          )}
          {resultText != null && (
            <div style={s.resultSection}>
              <div style={s.label}>Result</div>
              {renderContent(resultText)}
            </div>
          )}
        </>
      )}
      {open && !inputText && !resultText && (
        <div style={{ ...s.body, color: '#94a3b8' }}>Running…</div>
      )}
    </div>
  );
}
