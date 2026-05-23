import React, { useEffect, useRef, useState } from 'react';
import { getToken } from '../api/auth';
import AppShell from '../components/AppShell';
import ToolCallBlock from '../components/ToolCallBlock';

interface ChatMessage {
  id: string;
  role: 'user' | 'assistant' | 'system' | 'tool';
  content: string;
  toolCalls?: { id: string; name: string; result?: string }[];
}

const s: Record<string, React.CSSProperties> = {
  // AppShell handles the topbar; the page now fills its content slot
  page: { display: 'flex', flexDirection: 'column', height: '100%', background: '#0f1117' },
  body: { display: 'flex', flex: 1, overflow: 'hidden' },
  main: { flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' },
  contextBar: {
    height: 38,
    borderBottom: '1px solid #1e2235',
    background: '#0d0f18',
    display: 'flex',
    alignItems: 'center',
    padding: '0 1.25rem',
    gap: 8,
    fontSize: '0.8rem',
    color: '#4b5571',
    flexShrink: 0,
  },
  agentBadge: {
    background: '#1e2235',
    borderRadius: 4,
    padding: '2px 9px',
    fontSize: '0.78rem',
    color: '#a5b4fc',
    fontWeight: 500,
  },
  grow: { flex: 1 },
  messages: {
    flex: 1,
    overflowY: 'auto',
    padding: '1.5rem 2rem',
    display: 'flex',
    flexDirection: 'column',
    gap: '1rem',
  },
  emptyState: {
    color: '#374056',
    fontSize: '0.88rem',
    textAlign: 'center' as const,
    marginTop: '4rem',
    lineHeight: 1.8,
  },
  thinking: {
    alignSelf: 'flex-start',
    color: '#4b5571',
    fontSize: '0.8rem',
    fontStyle: 'italic',
  },
  inputRow: {
    padding: '0.9rem 1.25rem',
    borderTop: '1px solid #1e2235',
    display: 'flex',
    gap: 10,
    background: '#0d0f18',
    flexShrink: 0,
  },
  textarea: {
    flex: 1,
    background: '#13151f',
    border: '1px solid #2d3148',
    borderRadius: 8,
    color: '#e2e8f0',
    fontSize: '0.9rem',
    padding: '0.6rem 0.8rem',
    resize: 'none',
    outline: 'none',
    lineHeight: 1.5,
  },
  sendBtn: {
    background: '#6366f1',
    color: '#fff',
    border: 'none',
    borderRadius: 8,
    padding: '0 1.2rem',
    cursor: 'pointer',
    fontWeight: 600,
    fontSize: '0.9rem',
    minWidth: 68,
  },
};

function bubbleStyle(role: string): React.CSSProperties {
  if (role === 'tool') {
    return {
      alignSelf: 'flex-start',
      background: '#0f2027',
      border: '1px solid #1e3a4f',
      borderRadius: 8,
      padding: '4px 12px',
      color: '#a5b4fc',
      fontSize: '0.78rem',
      fontFamily: 'monospace',
      maxWidth: '80%',
    };
  }
  return {
    maxWidth: '76%',
    alignSelf: role === 'user' ? 'flex-end' : 'flex-start',
    background: role === 'user' ? '#3730a3' : '#13151f',
    border: role === 'user' ? '1px solid #4338ca' : '1px solid #1e2235',
    borderRadius: 12,
    padding: '0.65rem 1rem',
    color: '#e2e8f0',
    fontSize: '0.9rem',
    lineHeight: 1.65,
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
  };
}

function uid() {
  return Math.random().toString(36).slice(2);
}

export default function ChatPage() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [thinking, setThinking] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);

  function pushMessages(updater: (prev: ChatMessage[]) => ChatMessage[]) {
    setMessages(prev => updater(prev));
  }

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, thinking]);

  async function send() {
    const text = input.trim();
    if (!text || thinking) return;
    setInput('');
    setThinking(true);

    pushMessages(prev => [...prev, { id: uid(), role: 'user', content: text }]);

    const token = getToken();

    try {
      const res = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ message: text }),
      });

      const reader = res.body!.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';
        for (const line of lines) {
          if (!line.startsWith('data:')) continue;
          const jsonStr = line.slice(5).trim();
          if (!jsonStr) continue;
          try {
            const evt = JSON.parse(jsonStr);
            if (evt.type === 'RUN_DONE') {
              pushMessages(prev => [...prev, { id: uid(), role: 'assistant', content: evt.reply ?? '' }]);
            } else if (evt.type === 'TOOL_CALL') {
              const inputStr = evt.input ? JSON.stringify(evt.input) : '';
              const snippet = inputStr.length > 60 ? inputStr.slice(0, 60) + '…' : inputStr;
              pushMessages(prev => [
                ...prev,
                { id: uid(), role: 'tool', content: `🔧 ${evt.toolName}${snippet ? `(${snippet})` : ''}` },
              ]);
            } else if (evt.type === 'ERROR') {
              pushMessages(prev => [...prev, { id: uid(), role: 'system', content: `Error: ${evt.message}` }]);
            }
          } catch { /* ignore parse errors */ }
        }
      }
    } catch (e: unknown) {
      pushMessages(prev => [...prev, { id: uid(), role: 'system', content: `Network error: ${String(e)}` }]);
    } finally {
      setThinking(false);
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
  }

  return (
    <AppShell>
      <div style={s.page}>
        <div style={s.body}>
          {/* Main chat area */}
          <div style={s.main}>
            {/* Context bar */}
            <div style={s.contextBar}>
              <span>Chatting with</span>
              <span style={s.agentBadge}>📊 DataAgent</span>
              <div style={s.grow} />
              {messages.length > 0 && (
                <button
                  style={{ background: 'transparent', border: 'none', color: '#374056', cursor: 'pointer', fontSize: '0.75rem', padding: '0 4px' }}
                  title="Clear local display (session is preserved on server)"
                  onClick={() => pushMessages(() => [])}
                >
                  Clear display
                </button>
              )}
            </div>

            {/* Message list */}
            <div style={s.messages}>
              {messages.length === 0 && (
                <div style={s.emptyState}>
                  Ask DataAgent about your data.<br />
                  <span style={{ fontSize: '0.78rem' }}>
                    Previous sessions are preserved on the server — switch to Sessions view to see history.
                  </span>
                </div>
              )}

              {messages.map(m => (
                <div key={m.id} style={bubbleStyle(m.role)}>
                  {m.toolCalls?.map(tc => (
                    <ToolCallBlock key={tc.id} toolName={tc.name} toolCallId={tc.id} result={tc.result} />
                  ))}
                  {m.content}
                </div>
              ))}

              {thinking && <div style={s.thinking}>Assistant is thinking…</div>}
              <div ref={bottomRef} />
            </div>

            {/* Input */}
            <div style={s.inputRow}>
              <textarea
                style={s.textarea}
                rows={2}
                placeholder="Type a message… (Enter to send, Shift+Enter for newline)"
                value={input}
                onChange={e => setInput(e.target.value)}
                onKeyDown={handleKeyDown}
                disabled={thinking}
              />
              <button style={s.sendBtn} onClick={send} disabled={thinking || !input.trim()}>
                Send
              </button>
            </div>
          </div>
        </div>
      </div>
    </AppShell>
  );
}
