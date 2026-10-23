import React from 'react';
import { useOutletContext, useParams } from 'react-router-dom';
import SessionTranscript from '../components/SessionTranscript';

export default function AgentSessionDetailPage() {
  const { agentId } = useOutletContext<{ agentId: string }>();
  const { key } = useParams<{ key: string }>();
  if (!key) return <div style={{ padding: 24 }}>Missing session key.</div>;
  return <SessionTranscript agentId={agentId} sessionKey={key} />;
}
