/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { useEffect, useRef, useState } from 'react';
import { downloadAgentEvents } from '../api/agentEventExport';

export default function SessionEventExport({ sessionId, through, turnId }: {
  sessionId: string; through: string; turnId?: string;
}) {
  const controller = useRef<AbortController>();
  const mounted = useRef(false);
  const [busy, setBusy] = useState(false);
  const [count, setCount] = useState(0);
  const [outcome, setOutcome] = useState('');
  const [error, setError] = useState('');
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; controller.current?.abort(); };
  }, []);
  async function download(turn?: string) {
    const current = new AbortController(); controller.current = current;
    setBusy(true); setCount(0); setOutcome(''); setError('');
    const timeout = setTimeout(() => current.abort(new Error('Export exceeded two minutes. Use the streaming Service SDK.')), 120000);
    try {
      const result = await downloadAgentEvents(sessionId, { through, turn, signal: current.signal,
        onProgress: value => { if (mounted.current) setCount(value); } });
      if (!mounted.current || current.signal.aborted) return;
      const url = URL.createObjectURL(result.blob);
      const link = document.createElement('a'); link.href = url;
      link.download = `${turn ? 'turn' : 'session'}-${(turn ?? sessionId).replace(/[^a-zA-Z0-9_-]/g, '_')}.ndjson`;
      link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      setOutcome(`${result.count} committed events exported.`);
    } catch (cause) {
      if (mounted.current) {
        const reason = current.signal.aborted ? current.signal.reason : cause;
        setError(reason instanceof Error && reason.name !== 'AbortError' ? reason.message : 'Export cancelled. No file was saved.');
      }
    } finally {
      clearTimeout(timeout);
      if (mounted.current) setBusy(false);
    }
  }
  return <div style={{ padding: '12px 0' }}>
    <button disabled={busy || !through} onClick={() => void download()}>Export Session events</button>{' '}
    {turnId && <button disabled={busy || !through} onClick={() => void download(turnId)}>Export selected Turn events</button>}{' '}
    {busy && <><span role="status">Exporting · {count} verified events</span>{' '}
      <button onClick={() => controller.current?.abort()}>Cancel export</button></>}
    {outcome && <p role="status">{outcome}</p>}
    {error && <p role="alert">{error}</p>}
    <small style={{ display: 'block' }}>Exports committed events through the current view. Refresh for newer events. Console downloads are limited to 32 MiB.</small>
  </div>;
}
