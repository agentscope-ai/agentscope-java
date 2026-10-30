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

import { useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { Check, ChevronDown } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import { filterNamedResourceOptions, type NamedResourceOption } from './namedResourceOptions';

type NamedResourcePickerProps = {
  value: string;
  onChange: (id: string) => void;
  options: NamedResourceOption[];
  resourceLabel: string;
  loading?: boolean;
  error?: boolean;
  emptyLabel?: string;
  className?: string;
  'aria-label'?: string;
};

/** Selects a stable resource ID while displaying and searching human-readable names. */
export function NamedResourcePicker({
  value,
  onChange,
  options,
  resourceLabel,
  loading = false,
  error = false,
  emptyLabel = `Select ${resourceLabel}…`,
  className,
  'aria-label': ariaLabel = resourceLabel,
}: NamedResourcePickerProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const listboxId = useId();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [highlighted, setHighlighted] = useState(0);
  const selected = options.find(option => option.id === value);
  const selectedLabel = selected?.label || value;
  const search = open && query === selectedLabel ? '' : query;
  const visibleOptions = useMemo(
    () => filterNamedResourceOptions(options, search),
    [options, search],
  );
  const inputValue = open ? query : selectedLabel;

  const choose = (option: NamedResourceOption) => {
    onChange(option.id);
    setQuery(option.label);
    setOpen(false);
    setHighlighted(0);
  };
  const openPicker = () => {
    if (loading) return;
    setQuery(selectedLabel);
    setOpen(true);
    setHighlighted(0);
  };
  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      if (!open) openPicker();
      else setHighlighted(current => Math.min(current + 1, Math.max(0, visibleOptions.length - 1)));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setHighlighted(current => Math.max(0, current - 1));
    } else if (event.key === 'Enter' && open && visibleOptions[highlighted]) {
      event.preventDefault();
      choose(visibleOptions[highlighted]);
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
          placeholder={loading ? `Loading ${resourceLabel}s…` : emptyLabel}
          disabled={loading}
          aria-label={ariaLabel}
          aria-expanded={open}
          aria-controls={listboxId}
          aria-autocomplete="list"
          className="pr-10"
        />
        <button
          type="button"
          tabIndex={-1}
          className="absolute inset-y-0 right-0 flex w-10 items-center justify-center text-muted-foreground"
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => open ? setOpen(false) : openPicker()}
          disabled={loading}
          aria-label={`Open ${ariaLabel} options`}
        >
          <ChevronDown className={cn('h-4 w-4 transition-transform', open && 'rotate-180')} />
        </button>
      </div>
      {open && (
        <div
          id={listboxId}
          role="listbox"
          className="absolute z-50 mt-1 max-h-64 w-full min-w-64 overflow-auto rounded-lg border bg-popover p-1 text-popover-foreground shadow-lg"
        >
          {error && <div className="px-3 py-2 text-sm text-destructive">Failed to load {resourceLabel}s.</div>}
          {!loading && !error && visibleOptions.length === 0 && (
            <div className="px-3 py-2 text-sm text-muted-foreground">No matching {resourceLabel}.</div>
          )}
          {visibleOptions.map((option, index) => (
            <button
              key={option.id}
              type="button"
              role="option"
              aria-selected={option.id === value}
              className={cn(
                'flex w-full items-center gap-2 rounded-md px-3 py-2 text-left hover:bg-accent',
                index === highlighted && 'bg-accent',
              )}
              onMouseEnter={() => setHighlighted(index)}
              onClick={() => choose(option)}
            >
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium">{option.label}</span>
                <span className="block truncate text-xs text-muted-foreground">{option.secondary || option.id}</span>
              </span>
              {option.id === value && <Check className="h-4 w-4 shrink-0 text-primary" />}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
