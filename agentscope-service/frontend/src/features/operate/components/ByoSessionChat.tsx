import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { ApiError } from '@/lib/apiClient';
import { fetchSessionMessages, sendSessionUserMessage, type SessionMessageItem } from '../api';

/**
 * BYO / Operate chat: inject via control-plane user-message, then poll GET messages.
 */
export function ByoSessionChat({
  sessionId,
  readOnly = false,
  composerOnly = false,
}: {
  sessionId: string;
  readOnly?: boolean;
  composerOnly?: boolean;
}) {
  const qc = useQueryClient();
  const [input, setInput] = useState('');
  const messages = useQuery({
    queryKey: ['byo-session-messages', sessionId],
    queryFn: () => fetchSessionMessages(sessionId, { limit: 200, fromEnd: true }),
    enabled: !!sessionId && !composerOnly,
    refetchInterval: composerOnly ? false : 3_000,
  });
  const send = useMutation({
    mutationFn: (content: string) => sendSessionUserMessage(sessionId, content),
    onSuccess: () => {
      setInput('');
      void qc.invalidateQueries({ queryKey: ['byo-session-messages', sessionId] });
      void qc.invalidateQueries({ queryKey: ['runtime-messages', sessionId] });
    },
  });
  const items = (messages.data?.messages || []) as SessionMessageItem[];
  const err =
    send.error instanceof ApiError
      ? send.error.body || send.error.message
      : send.error instanceof Error
        ? send.error.message
        : '';
  return (
    <div className="flex h-full min-h-0 flex-col">
      {!composerOnly && (
        <div className="min-h-0 flex-1 space-y-3 overflow-y-auto p-4">
          {items.length === 0 && (
            <p className="text-sm text-muted-foreground">
              No messages yet. Send one to wake the agent.
            </p>
          )}
          {items.map((m, i) => (
            <div key={`${m.seq ?? i}-${m.role}`} className="text-sm">
              <div className="font-medium capitalize text-muted-foreground">{m.role || 'message'}</div>
              <div className="whitespace-pre-wrap">{m.content}</div>
            </div>
          ))}
        </div>
      )}
      {err ? <p className="px-4 py-1 text-sm text-red-600">{err}</p> : null}
      <form
        className="flex shrink-0 gap-2 border-t border-border p-3"
        onSubmit={(e) => {
          e.preventDefault();
          const content = input.trim();
          if (!content || readOnly || send.isPending) return;
          send.mutate(content);
        }}
      >
        <input
          className="min-w-0 flex-1 rounded-md border border-border bg-white px-3 py-2 text-sm"
          value={input}
          placeholder={readOnly ? 'Read only' : 'Message this session'}
          disabled={readOnly || send.isPending}
          onChange={(e) => setInput(e.target.value)}
        />
        <Button type="submit" size="sm" disabled={readOnly || send.isPending || !input.trim()}>
          Send
        </Button>
      </form>
    </div>
  );
}
