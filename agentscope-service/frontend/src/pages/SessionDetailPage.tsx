/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import React, { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  getManagedSession,
  ManagedSession,
  parseTeamExternalKey,
  teamDetailPath,
} from '../api/managedSessions';
import ChatPanel from '../components/ChatPanel';
import SessionTranscript from '../components/SessionTranscript';

type Tab = 'chat' | 'details';

const S: Record<string, React.CSSProperties> = {
  root: { display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 },
  bar: {
    display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap',
    padding: '14px 28px 0', borderBottom: '1px solid #e2e8f0', background: '#ffffff', flexShrink: 0,
  },
  back: {
    color: '#6366f1', textDecoration: 'none', fontSize: '0.85rem', fontWeight: 500,
  },
  title: { fontSize: '1.05rem', fontWeight: 700, color: '#0f172a', margin: 0 },
  meta: {
    fontSize: '0.78rem', color: '#94a3b8', fontFamily: 'ui-monospace, Menlo, monospace',
  },
  teamTag: {
    fontSize: '0.72rem', textTransform: 'uppercase', letterSpacing: '0.04em', fontWeight: 600,
    padding: '2px 8px', borderRadius: 6, color: '#0f766e', background: '#ccfbf1',
  },
  tabs: { display: 'flex', gap: 4, marginLeft: 8 },
  tab: {
    background: 'transparent', border: 'none', borderBottom: '2px solid transparent',
    padding: '12px 16px', cursor: 'pointer', fontSize: '0.9rem', color: '#64748b', fontWeight: 500,
    marginBottom: -1,
  },
  tabActive: { color: '#0f172a', fontWeight: 600, borderBottomColor: '#6366f1' },
  body: { flex: 1, minHeight: 0, overflow: 'auto' },
  bodyChat: { flex: 1, minHeight: 0, overflow: 'hidden', display: 'flex', flexDirection: 'column' },
  banner: {
    flexShrink: 0,
    margin: '12px 28px 0',
    padding: '12px 14px',
    borderRadius: 10,
    border: '1px solid #99f6e4',
    background: '#f0fdfa',
    color: '#115e59',
    fontSize: '0.88rem',
    lineHeight: 1.5,
  },
  bannerLink: { color: '#0f766e', fontWeight: 600 },
  chatWrap: { flex: 1, minHeight: 0 },
  err: { padding: 32, color: '#dc2626' },
  loading: { padding: 32, color: '#94a3b8' },
};

export default function SessionDetailPage() {
  const { sessionId = '' } = useParams<{ sessionId: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const tabParam = searchParams.get('tab');
  const tab: Tab = tabParam === 'details' ? 'details' : 'chat';
  const [session, setSession] = useState<ManagedSession | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  useEffect(() => {
    if (!sessionId) return;
    let cancelled = false;
    setLoading(true);
    setErr(null);
    getManagedSession(sessionId)
      .then(s => { if (!cancelled) setSession(s); })
      .catch(e => {
        if (!cancelled) {
          setSession(null);
          setErr(e instanceof Error ? e.message : 'Failed to load session');
        }
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [sessionId]);

  function setTab(next: Tab) {
    const params = new URLSearchParams(searchParams);
    if (next === 'chat') params.delete('tab');
    else params.set('tab', 'details');
    setSearchParams(params, { replace: true });
  }

  if (!sessionId) {
    return <div style={S.err}>Missing session id. <Link to="/sessions">Back to sessions</Link></div>;
  }

  if (loading) {
    return <div style={S.loading}>Loading session…</div>;
  }

  if (err || !session) {
    return (
      <div style={S.err}>
        {err || 'Session not found.'}{' '}
        <Link to="/sessions">Back to sessions</Link>
        {' · '}
        <Link to="/sessions/new">Create session</Link>
      </div>
    );
  }

  const teamRef = parseTeamExternalKey(session.externalKey);
  const fromTeam = !!teamRef;

  return (
    <div style={S.root}>
      <div style={S.bar}>
        <Link
          to={`/sessions?agentId=${encodeURIComponent(session.agentId)}`}
          style={S.back}
        >
          ← Sessions
        </Link>
        <h1 style={S.title}>Session</h1>
        <span style={S.meta} title={session.id}>{session.id}</span>
        {fromTeam && (
          <span style={S.teamTag} title={session.externalKey || undefined}>Team</span>
        )}
        <span style={{ flex: 1 }} />
        <div style={S.tabs}>
          <button
            type="button"
            style={{ ...S.tab, ...(tab === 'chat' ? S.tabActive : {}) }}
            onClick={() => setTab('chat')}
          >
            {fromTeam ? 'Transcript' : 'Chat'}
          </button>
          <button
            type="button"
            style={{ ...S.tab, ...(tab === 'details' ? S.tabActive : {}) }}
            onClick={() => setTab('details')}
          >
            Details
          </button>
        </div>
      </div>
      <div style={tab === 'chat' ? S.bodyChat : S.body}>
        {tab === 'chat' ? (
          <>
            {teamRef && (
              <div style={S.banner}>
                This session was started by Agent Team{' '}
                <strong>
                  {teamRef.namespace}/{teamRef.teamName}
                </strong>{' '}
                (member <strong>{teamRef.memberName}</strong>). Direct chat here is disabled —
                continue the conversation from the{' '}
                <Link to={teamDetailPath(teamRef)} style={S.bannerLink}>
                  team detail page
                </Link>
                .
              </div>
            )}
            <div style={S.chatWrap}>
              <ChatPanel
                sessionId={session.id}
                agentId={session.agentId}
                readOnly={fromTeam}
              />
            </div>
          </>
        ) : (
          <SessionTranscript
            agentId={session.agentId}
            sessionId={session.id}
            embedded
            onDeleted={() => navigate('/sessions', { replace: true })}
          />
        )}
      </div>
    </div>
  );
}
