import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Archive, ArchiveRestore, ExternalLink, MessageSquare, Pin, PinOff, Plus } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  createChat,
  listChatAgents,
  listChats,
  sendChatTurn,
  updateChat,
  type Chat,
} from '@/api/chats';
import { getRoles } from '@/api/auth';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { ConversationSurface } from '@/features/conversation/ConversationSurface';
import { runtimeEventsToConversation, runtimeEventsToMessages } from '@/features/conversation/adapters';
import type { ConversationMessage } from '@/features/conversation/model';
import { useSessionEvents } from '@/features/operate/lib/useSessionEvents';
import { formatRelative } from '@/lib/format';

function firstTitle(message: string): string {
  const value = message.trim().replace(/\s+/g, ' ');
  return value.length > 64 ? `${value.slice(0, 61)}…` : value;
}

function ChatWorkspace({ chat, onChanged }: { chat: Chat; onChanged: (chat?: Chat) => void }) {
  const scope = useControlPlaneScope();
  const roles = getRoles().map(role => role.toLowerCase());
  const canInspectSession = roles.includes('admin') || roles.includes('operator');
  const queryClient = useQueryClient();
  const [message, setMessage] = useState('');
  const [pending, setPending] = useState(false);
  const [pendingAfter, setPendingAfter] = useState(0);
  const [pendingMessage, setPendingMessage] = useState('');
  const [error, setError] = useState('');
  const timeline = useSessionEvents(chat.sessionId, { chatId: chat.id, enabled: true });
  const messages = runtimeEventsToMessages(timeline.events);
  const events = runtimeEventsToConversation(timeline.events);
  const latestUserSeq = Math.max(0, ...timeline.events.filter(event => event.role === 'user').map(event => event.seq ?? 0));
  const latestTerminalSeq = Math.max(0, ...timeline.events.filter(event => {
    const type = (event.eventType ?? '').toLowerCase();
    return event.role === 'assistant' || type.includes('turn.completed') || type.includes('turn.failed') ||
      type.includes('turn.cancelled');
  }).map(event => event.seq ?? 0));
  const runtimeBusy = latestUserSeq > latestTerminalSeq;

  useEffect(() => {
    setMessage('');
    setPending(false);
    setPendingMessage('');
    setError('');
  }, [chat.id]);

  useEffect(() => {
    if (!pending) return;
    const finished = timeline.events.some((event) => {
      const seq = event.seq ?? 0;
      const type = (event.eventType ?? '').toLowerCase();
      return seq > pendingAfter && (event.role === 'assistant' || type.includes('completed') ||
        type.includes('failed') || type.includes('cancelled'));
    });
    if (finished) {
      setPending(false);
      setPendingMessage('');
      void queryClient.invalidateQueries({ queryKey: ['chats'] });
    }
  }, [pending, pendingAfter, queryClient, timeline.events]);

  const optimistic: ConversationMessage[] = pendingMessage &&
    !messages.some(item => item.role === 'user' && item.blocks.some(block => block.text === pendingMessage))
    ? [{
      id: `pending-${chat.id}`,
      role: 'user',
      blocks: [{ id: `pending-text-${chat.id}`, kind: 'text', text: pendingMessage }],
      occurredAt: new Date().toISOString(),
      state: 'complete',
    }]
    : [];

  async function submit(value: string) {
    if (!value.trim() || pending || runtimeBusy) return;
    const after = Math.max(0, ...timeline.events.map(event => event.seq ?? 0));
    setPendingAfter(after);
    setPendingMessage(value.trim());
    setPending(true);
    setError('');
    try {
      await sendChatTurn(chat.id, value.trim());
      setMessage('');
      await timeline.refresh();
    } catch (cause) {
      setPending(false);
      setPendingMessage('');
      setError(cause instanceof Error ? cause.message : 'Failed to send message');
    }
  }

  const change = useMutation({
    mutationFn: (patch: { pinned?: boolean; status?: 'active' | 'archived' }) => updateChat(chat, patch),
    onSuccess: ({ chat: value }) => {
      void queryClient.invalidateQueries({ queryKey: ['chats'] });
      onChanged(value.status === 'archived' ? undefined : value);
    },
  });

  return <div className="min-w-0 space-y-3">
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h2 className="text-xl font-semibold">{chat.title}</h2>
        <div className="mt-1 flex items-center gap-2 text-sm text-muted-foreground">
          <span>{chat.agentName}</span><Badge>{chat.status}</Badge>
          <span>Updated {formatRelative(chat.updatedAt)}</span>
        </div>
      </div>
      <div className="flex gap-2">
        {canInspectSession && <Button asChild size="sm" variant="outline"><Link to={scope.scopedPath(`/work/sessions/${encodeURIComponent(chat.sessionId)}`)}>Session diagnostics<ExternalLink className="h-3.5 w-3.5" /></Link></Button>}
        <Button asChild size="sm" variant="outline"><Link to={scope.scopedPath(`/work/issues?new=1&fromChat=${encodeURIComponent(chat.id)}&title=${encodeURIComponent(chat.title)}&description=${encodeURIComponent(`Created from Chat with ${chat.agentName}.`)}`)}>Create issue</Link></Button>
        <Button size="sm" variant="outline" disabled={change.isPending} onClick={() => change.mutate({ pinned: !chat.pinned })}>
          {chat.pinned ? <PinOff className="h-4 w-4" /> : <Pin className="h-4 w-4" />}{chat.pinned ? 'Unpin' : 'Pin'}
        </Button>
        <Button size="sm" variant="outline" disabled={change.isPending} onClick={() => change.mutate({ status: chat.status === 'archived' ? 'active' : 'archived' })}>
          {chat.status === 'archived' ? <ArchiveRestore className="h-4 w-4" /> : <Archive className="h-4 w-4" />}{chat.status === 'archived' ? 'Restore' : 'Archive'}
        </Button>
      </div>
    </div>
    <ConversationSurface
      className="min-h-[42rem] max-h-[calc(100vh-15rem)]"
      messages={[...messages, ...optimistic]}
      events={events}
      source="personal chat"
      loading={timeline.loading}
      error={error || timeline.error}
      emptyMessage={`Start a personal conversation with ${chat.agentName}. This Chat is separate from Issues.`}
      composer={chat.status === 'active' ? {
        value: message,
        onChange: setMessage,
        onSubmit: submit,
        busy: pending || runtimeBusy,
        disabled: pending || runtimeBusy,
        placeholder: pending || runtimeBusy ? `${chat.agentName} is working…` : `Message ${chat.agentName}…`,
      } : undefined}
      hasEarlierMessages={timeline.hasEarlier}
      loadingEarlierMessages={timeline.loadingEarlier}
      onLoadEarlierMessages={() => void timeline.loadEarlier()}
      hasEarlierEvents={timeline.hasEarlier}
      loadingEarlierEvents={timeline.loadingEarlier}
      onLoadEarlierEvents={() => void timeline.loadEarlier()}
    />
  </div>;
}

