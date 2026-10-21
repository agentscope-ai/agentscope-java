import { useEffect, useRef, useState } from 'react';
import {
  AgentToolTask, AgentTurnCapabilities, WorkspaceRecovery, getAgentCommand,
  listAgentToolTasks, recoverAgentTool, recoverAgentWorkspace,
} from '../api/agentSessions';

export default function ToolExecutionRecovery({ sessionId, turnId, capabilities, readOnly }: {
  sessionId: string; turnId: string; capabilities: AgentTurnCapabilities; readOnly: boolean;
}) {
  const [tasks, setTasks] = useState<AgentToolTask[]>([]);
  const [after, setAfter] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  const controller = useRef<AbortController>();
  useEffect(() => {
    const current = new AbortController(); controller.current = current;
    setTasks([]); setAfter(''); setError('');
    listAgentToolTasks(sessionId, turnId, undefined, current.signal)
      .then(page => { if (!current.signal.aborted) { setTasks(page.data); setAfter(page.next_cursor ?? ''); } })
      .catch(e => { if (!current.signal.aborted) setError(e.message); });
    return () => current.abort();
  }, [sessionId, turnId, revision]);
  async function more() {
    const current = controller.current;
    setBusy(true); setError('');
    try {
      const page = await listAgentToolTasks(sessionId, turnId, after, current?.signal);
      if (current?.signal.aborted) return;
      const merged = new Map(tasks.map(task => [task.task_id, task]));
      for (const task of page.data) merged.set(task.task_id, task);
      if (merged.size > 2000) throw new Error('Tool display limit reached. Refresh to query the current tasks.');
      setTasks([...merged.values()]); setAfter(page.next_cursor ?? '');
    } catch (e) { if (!current?.signal.aborted) setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }
  return <details><summary>Tool execution and recovery</summary>
    <button onClick={() => setRevision(value => value + 1)}>Refresh tool execution</button>
    {error && <p role="alert">{error}</p>}
    {tasks.map(task => <Recovery key={`${turnId}:${task.task_id}:${task.authorization_generation}:${task.workspace_generation}`}
      sessionId={sessionId} task={task} capabilities={capabilities} readOnly={readOnly} />)}
    {after && <button disabled={busy} onClick={() => void more()}>Load more tool executions</button>}
  </details>;
}

function Recovery({ sessionId, task, capabilities, readOnly }: {
  sessionId: string; task: AgentToolTask; capabilities: AgentTurnCapabilities; readOnly: boolean;
}) {
  const [mode, setMode] = useState<WorkspaceRecovery['mode']>('reconnect');
  const [provider, setProvider] = useState('');
  const [sideEffects, setSideEffects] = useState(false);
  const [providerChecked, setProviderChecked] = useState(false);
  const [dataLoss, setDataLoss] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [command, setCommand] = useState<{ command_id: string; command_status: string; failure_code?: string }>();
  const retry = useRef<{ signature: string; key: string }>();
  const controller = useRef(new AbortController());
  useEffect(() => { const current = new AbortController(); controller.current = current; return () => current.abort(); }, []);
  async function submit(workspace: boolean) {
    const recovery: WorkspaceRecovery = { mode, confirm_side_effects: sideEffects,
      ...(provider.trim() ? { provider_id: provider.trim() } : {}),
      ...(mode === 'reconnect' ? { confirm_provider_idle: providerChecked }
        : { confirm_provider_absent: providerChecked, confirm_data_loss: dataLoss }),
    };
    const signature = JSON.stringify([workspace, task.task_id, task.authorization_generation, task.workspace_generation, workspace ? recovery : null]);
    if (retry.current?.signature !== signature) retry.current = { signature, key: crypto.randomUUID() };
    setBusy(true); setError('');
    try {
      const receipt = workspace ? await recoverAgentWorkspace(sessionId, task.turn_id, task, recovery, retry.current.key)
        : await recoverAgentTool(sessionId, task.turn_id, task, retry.current.key);
      if (!controller.current.signal.aborted) setCommand({ command_id: receipt.command_id, command_status: 'accepted' });
    } catch (e) { if (!controller.current.signal.aborted) setError(e instanceof Error ? e.message : String(e)); }
    finally { if (!controller.current.signal.aborted) setBusy(false); }
  }
  const available = (name: string) => capabilities.available_commands.includes(name);
  const workspace = task.environment_type === 'sandbox' && !!task.workspace_generation && !task.workspace_busy
    && ['unknown', 'cancelled', 'queued', 'succeeded', 'failed'].includes(task.status);
  return <article style={{ padding: 12, borderBottom: '1px solid #e2e8f0' }}>
    <p>{task.tool_name} · {task.status} · result {task.result_status}</p>
    <small>{task.failure_code || task.workspace_failure} {task.workspace_status && `Workspace: ${task.workspace_status}`}</small>
    {!readOnly && (task.status === 'unknown' && available('tool_retry') || workspace && available('workspace_recover')) && <div>
      <label><input type="checkbox" checked={sideEffects} onChange={e => setSideEffects(e.target.checked)} /> I confirm the operation may repeat external side effects.</label>
      {task.status === 'unknown' && available('tool_retry') && <button disabled={busy || !sideEffects} onClick={() => void submit(false)}>Retry unknown tool</button>}
      {workspace && available('workspace_recover') && <div>
        <select aria-label="Workspace recovery mode" value={mode} onChange={e => { setMode(e.target.value as typeof mode); setProviderChecked(false); setDataLoss(false); }}>
          <option value="reconnect">Reconnect existing sandbox</option><option value="restore">Restore retained archive</option><option value="discard">Start an empty workspace</option>
        </select>
        {mode === 'reconnect' && <input aria-label="Sandbox provider ID" placeholder="Existing sandbox ID, if needed" value={provider} onChange={e => setProvider(e.target.value)} />}
        <label><input type="checkbox" checked={providerChecked} onChange={e => setProviderChecked(e.target.checked)} /> {mode === 'reconnect' ? 'I have verified the sandbox is idle.' : 'I have verified the previous sandbox no longer exists.'}</label>
        {mode !== 'reconnect' && <label><input type="checkbox" checked={dataLoss} onChange={e => setDataLoss(e.target.checked)} /> I accept losing file changes outside the retained archive.</label>}
        <button disabled={busy || !sideEffects || !providerChecked || mode !== 'reconnect' && !dataLoss} onClick={() => void submit(true)}>Recover workspace</button>
        <p>Workspace recovery does not retry the tool. Refresh its generations before submitting a separate tool retry.</p>
      </div>}
    </div>}
    {command && <p>Recovery command: {command.command_status} {command.failure_code}
      <button disabled={busy} onClick={() => {
        setBusy(true); getAgentCommand(sessionId, command.command_id, controller.current.signal)
          .then(value => { if (!controller.current.signal.aborted) setCommand(value); })
          .catch(e => { if (!controller.current.signal.aborted) setError(e.message); })
          .finally(() => { if (!controller.current.signal.aborted) setBusy(false); });
      }}>Check command status</button>
    </p>}
    {error && <p role="alert">{error}</p>}
  </article>;
}
