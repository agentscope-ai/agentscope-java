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
import { useCallback, useEffect, useRef, useState } from 'react';
import { weixin, WeixinError, weixinErrorMessage, type WeixinLinkFlow } from '@/api/weixin';

export const isActiveFlow = (flow?: WeixinLinkFlow) => !!flow && !['COMPLETED', 'CANCELLED', 'EXPIRED', 'FAILED'].includes(flow.status);
const message = (error: unknown) => error instanceof WeixinError ? error.message : '网络连接中断，请检查网络后重试。';

// Only the opaque flow ID survives a refresh. QR content and verification codes
// stay in memory; the authenticated control plane remains the state authority.
export function useWeixinLink(channelId: string, accountId: string, initial?: WeixinLinkFlow, initialError = '') {
  const storageKey = `weixin-link:${encodeURIComponent(accountId)}:${encodeURIComponent(channelId)}`;
  const [flow, setFlow] = useState(initial);
  const [error, setError] = useState(initialError);
  const [restoring, setRestoring] = useState(!initial);
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(Date.now);
  const action = useRef<AbortController>();
  const poll = useRef<AbortController>();
  const active = isActiveFlow(flow);

  const accept = useCallback((next: WeixinLinkFlow) => {
    setNow(Date.now());
    setFlow(previous => ({ ...next, qrcodeImage: next.qrcodeImage ?? (previous?.flowId === next.flowId ? previous.qrcodeImage : undefined) }));
    setError(next.errorCode ? weixinErrorMessage(next.errorCode) : '');
    try {
      if (isActiveFlow(next)) sessionStorage.setItem(storageKey, next.flowId);
      else sessionStorage.removeItem(storageKey);
    } catch { /* Authorization still works when browser storage is unavailable. */ }
  }, [storageKey]);

  useEffect(() => {
    const controller = new AbortController();
    if (initial) {
      accept(initial);
      setRestoring(false);
    } else {
      let saved: string | null = null;
      try { saved = sessionStorage.getItem(storageKey); } catch { /* Optional resume. */ }
      if (!saved) setRestoring(false);
      else void weixin.get(channelId, saved, controller.signal).then(next => {
        if (!controller.signal.aborted) accept(next);
      }).catch(reason => {
        if (!controller.signal.aborted) {
          setError(message(reason));
          if (reason instanceof WeixinError && [404, 409].includes(reason.status)) {
            try { sessionStorage.removeItem(storageKey); } catch { /* Optional storage. */ }
          }
        }
      }).finally(() => { if (!controller.signal.aborted) setRestoring(false); });
    }
    return () => { controller.abort(); action.current?.abort(); };
    // Initial flow belongs to this keyed panel instance, not each parent refresh.
  }, [channelId, storageKey, accept, initial]);

  useEffect(() => {
    if (!active) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [active]);

  useEffect(() => {
    if (flow && isActiveFlow(flow) && now >= flow.expiresAt && !busy) accept({ ...flow, status: 'EXPIRED', errorCode: '' });
  }, [flow, now, busy, accept]);

  useEffect(() => {
    if (!flow || restoring || busy || !['STARTING', 'WAITING_SCAN', 'SCANNED'].includes(flow.status)) return;
    const controller = new AbortController();
    poll.current = controller;
    let timer: number;
    let failures = 0;
    const check = async () => {
      try {
        const next = await weixin.poll(channelId, flow.flowId, controller.signal);
        if (!controller.signal.aborted) accept(next);
      } catch (reason) {
        if (controller.signal.aborted) return;
        if (reason instanceof WeixinError && [400, 403, 404, 409].includes(reason.status)) {
          accept({ ...flow, status: 'FAILED', errorCode: reason.code });
          setError(reason.message);
          return;
        }
        setError(reason instanceof WeixinError && reason.status === 429 ? '' : message(reason));
        // Schedule only after the previous long poll finishes. No overlapping
        // provider operations, even when a request takes tens of seconds.
        timer = window.setTimeout(check, Math.min(15000, 2000 * ++failures));
      }
    };
    timer = window.setTimeout(check, Math.max(2000, flow.pollAfterMs || 0));
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [channelId, flow, restoring, busy, accept]);

  const run = async (operation: (signal: AbortSignal) => Promise<WeixinLinkFlow>) => {
    if (action.current && !action.current.signal.aborted) return;
    const controller = new AbortController();
    action.current = controller;
    poll.current?.abort();
    setBusy(true);
    setError('');
    try {
      const next = await operation(controller.signal);
      if (!controller.signal.aborted) accept(next);
      return !controller.signal.aborted ? next : undefined;
    } catch (reason) {
      if (!controller.signal.aborted) setError(message(reason));
    } finally {
      if (action.current === controller) {
        action.current = undefined;
        if (!controller.signal.aborted) setBusy(false);
      }
    }
  };

  return {
    flow, error, busy, restoring, remaining: flow ? Math.max(0, Math.ceil((flow.expiresAt - now) / 1000)) : 0,
    start: (relink: boolean) => run(signal => weixin.start(channelId, relink, signal)),
    verify: (code: string) => flow && run(signal => weixin.verify(channelId, flow.flowId, code.trim(), signal)),
    complete: () => flow && run(signal => weixin.complete(channelId, flow.flowId, signal)),
    cancel: () => flow && run(signal => weixin.cancel(channelId, flow.flowId, signal)),
  };
}
