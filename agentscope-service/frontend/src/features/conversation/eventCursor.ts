/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

export interface ContiguousMergeResult<T> {
  cursor: number;
  accepted: T[];
}

/**
 * Adds durable events to a pending set and releases only the contiguous prefix.
 *
 * The pending map intentionally survives reconnects. If sequence 12 arrives before
 * sequence 11, callers retain 12, repair history from 10, and then release 11 and
 * 12 together. This prevents the UI resume cursor from permanently jumping over a
 * transaction that was not visible to the first database read.
 */
export function mergeContiguousEvents<T>(
  cursor: number,
  pending: Map<number, T>,
  incoming: Iterable<T>,
  sequenceOf: (event: T) => number,
): ContiguousMergeResult<T> {
  for (const event of incoming) {
    const sequence = sequenceOf(event);
    if (sequence > cursor && !pending.has(sequence)) pending.set(sequence, event);
  }

  const accepted: T[] = [];
  let next = cursor + 1;
  while (pending.has(next)) {
    const event = pending.get(next);
    pending.delete(next);
    if (event != null) accepted.push(event);
    cursor = next;
    next += 1;
  }
  return { cursor, accepted };
}
