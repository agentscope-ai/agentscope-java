import React from 'react';
import { useOutletContext, useParams, useSearchParams } from 'react-router-dom';
import SessionTranscript from '../components/SessionTranscript';

export default function AgentSessionDetailPage() {
  const { agentId } = useOutletContext<{ agentId: string }>();
  const { key } = useParams<{ key: string }>();
  const [searchParams] = useSearchParams();
  const managedSessionId = searchParams.get('managed') ?? undefined;
  if (!key) return <div style={{ padding: 24 }}>Missing session key.</div>;
  return <SessionTranscript agentId={agentId} sessionKey={key} managedSessionId={managedSessionId} />;
}
