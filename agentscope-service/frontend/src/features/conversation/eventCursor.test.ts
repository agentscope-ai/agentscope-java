/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { describe, expect, it } from 'vitest';
import { mergeContiguousEvents } from './eventCursor';

interface Event {
  seq: number;
  value: string;
}

describe('mergeContiguousEvents', () => {
  it('retains an out-of-order event until the missing sequence arrives', () => {
    const pending = new Map<number, Event>();

    const first = mergeContiguousEvents(
      2,
      pending,
      [{ seq: 4, value: 'four' }],
      event => event.seq,
    );
    expect(first).toEqual({ cursor: 2, accepted: [] });
    expect([...pending.keys()]).toEqual([4]);

    const repaired = mergeContiguousEvents(
      first.cursor,
      pending,
      [{ seq: 3, value: 'three' }],
      event => event.seq,
    );
    expect(repaired.cursor).toBe(4);
    expect(repaired.accepted.map(event => event.value)).toEqual(['three', 'four']);
    expect(pending.size).toBe(0);
  });

  it('ignores duplicates and releases a sorted contiguous prefix', () => {
    const pending = new Map<number, Event>();
    const result = mergeContiguousEvents(
      7,
      pending,
      [
        { seq: 9, value: 'nine' },
        { seq: 8, value: 'eight' },
        { seq: 8, value: 'duplicate' },
        { seq: 7, value: 'old' },
      ],
      event => event.seq,
    );

    expect(result.cursor).toBe(9);
    expect(result.accepted.map(event => event.value)).toEqual(['eight', 'nine']);
    expect(pending.size).toBe(0);
  });
});
