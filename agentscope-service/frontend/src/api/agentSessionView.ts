import type { AgentSessionEvent, AgentSessionSnapshot } from './agentSessions';

type Data = Record<string, unknown>;
const record = (value: unknown): Data => value && typeof value === 'object' && !Array.isArray(value) ? value as Data : {};
const blocks = (value: unknown): Data[] => Array.isArray(value) ? value.map(record) : [];

/** Applies complete records and committed deltas identically before and after a reconnect. */
export class AgentSessionView {
  private view: AgentSessionSnapshot;
  private seen = new Set<string>();
  private items = new Map<string, AgentSessionEvent>();
  private tools = new Map<string, AgentSessionEvent>();
  private actions = new Map<string, AgentSessionEvent>();
  private actionHistory = new Map<string, AgentSessionEvent>();
  private turns = new Map<string, AgentSessionEvent>();
  private runs = new Map<string, AgentSessionEvent>();
  private inputs = new Map<string, AgentSessionEvent>();
  private subagents = new Map<string, AgentSessionEvent>();
  private artifacts = new Map<string, AgentSessionEvent>();
  private modelUsage = new Map<string, Data>();
  private commands = new Map<string, AgentSessionEvent>();
  private currentTools = new Map<string, string>();

  constructor(snapshot: AgentSessionSnapshot) {
    this.view = structuredClone(snapshot);
    for (const event of snapshot.items) {
      this.items.set(String(event.data.item_id), structuredClone(event));
      if (event.data.active_tool_call_id) this.currentTools.set(String(event.data.item_id), String(event.data.active_tool_call_id));
    }
    for (const event of snapshot.tools ?? []) this.tools.set(this.toolKey(event.data), structuredClone(event));
    for (const event of snapshot.required_actions) {
      this.actions.set(String(event.data.request_id), structuredClone(event));
      this.actionHistory.set(String(event.data.request_id), structuredClone(event));
    }
    for (const event of snapshot.turns) this.turns.set(String(event.data.turn_id), structuredClone(event));
    for (const event of snapshot.runs) this.runs.set(String(event.data.run_id), structuredClone(event));
    for (const event of snapshot.inputs ?? []) this.inputs.set(String(event.data.input_id), structuredClone(event));
    for (const event of snapshot.subagents ?? []) this.subagents.set(String(event.data.childSessionId), structuredClone(event));
    for (const event of snapshot.artifacts ?? []) this.artifacts.set(String(event.data.artifact_id), structuredClone(event));
    for (const event of snapshot.action_commands ?? []) this.commands.set(String(event.data.command_id), structuredClone(event));
    for (const usage of blocks(record(snapshot.usage).models)) this.modelUsage.set(String(usage.model_call_id ?? usage.attempt_id), usage);
  }
  private toolKey(data: Data) { return `${data.turn_id ?? 'null'}:${data.tool_call_id}`; }
  private tool(event: AgentSessionEvent, block: Data, status: string) {
    const id = block.id ?? block.tool_call_id;
    if (id == null) return;
    const key = this.toolKey({ ...event.data, tool_call_id: id });
    const data = { ...this.tools.get(key)?.data };
    for (const field of ['turn_id', 'run_id', 'item_id', 'model_call_id']) if (event.data[field] != null) data[field] = event.data[field];
    data.tool_call_id = id;
    if (typeof block.name === 'string' && block.name && !block.name.startsWith('__')) data.name = block.name;
    if (Object.keys(record(block.input)).length) data.input = { ...record(data.input), ...record(block.input) };
    if (typeof block.content === 'string') data.arguments = event.type === 'item.delta' ? String(data.arguments ?? '') + block.content : block.content;
    if (Array.isArray(block.output)) {
      if (event.type === 'tool.delta') data.progress = [...blocks(data.progress), ...blocks(block.output)];
      else data.output = block.output;
    }
    const next = String((block.type === 'tool_result' || event.type === 'tool.completed') ? block.state ?? status : status).toLowerCase();
    if (!(['completed', 'success', 'error', 'denied', 'interrupted', 'failed'].includes(String(data.status)) && ['requested', 'generating_arguments'].includes(next))) data.status = next;
    this.tools.set(key, { ...event, type: 'tool.updated', data });
  }
  apply(event: AgentSessionEvent): void {
    if (event.ephemeral || event.id && this.seen.has(event.id)) return;
    const data = event.data;
    if (['item.started', 'item.completed'].includes(event.type)) {
      this.items.set(String(data.item_id), structuredClone(event));
      for (const block of blocks(record(data.item).content)) {
        if (block.type === 'tool_use') this.tool(event, block, 'requested');
        if (block.type === 'tool_result') this.tool(event, block, 'completed');
      }
    } else if (event.type === 'session.context_initialized') {
      for (const item of blocks(data.items)) {
        this.items.set(String(item.id), { ...event, type: 'item.updated', data: { item_id: item.id, item } });
        for (const block of blocks(item.content)) {
          if (block.type === 'tool_use') this.tool(event, block, 'requested');
          if (block.type === 'tool_result') this.tool(event, block, 'completed');
        }
      }
    } else if (event.type === 'item.delta') {
      const id = String(data.item_id);
      const previous = this.items.get(id);
      const item = { ...record(previous?.data.item) };
      if (!['completed', 'incomplete'].includes(String(item.status))) {
        const content = structuredClone(blocks(item.content));
        for (const block of blocks(data.content)) {
          if (block.type === 'text') {
            const tail = content[content.length - 1];
            if (tail?.type === 'text') tail.text = String(tail.text ?? '') + String(block.text ?? '');
            else content.push({ ...block });
          } else if (block.type === 'tool_use') {
            const call = { ...block };
            if (call.id) this.currentTools.set(id, String(call.id));
            else call.id = this.currentTools.get(id);
            this.tool(event, call, 'generating_arguments');
          } else content.push(block);
        }
        Object.assign(item, { id, type: 'message', role: 'assistant', content, status: 'in_progress' });
        const details: Data = { ...data, item, active_tool_call_id: this.currentTools.get(id) };
        delete details.content;
        this.items.set(id, { ...event, type: 'item.updated', data: details });
      }
    } else if (event.type.startsWith('tool.')) {
      const state = event.type === 'tool.requested' ? 'requested' : event.type === 'tool.completed' ? String(data.status ?? 'completed') : 'running';
      this.tool(event, { ...data, id: data.tool_call_id, ...record(data.result) }, state);
    } else if (event.type.startsWith('run.')) {
      this.runs.set(String(data.run_id), event);
      if (event.type === 'run.ended') {
        for (const [id, old] of this.items) {
          const item = record(old.data.item);
          if (old.data.run_id === data.run_id && item.status === 'in_progress') this.items.set(id, {
            ...event, type: 'item.updated', data: { ...old.data, item: { ...item, status: data.status === 'completed' ? 'completed' : 'incomplete' } },
          });
        }
        for (const [id, old] of this.tools) if (old.data.run_id === data.run_id && ['running', 'requested', 'generating_arguments'].includes(String(old.data.status)))
          this.tools.set(id, { ...old, data: { ...old.data, status: 'unknown' } });
      }
    } else if (event.type.startsWith('turn.') && event.type !== 'turn.ended') {
      this.turns.set(String(data.turn_id), event);
    } else if (event.type === 'required_action.created') {
      this.actions.set(String(data.request_id), event); this.actionHistory.set(String(data.request_id), event);
      if (data.tool_call) this.tool(event, record(data.tool_call), 'suspended');
    } else if (['required_action.accepted', 'required_action.resolved'].includes(event.type)) {
      this.actions.delete(String(data.request_id));
      if (event.type === 'required_action.resolved') this.actionHistory.set(String(data.request_id), event);
    } else if (event.type === 'required_action.rejected') {
      if (data.pending !== false && this.actionHistory.get(String(data.request_id))?.type !== 'required_action.resolved')
        this.actions.set(String(data.request_id), { ...event, data: { ...this.actionHistory.get(String(data.request_id))?.data, ...data } });
    } else if (event.type.startsWith('input.')) {
      const ids = Array.isArray(data.input_ids) ? [...data.input_ids] : [];
      if (data.input_id) ids.push(data.input_id);
      for (const id of ids) {
        const old = this.inputs.get(String(id));
        if (event.type === 'input.accepted' && old && ['input.applied', 'input.rejected'].includes(old.type)) continue;
        this.inputs.set(String(id), { ...event, data: { ...old?.data, ...data, input_id: id } });
      }
    }
    if (event.type.startsWith('required_action.') && data.command_id) this.commands.set(String(data.command_id), event);
    if (event.type.startsWith('subagent.')) this.subagents.set(String(data.childSessionId), event);
    if (event.type === 'artifact.published') this.artifacts.set(String(data.artifact_id), event);
    if (event.type === 'artifact.deleted') this.artifacts.delete(String(data.artifact_id));
    if (['usage.recorded', 'model.completed'].includes(event.type)) this.modelUsage.set(String(data.model_call_id ?? data.attempt_id ?? event.id), data);
    if (['usage.recorded', 'model.completed'].includes(event.type) && ['failed', 'cancelled', 'interrupted'].includes(String(data.status)) && data.item_id) {
      const id = String(data.item_id), old = this.items.get(id);
      if (old && record(old.data.item).status === 'in_progress') this.items.set(id, { ...event, type: 'item.updated', data: { ...old.data, item: { ...record(old.data.item), status: 'incomplete' } } });
      for (const [key, tool] of this.tools) if (tool.data.item_id === id && tool.data.status === 'generating_arguments') this.tools.set(key, { ...tool, data: { ...tool.data, status: 'unknown' } });
    }
    if (event.id) { this.seen.add(event.id); if (this.seen.size > 4096) this.seen.delete(this.seen.values().next().value!); }
    if (event.cursor) this.view.as_of = event.cursor;
  }
  snapshot(): AgentSessionSnapshot {
    const totals: Record<string, number> = {};
    for (const call of this.modelUsage.values()) for (const [key, value] of Object.entries(record(call.usage)))
      if (typeof value === 'number') totals[key] = (totals[key] ?? 0) + value;
    return { ...this.view, usage: { scope: 'session_only', model_calls: this.modelUsage.size, totals, models: [...this.modelUsage.values()] },
      subagents: [...this.subagents.values()], artifacts: [...this.artifacts.values()], action_commands: [...this.commands.values()], items: [...this.items.values()], tools: [...this.tools.values()], required_actions: [...this.actions.values()],
      turns: [...this.turns.values()], runs: [...this.runs.values()], inputs: [...this.inputs.values()] };
  }
}