export default function ChatPage() {
  const scope = useControlPlaneScope();
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const [archived, setArchived] = useState(false);
  const [agentId, setAgentId] = useState(params.get('agent') ?? '');
  const [draft, setDraft] = useState('');
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState('');
  const chats = useQuery({
    queryKey: ['chats', scope.tenant, scope.namespace, archived],
    queryFn: () => listChats(scope.tenant, scope.namespace, archived),
  });
  const agents = useQuery({
    queryKey: ['chat-agents', scope.tenant, scope.namespace],
    queryFn: () => listChatAgents(scope.tenant, scope.namespace),
  });
  const chatId = params.get('chat') ?? '';
  const newMode = params.get('new') === '1';
  const selected = useMemo(() => chats.data?.items.find(item => item.id === chatId), [chatId, chats.data?.items]);

  useEffect(() => {
    if (!chatId && !newMode && !params.get('agent') && chats.data?.items[0]) {
      const next = new URLSearchParams(params);
      next.set('chat', chats.data.items[0].id);
      setParams(next, { replace: true });
    }
  }, [chatId, chats.data?.items, newMode, params, setParams]);

  function select(chat?: Chat, startNew = false) {
    const next = new URLSearchParams(params);
    if (chat) {
      next.delete('agent');
      next.delete('new');
      next.set('chat', chat.id);
    } else {
      next.delete('chat');
      if (startNew) next.set('new', '1');
      else next.delete('new');
    }
    setParams(next);
  }

  async function start(value: string) {
    if (!agentId || !value.trim() || creating) return;
    setCreating(true);
    setError('');
    try {
      const result = await createChat({ tenant: scope.tenant, namespace: scope.namespace, agentId, title: firstTitle(value) });
      await sendChatTurn(result.chat.id, value.trim());
      await queryClient.invalidateQueries({ queryKey: ['chats'] });
      setDraft('');
      select(result.chat);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Failed to start Chat');
    } finally {
      setCreating(false);
    }
  }

  const availableAgents = agents.data?.items ?? [];
  const chosenAgent = availableAgents.find(agent => agent.id === agentId);
  return <Page>
    <PageHeader title="Chat" description="Personal, multi-turn conversations with an Agent. Create an Issue when work needs shared ownership and tracking." actions={
      <Button onClick={() => select(undefined, true)}><Plus className="mr-2 h-4 w-4" />New chat</Button>
    } />
    <div className="grid gap-5 lg:grid-cols-[19rem_minmax(0,1fr)]">
      <aside className="rounded-2xl border bg-white p-3">
        <div className="mb-3 flex rounded-lg bg-muted p-1">
          <button type="button" className={`flex-1 rounded-md px-2 py-1.5 text-sm ${!archived ? 'bg-white shadow-sm' : 'text-muted-foreground'}`} onClick={() => { setArchived(false); select(); }}>Active</button>
          <button type="button" className={`flex-1 rounded-md px-2 py-1.5 text-sm ${archived ? 'bg-white shadow-sm' : 'text-muted-foreground'}`} onClick={() => { setArchived(true); select(); }}>Archived</button>
        </div>
        <div className="space-y-1">
          {(chats.data?.items ?? []).map(chat => <button key={chat.id} type="button" onClick={() => select(chat)} className={`w-full rounded-xl px-3 py-3 text-left ${chat.id === chatId ? 'bg-indigo-50 text-indigo-950' : 'hover:bg-muted/60'}`}>
            <div className="flex items-center gap-2"><span className="min-w-0 flex-1 truncate font-medium">{chat.title}</span>{chat.pinned && <Pin className="h-3.5 w-3.5" />}</div>
            <div className="mt-1 flex items-center justify-between gap-2 text-xs text-muted-foreground"><span className="truncate">{chat.agentName}</span><span className="shrink-0">{formatRelative(chat.updatedAt)}</span></div>
          </button>)}
          {!chats.isLoading && !(chats.data?.items ?? []).length && <p className="px-3 py-8 text-center text-sm text-muted-foreground">No {archived ? 'archived' : 'active'} chats.</p>}
        </div>
      </aside>
      {selected ? <ChatWorkspace key={selected.id} chat={selected} onChanged={select} /> : <div className="space-y-4 rounded-2xl border bg-white p-6">
        <div><h2 className="text-xl font-semibold">Start a new chat</h2><p className="mt-1 text-sm text-muted-foreground">Choose one conversation-capable Agent. Team and Workflow work should start from an Issue.</p></div>
        <label className="grid max-w-xl gap-1.5 text-sm">Agent<select className="h-11 rounded-lg border bg-background px-3" value={agentId} onChange={event => setAgentId(event.target.value)}>
          <option value="">Choose an Agent…</option>
          {availableAgents.map(agent => <option key={agent.id} value={agent.id} disabled={agent.capability.state !== 'available'}>{agent.name}{agent.capability.state === 'available' ? '' : ` — ${agent.capability.state}`}</option>)}
        </select></label>
        {chosenAgent && <div className="max-w-xl rounded-xl border p-4 text-sm"><div className="flex items-center gap-2"><MessageSquare className="h-4 w-4" /><strong>{chosenAgent.name}</strong><Badge tone={chosenAgent.capability.state === 'available' ? 'success' : 'warning'}>{chosenAgent.capability.state}</Badge></div><p className="mt-2 text-muted-foreground">{chosenAgent.description || chosenAgent.capability.reason}</p></div>}
        {error && <p className="text-sm text-red-600">{error}</p>}
        {availableAgents.length ? <ConversationSurface
          className="min-h-[28rem]"
          messages={[]}
          events={[]}
          source="new personal chat"
          emptyMessage={agentId ? 'Send the first message to create this Chat.' : 'Choose an Agent to begin.'}
          composer={{ value: draft, onChange: setDraft, onSubmit: start, busy: creating, disabled: creating || !agentId || chosenAgent?.capability.state !== 'available', placeholder: 'What would you like to work through?' }}
        /> : !agents.isLoading && <EmptyState title="No conversation-capable Agents" description="Connect a runtime and configure an Agent with conversation support first." />}
      </div>}
    </div>
  </Page>;
}
