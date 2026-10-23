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
