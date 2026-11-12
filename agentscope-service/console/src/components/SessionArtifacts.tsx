import { useRef, useState } from 'react';
import { AgentSessionEvent, deleteAgentArtifact, downloadAgentArtifact, publishAgentArtifact, uploadAgentFile } from '../api/agentSessions';

/** Uploaded files become deliverables only after an explicit publication. */
export default function SessionArtifacts({ sessionId, artifacts, filesSupported, readOnly }: {
  sessionId: string; artifacts: AgentSessionEvent[]; filesSupported: boolean; readOnly: boolean;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File>();
  const pending = useRef<{ file: File; key: string; fileId?: string }>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function act(operation: () => Promise<void>) {
    setBusy(true); setError('');
    try { await operation(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : String(cause)); }
    finally { setBusy(false); }
  }
  async function publish() {
    if (!file) return;
    if (pending.current?.file !== file) pending.current = { file, key: crypto.randomUUID() };
    const request = pending.current!;
    await act(async () => {
      if (!request.fileId) request.fileId = (await uploadAgentFile(sessionId, file, `${request.key}-file`)).file_id;
      await publishAgentArtifact(sessionId, request.fileId, `${request.key}-publish`);
      pending.current = undefined; setFile(undefined);
      if (input.current) input.current.value = '';
    });
  }
  async function download(id: string, name: string) {
    const blob = await downloadAgentArtifact(sessionId, id);
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a'); link.href = url; link.download = name; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  function external(value: unknown) {
    try {
      const url = new URL(String(value));
      return url.protocol === 'https:' && !url.username && !url.password ? url.href : undefined;
    } catch { return undefined; }
  }
  if (!artifacts.length && (!filesSupported || readOnly)) return null;
  return <section>
    <h3>Artifacts</h3>
    {error && <p role="alert">{error}</p>}
    {!readOnly && filesSupported && <div>
      <input ref={input} type="file" aria-label="Result file to publish" disabled={busy} onChange={event => setFile(event.target.files?.[0])} />
      <button disabled={busy || !file} onClick={() => void publish()}>Publish result file</button>
    </div>}
    {artifacts.map(event => {
      const id = String(event.data.artifact_id);
      const name = String(event.data.name ?? id);
      const uri = external(event.data.uri);
      const downloadable = !!event.data.file_id || !!event.data.download_url;
      return <div key={id} style={{ padding: '8px 0' }}>
        <span>{name}</span>{' '}
        {downloadable && <button disabled={busy} onClick={() => void act(() => download(id, name))}>Download</button>}{' '}
        {uri && <a href={uri} target="_blank" rel="noopener noreferrer">Open reference</a>}{' '}
        {!readOnly && <button disabled={busy} onClick={() => void act(() => deleteAgentArtifact(sessionId, id))}>Remove record</button>}
      </div>;
    })}
    {!readOnly && artifacts.length > 0 && <small>Removing a record keeps the uploaded file.</small>}
  </section>;
}
