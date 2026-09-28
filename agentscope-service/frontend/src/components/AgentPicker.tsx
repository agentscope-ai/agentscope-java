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

import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Check, ChevronDown, X } from 'lucide-react';
import { listAgents, listCatalogBindings, type AgentDefinition } from '@/api/agents';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';

export function useCatalogAgents() {
  const scope = useControlPlaneScope();
  return useQuery({
    queryKey: ['catalog-agent-options', scope.tenant, scope.namespace],
    queryFn: () => listAgents(scope.tenant, scope.namespace),
    staleTime: 10_000,
  });
}

export function agentDisplayName(agent: AgentDefinition | undefined, fallback = '') {
  return agent?.name || agent?.agentKey || fallback;
}

export function filterAgentOptions(
  agents: AgentDefinition[],
  search: string,
  includeInactive: boolean,
  selectedId: string,
  excludedIds: ReadonlySet<string>,
) {
  const needle = search.trim().toLowerCase();
  return agents
    .filter((agent) => !excludedIds.has(agent.id))
    .filter((agent) => includeInactive || agent.status === 'active' || agent.id === selectedId)
    .filter((agent) => !needle || [agent.name, agent.agentKey, agent.id, agent.runtimeKind, agent.status]
      .filter(Boolean).join(' ').toLowerCase().includes(needle))
    .sort((a, b) => agentDisplayName(a).localeCompare(agentDisplayName(b)));
}

export function AgentIdentity({ agentId, showId = true }: { agentId: string; showId?: boolean }) {
  const agents = useCatalogAgents();
  const agent = agents.data?.find((item) => item.id === agentId);
  const name = agentDisplayName(agent, agentId || 'Unknown Agent');
  return (
    <span title={agentId}>
      {name}
      {showId && agent && <span className="ml-1 font-mono text-xs text-muted-foreground">{agentId.slice(0, 8)}</span>}
    </span>
  );
}

type AgentPickerProps = {
  value: string;
  onChange: (agentId: string) => void;
  disabled?: boolean;
  required?: boolean;
  includeInactive?: boolean;
  excludeIds?: string[];
  className?: string;
  searchPlaceholder?: string;
  emptyLabel?: string;
  'aria-label'?: string;
};

/**
 * Selects a stable Agent ID from the scoped Catalog. One combobox both shows
 * the current selection and filters registered resources; arbitrary IDs can
 * never be submitted.
 */
