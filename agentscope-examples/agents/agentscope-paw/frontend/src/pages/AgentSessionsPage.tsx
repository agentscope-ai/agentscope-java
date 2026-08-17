import React from 'react';
import { useOutletContext } from 'react-router-dom';
import SessionInboxList from '../components/SessionInboxList';

export default function AgentSessionsPage() {
  const { agentId } = useOutletContext<{ agentId: string }>();
  return <SessionInboxList agentId={agentId} />;
}
