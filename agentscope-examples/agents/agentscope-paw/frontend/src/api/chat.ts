export interface ChatRequest {
  message: string;
  sessionKey?: string;
}

export interface ChatEvent {
  type: 'token' | 'tool_call' | 'tool_result' | 'done' | 'error' | string;
  data?: string;
  toolName?: string;
  toolInput?: string;
  toolResult?: string;
  error?: string;
  sessionKey?: string;
}

export interface CurrentSession {
  sessionKey: string | null;
  exists: boolean;
}

export async function currentSession(agentId: string): Promise<CurrentSession> {
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/chat/session`);
  if (!res.ok) throw new Error(`Failed to resolve current session: ${res.status}`);
  return res.json();
}

export async function* stream(agentId: string, req: ChatRequest): AsyncGenerator<ChatEvent> {
  const res = await fetch(`/api/agents/${encodeURIComponent(agentId)}/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });
  if (!res.ok || !res.body) throw new Error(`Chat stream failed: ${res.status}`);

  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const evt = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      const lines = evt.split('\n');
      let data = '';
      for (const ln of lines) if (ln.startsWith('data:')) data += ln.slice(5).trim();
      if (!data) continue;
      try {
        yield JSON.parse(data) as ChatEvent;
      } catch {
        yield { type: 'token', data } as ChatEvent;
      }
    }
  }
}