export function AgentPicker({
  value,
  onChange,
  disabled,
  required,
  includeInactive = false,
  excludeIds = [],
  className,
  searchPlaceholder = 'Search registered Agents…',
  emptyLabel = 'Select Agent…',
  'aria-label': ariaLabel = 'Agent',
}: AgentPickerProps) {
  const agents = useCatalogAgents();
  const inputRef = useRef<HTMLInputElement>(null);
  const activeOptionRef = useRef<HTMLButtonElement>(null);
  const listboxId = useId();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [highlighted, setHighlighted] = useState(0);
  const excluded = useMemo(() => new Set(excludeIds), [excludeIds]);
  const selected = agents.data?.find((agent) => agent.id === value);
  const selectedLabel = selected ? agentDisplayName(selected) : value;
  const search = open && query === selectedLabel ? '' : query;
  const options = useMemo(
    () => filterAgentOptions(agents.data ?? [], search, includeInactive, value, excluded),
    [agents.data, excluded, includeInactive, search, value],
  );
  const inputValue = open ? query : selectedLabel;

  useEffect(() => {
    if (open) activeOptionRef.current?.scrollIntoView({ block: 'nearest' });
  }, [open, highlighted]);

  useEffect(() => {
    inputRef.current?.setCustomValidity(required && !value ? 'Select a registered Agent.' : '');
  }, [required, value]);

  const choose = (agent: AgentDefinition) => {
    onChange(agent.id);
    setQuery(agentDisplayName(agent));
    setOpen(false);
    setHighlighted(0);
  };
  const openPicker = () => {
    if (disabled || agents.isLoading) return;
    setQuery(selectedLabel);
    setOpen(true);
    setHighlighted(0);
  };
  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      if (!open) openPicker();
      else setHighlighted((current) => Math.min(current + 1, Math.max(0, options.length - 1)));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setHighlighted((current) => Math.max(0, current - 1));
    } else if (event.key === 'Enter' && open && options[highlighted]) {
      event.preventDefault();
      choose(options[highlighted]);
    } else if (event.key === 'Escape') {
      setOpen(false);
      setQuery(selectedLabel);
    }
  };

  return (
    <div
      className={cn('relative min-w-0', className)}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) {
          setOpen(false);
          setQuery(selectedLabel);
        }
      }}
    >
      <div className="relative">
        <Input
          ref={inputRef}
          type="text"
          role="combobox"
          value={inputValue}
          onFocus={(event) => {
            openPicker();
            event.currentTarget.select();
          }}
          onChange={(event) => {
            if (value) onChange('');
            setQuery(event.target.value);
            setOpen(true);
            setHighlighted(0);
          }}
          onKeyDown={onKeyDown}
          placeholder={agents.isLoading ? 'Loading Agents…' : emptyLabel || searchPlaceholder}
          disabled={disabled || agents.isLoading}
          required={required}
          aria-label={ariaLabel}
          aria-expanded={open}
          aria-controls={listboxId}
          aria-activedescendant={open && options[highlighted] ? `${listboxId}-option-${highlighted}` : undefined}
          aria-autocomplete="list"
          className="pr-10"
        />
        <button
          type="button"
          tabIndex={-1}
          className="absolute inset-y-0 right-0 flex w-10 items-center justify-center text-muted-foreground"
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => open ? setOpen(false) : openPicker()}
          disabled={disabled || agents.isLoading}
          aria-label={`Open ${ariaLabel} options`}
        >
          <ChevronDown className={cn('h-4 w-4 transition-transform', open && 'rotate-180')} />
        </button>
      </div>
      {open && (
        <div
          id={listboxId}
          role="listbox"
          className="absolute z-50 mt-1 max-h-64 w-full min-w-0 overflow-auto rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-lg"
        >
          {agents.isError && <div className="px-3 py-2 text-sm text-destructive">Failed to load registered Agents.</div>}
          {!agents.isLoading && !agents.isError && options.length === 0 && (
            <div className="px-3 py-2 text-sm text-muted-foreground">No matching active Agent.</div>
          )}
          {options.map((agent, index) => (
            <button
              key={agent.id}
              id={`${listboxId}-option-${index}`}
              ref={index === highlighted ? activeOptionRef : undefined}
              type="button"
              role="option"
              aria-selected={agent.id === value}
              className={cn(
                'flex w-full items-center gap-2 rounded-md px-3 py-2 text-left hover:bg-accent',
                index === highlighted && 'bg-accent',
              )}
              onMouseEnter={() => setHighlighted(index)}
              onClick={() => choose(agent)}
            >
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium">{agentDisplayName(agent)}</span>
                <span className="block truncate text-xs text-muted-foreground">
                  {agent.agentKey || agent.id.slice(0, 8)}
                  {agent.runtimeKind ? ` · ${agent.runtimeKind}` : ''}
                  {agent.status && agent.status !== 'active' ? ` · ${agent.status}` : ''}
                </span>
              </span>
              {agent.id === value && <Check className="h-4 w-4 shrink-0 text-primary" />}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

type AgentMultiPickerProps = {
  value: string[];
  onChange: (agentIds: string[]) => void;
  disabled?: boolean;
  excludeIds?: string[];
  className?: string;
  searchPlaceholder?: string;
  'aria-label'?: string;
};

/**
 * Selects several registered Agents in one compact combobox. Selected Agents
 * stay visible as removable chips, while the search only offers the remaining
 * active Agents.
 */
export function AgentMultiPicker({
  value,
  onChange,
  disabled,
  excludeIds = [],
  className,
  searchPlaceholder = 'Search and add Agents…',
  'aria-label': ariaLabel = 'Agents',
}: AgentMultiPickerProps) {
  const agents = useCatalogAgents();
  const inputRef = useRef<HTMLInputElement>(null);
  const listboxId = useId();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [highlighted, setHighlighted] = useState(0);
  const excluded = useMemo(() => new Set([...excludeIds, ...value]), [excludeIds, value]);
  const selected = value.map(agentId => ({
    id: agentId,
    label: agentDisplayName(agents.data?.find(agent => agent.id === agentId), agentId),
  }));
  const options = useMemo(
    () => filterAgentOptions(agents.data ?? [], query, false, '', excluded),
    [agents.data, excluded, query],
  );

  const choose = (agent: AgentDefinition) => {
    onChange([...value, agent.id]);
    setQuery('');
    setHighlighted(0);
    setOpen(true);
    inputRef.current?.focus();
  };
  const remove = (agentId: string) => onChange(value.filter(item => item !== agentId));
  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setOpen(true);
      setHighlighted(current => Math.min(current + 1, Math.max(0, options.length - 1)));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setHighlighted(current => Math.max(0, current - 1));
    } else if (event.key === 'Enter' && open && options[highlighted]) {
      event.preventDefault();
      choose(options[highlighted]);
    } else if (event.key === 'Backspace' && !query && value.length) {
      remove(value[value.length - 1]);
    } else if (event.key === 'Escape') {
      setOpen(false);
    }
  };

  return (
    <div
      className={cn('relative min-w-0', className)}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
      }}
    >
      <div
        className={cn(
          'flex min-h-11 flex-wrap items-center gap-2 rounded-lg border bg-background px-3 py-2 text-sm shadow-sm focus-within:ring-2 focus-within:ring-ring',
          disabled && 'cursor-not-allowed opacity-50',
        )}
        onClick={() => inputRef.current?.focus()}
      >
        {selected.map(agent => (
          <span key={agent.id} className="inline-flex max-w-full items-center gap-1 rounded-full border bg-muted px-2.5 py-1 text-xs font-medium">
            <span className="truncate">{agent.label}</span>
            <button
              type="button"
              className="rounded-full text-muted-foreground hover:text-foreground"
              onClick={(event) => { event.stopPropagation(); remove(agent.id); }}
              disabled={disabled}
              aria-label={`Remove ${agent.label}`}
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </span>
        ))}
        <input
          ref={inputRef}
          type="text"
          role="combobox"
          value={query}
          onFocus={() => setOpen(true)}
          onChange={(event) => { setQuery(event.target.value); setOpen(true); setHighlighted(0); }}
          onKeyDown={onKeyDown}
          placeholder={value.length ? 'Add another Agent…' : agents.isLoading ? 'Loading Agents…' : searchPlaceholder}
          disabled={disabled || agents.isLoading}
          aria-label={ariaLabel}
          aria-expanded={open}
          aria-controls={listboxId}
          aria-autocomplete="list"
          className="min-w-44 flex-1 border-0 bg-transparent py-1 outline-none placeholder:text-muted-foreground"
        />
        <ChevronDown className={cn('ml-auto h-4 w-4 shrink-0 text-muted-foreground transition-transform', open && 'rotate-180')} />
      </div>
      {open && (
        <div id={listboxId} role="listbox" aria-multiselectable="true" className="absolute z-50 mt-1 max-h-64 w-full min-w-64 overflow-auto rounded-lg border bg-popover p-1 text-popover-foreground shadow-lg">
          {agents.isError && <div className="px-3 py-2 text-sm text-destructive">Failed to load registered Agents.</div>}
          {!agents.isLoading && !agents.isError && options.length === 0 && (
            <div className="px-3 py-2 text-sm text-muted-foreground">{query ? 'No matching active Agent.' : 'All available Agents are selected.'}</div>
          )}
          {options.map((agent, index) => (
            <button
              key={agent.id}
              type="button"
              role="option"
              aria-selected="false"
              className={cn('flex w-full items-center gap-2 rounded-md px-3 py-2 text-left hover:bg-accent', index === highlighted && 'bg-accent')}
              onMouseEnter={() => setHighlighted(index)}
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => choose(agent)}
            >
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium">{agentDisplayName(agent)}</span>
                <span className="block truncate text-xs text-muted-foreground">{agent.agentKey || agent.id.slice(0, 8)}{agent.runtimeKind ? ` · ${agent.runtimeKind}` : ''}</span>
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

export function AgentBindingPicker({
  agentId,
  kind,
  value,
  onChange,
  required,
}: {
  agentId: string;
  kind?: string;
  value: string;
  onChange: (bindingId: string) => void;
  required?: boolean;
}) {
  const bindings = useQuery({
    queryKey: ['catalog-agent-bindings', agentId],
    queryFn: () => listCatalogBindings(agentId),
    enabled: !!agentId,
  });
  const options = (bindings.data ?? []).filter((binding) => !kind || binding.kind === kind);
  return (
    <div className="grid gap-1">
      <select
        className="h-10 rounded-lg border bg-background px-3 text-sm"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        disabled={!agentId || bindings.isLoading}
        required={required}
        aria-label="Runtime Binding"
      >
        <option value="">{!agentId ? 'Select Agent first…' : bindings.isLoading ? 'Loading Bindings…' : 'Select Runtime Binding…'}</option>
        {options.map((binding) => (
          <option key={binding.id} value={binding.id}>
            {binding.kind} · priority {binding.priority} · {binding.enabled ? 'enabled' : 'disabled'} · {binding.id.slice(0, 8)}
          </option>
        ))}
      </select>
      {!bindings.isLoading && agentId && options.length === 0 && <p className="text-xs text-muted-foreground">No matching Binding for this Agent.</p>}
    </div>
  );
}
