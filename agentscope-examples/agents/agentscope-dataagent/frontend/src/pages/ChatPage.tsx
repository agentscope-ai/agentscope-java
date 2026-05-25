import React from 'react';
import { useOutletContext } from 'react-router-dom';
import { ACTIVE_AGENT_ID } from '../api/activeAgent';
import ChatHeader from '../components/ChatHeader';
import ChatPanel from '../components/ChatPanel';
import { ShellOutletContext } from '../components/EditTierGate';

export default function ChatPage() {
  const ctx = useOutletContext<ShellOutletContext>();
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <ChatHeader agent={ctx.agent} />
      <div style={{ flex: 1, minHeight: 0 }}>
        <ChatPanel agentId={ACTIVE_AGENT_ID} onSessionUpdate={ctx.bumpSidebar} />
      </div>
    </div>
  );
}
