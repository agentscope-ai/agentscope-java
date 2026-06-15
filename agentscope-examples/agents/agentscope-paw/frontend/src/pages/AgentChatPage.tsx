import React from 'react';
import { useOutletContext } from 'react-router-dom';
import ChatPanel from '../components/ChatPanel';

export default function AgentChatPage() {
  const { agentId } = useOutletContext<{ agentId: string }>();
  return <ChatPanel agentId={agentId} />;
}
