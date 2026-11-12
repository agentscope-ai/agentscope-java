/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiFetch, ApiError } from '@/lib/apiClient';
import { weixin, WeixinError } from './weixin';

vi.mock('@/lib/apiClient', async importOriginal => {
  const original = await importOriginal<typeof import('@/lib/apiClient')>();
  return { ...original, apiFetch: vi.fn() };
});

describe('Weixin authorization API', () => {
  beforeEach(() => vi.mocked(apiFetch).mockReset());
  it('sends encoded Channel and flow IDs with abortable no-store requests', async () => {
    const controller = new AbortController();
    await weixin.verify('channel/a', 'flow?b', '1234', controller.signal);
    expect(apiFetch).toHaveBeenCalledWith('/api/channels/channel%2Fa/weixin/link-flows/flow%3Fb/verify', {
      method: 'POST', signal: controller.signal, cache: 'no-store', body: '{"verifyCode":"1234"}',
    });
  });
  it('uses the relink operation without disconnecting the current credential', async () => {
    await weixin.start('channel', true);
    expect(apiFetch).toHaveBeenCalledTimes(1);
    expect(apiFetch).toHaveBeenCalledWith('/api/channels/channel/weixin/relink', expect.objectContaining({ method: 'POST' }));
  });
  it('translates known conflicts and never renders raw provider responses', async () => {
    vi.mocked(apiFetch).mockRejectedValueOnce(new ApiError(409, '{"errorCode":"weixin_account_in_use"}'));
    await expect(weixin.complete('channel', 'flow')).rejects.toThrow('已绑定其他 Channel');
    vi.mocked(apiFetch).mockRejectedValueOnce(new ApiError(502, 'private-provider-response'));
    await expect(weixin.poll('channel', 'flow')).rejects.toEqual(new WeixinError(502, ''));
  });
});
