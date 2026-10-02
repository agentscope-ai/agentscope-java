import React from 'react';
import { useOutletContext } from 'react-router-dom';
import ChannelBindingTable from '../components/ChannelBindingTable';

export default function AgentChannelsPage() {
  const { agentId } = useOutletContext<{ agentId: string }>();
  return <ChannelBindingTable agentId={agentId} />;
}
