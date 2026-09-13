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

import { describe, expect, it } from 'vitest';
import { approvalAttentionSummary, formatAttentionCount } from './approvalAttention';

describe('approvalAttentionSummary', () => {
  it('deduplicates unread notifications linked to pending approvals', () => {
    expect(approvalAttentionSummary([
      { approvalId: 'approval-1', read: false, archived: false },
      { read: false, archived: false },
      { approvalId: 'resolved', read: false, archived: false },
      { read: false, archived: true },
      { read: true, archived: false },
    ], [
      { id: 'approval-1' },
      { id: 'approval-2' },
    ])).toEqual({ total: 4, unread: 3, pending: 2 });
  });

  it('caps the compact menu label', () => {
    expect(formatAttentionCount(12)).toBe('12');
    expect(formatAttentionCount(120)).toBe('99+');
  });
});
