// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
// Test-only copy of the Session API v1 reducer, initialized from Console on 2026-10-12.
import type { AgentResourcePage, AgentSessionEvent, AgentSessionSnapshot } from './types';

type Data = Record<string, unknown>;
const record = (value: unknown): Data => value && typeof value === 'object' && !Array.isArray(value) ? value as Data : {};
const blocks = (value: unknown): Data[] => Array.isArray(value) ? value.map(record) : [];

export class AgentViewLimitError extends Error {
  constructor() { super('Display limit reached. Refresh to continue from a new snapshot; execution continues on the server.'); }
}

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
  private steps = new Map<string, AgentSessionEvent>();
  private inputs = new Map<string, AgentSessionEvent>();
  private subagents = new Map<string, AgentSessionEvent>();
  private artifacts = new Map<string, AgentSessionEvent>();
  private modelUsage = new Map<string, Data>();
  private commands = new Map<string, AgentSessionEvent>();
  private currentTools = new Map<string, string>();
  private authoritativeUsage?: Data;
  private liveEvents: AgentSessionEvent[] = [];
  private partialItems = new Set<string>();
  private partialTools = new Set<string>();
  private displayCharacters = 0;
  private reserve(value: unknown): void {
    const size = JSON.stringify(value).length;
    if (this.displayCharacters + size > 16 * 1024 * 1024) throw new AgentViewLimitError();
    this.displayCharacters += size;
  }
  private checkRecords(): void {
    const indexes = [this.items, this.tools, this.actions, this.actionHistory, this.turns, this.runs,
      this.steps, this.inputs, this.subagents, this.artifacts, this.modelUsage, this.commands];
    if (indexes.reduce((count, index) => count + index.size, 0) > 8192) throw new AgentViewLimitError();
  }

  constructor(snapshot: AgentSessionSnapshot) {
    this.reserve(snapshot);
    this.view = structuredClone(snapshot);
    if (snapshot.usage?.authoritative) this.authoritativeUsage = structuredClone(snapshot.usage);
    for (const event of snapshot.items) {
      this.items.set(String(event.data.item_id), structuredClone(event));
      if (event.data.active_tool_call_id) this.currentTools.set(String(event.data.item_id), String(event.data.active_tool_call_id));
    }
    for (const event of snapshot.tools ?? []) {
      this.tools.set(this.toolKey(event.data), structuredClone(event));
      if (event.data.item_id && event.data.status === 'generating_arguments')
        this.currentTools.set(String(event.data.item_id), String(event.data.tool_call_id));
    }
    for (const event of snapshot.required_actions) {
      this.actions.set(String(event.data.request_id), structuredClone(event));
      this.actionHistory.set(String(event.data.request_id), structuredClone(event));
    }
    for (const event of snapshot.turns) this.turns.set(String(event.data.turn_id), structuredClone(event));
    for (const event of snapshot.runs) this.runs.set(String(event.data.run_id), structuredClone(event));
    for (const event of snapshot.steps ?? []) this.steps.set(String(event.data.step_id), structuredClone(event));
    for (const event of snapshot.inputs ?? []) this.inputs.set(String(event.data.input_id), structuredClone(event));
    for (const event of snapshot.subagents ?? []) this.subagents.set(String(event.data.childSessionId), structuredClone(event));
    for (const event of snapshot.artifacts ?? []) this.artifacts.set(String(event.data.artifact_id), structuredClone(event));
    for (const event of snapshot.action_commands ?? []) this.commands.set(String(event.data.command_id), structuredClone(event));
    for (const usage of blocks(record(snapshot.usage).models)) this.modelUsage.set(String(usage.model_call_id ?? usage.attempt_id), usage);
    this.checkRecords();
  }
  private toolKey(data: Data) { return `${data.turn_id ?? 'null'}:${data.tool_call_id}`; }
  mergeResourcePage(kind: string, page: AgentResourcePage): void {
    const reference = this.view.resource_pages?.[kind];
    if (!reference || reference.snapshot_id !== page.snapshot_id || reference.as_of !== page.as_of)
      throw new Error('Resource snapshot changed; refresh before loading another page.');
    const indexes: Record<string, [Map<string, AgentSessionEvent>, string]> = {
      items: [this.items, 'item_id'], tools: [this.tools, 'tool_call_id'], steps: [this.steps, 'step_id'],
      turns: [this.turns, 'turn_id'], runs: [this.runs, 'run_id'], required_actions: [this.actions, 'request_id'],
      inputs: [this.inputs, 'input_id'], subagents: [this.subagents, 'childSessionId'],
      artifacts: [this.artifacts, 'artifact_id'], action_commands: [this.commands, 'command_id'],
    };
    const selected = indexes[kind];
    if (!selected) throw new Error('Unsupported Session resource.');
    this.reserve(page);
    // Reconcile the old page with every committed event since its snapshot. This also rebuilds
    // a message prefix when its first live delta arrived before its older page was loaded.
    const baseline: AgentSessionSnapshot = { session: this.view.session, as_of: reference.as_of,
      items: [], tools: [], turns: [], runs: [], steps: [], required_actions: [], inputs: [],
      subagents: [], artifacts: [], action_commands: [], usage: { authoritative: true } };
    (baseline as unknown as Record<string, unknown>)[kind] = page.data;
    const replay = new AgentSessionView(baseline);
    for (const event of this.liveEvents) replay.reduce(event);
    const reconciled: Record<string, Map<string, AgentSessionEvent>> = {
      items: replay.items, tools: replay.tools, turns: replay.turns, runs: replay.runs, steps: replay.steps,
      required_actions: replay.actions, inputs: replay.inputs, subagents: replay.subagents,
      artifacts: replay.artifacts, action_commands: replay.commands,
    };
    for (const event of page.data) {
      const key = kind === 'tools' ? this.toolKey(event.data) : String(event.data[selected[1]]);
      if (selected[0].has(key) && !(kind === 'items' && this.partialItems.has(key))
          && !(kind === 'tools' && this.partialTools.has(key))) continue;
      if (kind === 'required_actions' && this.actionHistory.has(key)) continue;
      const next = reconciled[kind].get(key);
      // Resolved actions and deleted artifacts are absent after replay.
      if (!next) continue;
      selected[0].set(key, structuredClone(next));
      if (kind === 'tools') this.partialTools.delete(key);
      if (kind === 'required_actions') this.actionHistory.set(key, structuredClone(next));
      if (kind === 'items') {
        this.partialItems.delete(key);
        if (next.data.active_tool_call_id) this.currentTools.set(key, String(next.data.active_tool_call_id));
      }
    }
    this.checkRecords();
    this.view.resource_pages = { ...this.view.resource_pages, [kind]: structuredClone(page) };
  }
  private tool(event: AgentSessionEvent, block: Data, status: string) {
    const id = block.id ?? block.tool_call_id;
    if (id == null) return;
    const key = this.toolKey({ ...event.data, tool_call_id: id });
    if (!this.tools.has(key) && ['item.delta', 'tool.delta', 'tool.dispatched'].includes(event.type)) this.partialTools.add(key);
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
    if (this.liveEvents.length >= 4096) throw new AgentViewLimitError();
    this.reserve(event);
    this.reduce(event);
    this.liveEvents.push(structuredClone(event));
    this.checkRecords();
  }
  private reduce(event: AgentSessionEvent): void {
    if (event.ephemeral || event.id && this.seen.has(event.id)) return;
    const data = event.data;
    if (['item.started', 'item.completed', 'item.finalized'].includes(event.type)) {
      this.partialItems.delete(String(data.item_id));
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
      if (!previous) this.partialItems.add(id);
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
      const state = event.type === 'tool.requested' ? 'requested' : event.type === 'tool.waiting' ? 'waiting' : event.type === 'tool.completed' ? String(data.status ?? 'completed') : 'running';
      this.tool(event, { ...data, id: data.tool_call_id, ...record(data.result) }, state);
    } else if (event.type === 'step.updated') {
      this.steps.set(String(data.step_id), structuredClone(event));
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
    if (['usage.recorded', 'model.completed'].includes(event.type)) {
      if (data.session_usage) this.authoritativeUsage = { ...structuredClone(record(data.session_usage)), authoritative: true };
      if (!this.authoritativeUsage) this.modelUsage.set(String(data.model_call_id ?? data.attempt_id ?? event.id), data);
    }
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
    const scope = this.view.usage?.scope === 'session_subtree' || [...this.modelUsage.values()].some(call => call.childSessionId != null) ? 'session_subtree' : 'session_only';
    return { ...this.view, usage: this.authoritativeUsage ?? { scope, model_calls: this.modelUsage.size, totals, models: [...this.modelUsage.values()] },
      subagents: [...this.subagents.values()], artifacts: [...this.artifacts.values()], action_commands: [...this.commands.values()], items: [...this.items.values()].sort((a, b) => new Date(a.created_at ?? 0).getTime() - new Date(b.created_at ?? 0).getTime()), tools: [...this.tools.values()], required_actions: [...this.actions.values()],
      turns: [...this.turns.values()], runs: [...this.runs.values()], steps: [...this.steps.values()], inputs: [...this.inputs.values()] };
  }
}
