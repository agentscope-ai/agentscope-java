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

import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { ApiError } from '@/lib/apiClient';

type BusyState = boolean | null | undefined;

/**
 * Compress action with busy=unknown force-confirm and busy=true auto-queue.
 */
export function CompressButton({
  busy,
  disabled,
  pending,
  onCompress,
}: {
  busy?: BusyState;
  disabled?: boolean;
  pending?: boolean;
  onCompress: (opts: { force?: boolean; queue?: boolean }) => Promise<{ queued?: boolean } | void>;
}) {
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [working, setWorking] = useState(false);

  async function run(opts: { force?: boolean; queue?: boolean }) {
    setWorking(true);
    setNote(null);
    try {
      const res = await onCompress(opts);
      if (res && res.queued) {
        setNote('Queued — will run when the session becomes idle.');
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        try {
          const body = JSON.parse(e.body) as { code?: string; hint?: string; error?: string };
          if (body.hint === 'force_confirm') {
            setConfirmOpen(true);
            return;
          }
          setNote(body.error || 'Session is busy. Try again when idle, or leave the default queue behavior.');
          return;
        } catch {
          /* fall through */
        }
      }
      setNote(e instanceof Error ? e.message : String(e));
    } finally {
      setWorking(false);
    }
  }

  function onClick() {
    setNote(null);
    // busy unknown → ask before forcing
    if (busy === null || busy === undefined) {
      setConfirmOpen(true);
      return;
    }
    // busy=true → queue by default; busy=false → run now
    void run({ queue: true });
  }

  return (
    <div className="flex flex-col items-end gap-1">
      <Button size="sm" variant="outline" disabled={disabled || pending || working} onClick={onClick}>
        {working || pending ? 'Compressing…' : 'Compress'}
      </Button>
      {note && <p className="max-w-xs text-right text-xs text-muted-foreground">{note}</p>}
      {confirmOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
          <div className="w-full max-w-md space-y-4 rounded-lg border border-border bg-background p-4 shadow-lg">
            <h2 className="text-sm font-semibold">Confirm compress</h2>
            <p className="text-sm text-muted-foreground">
              This data plane did not report whether the session is mid-turn. Compressing may interrupt
              in-flight reasoning. Continue anyway?
            </p>
            <div className="flex justify-end gap-2">
              <Button size="sm" variant="outline" onClick={() => setConfirmOpen(false)}>
                Cancel
              </Button>
              <Button
                size="sm"
                onClick={() => {
                  setConfirmOpen(false);
                  void run({ force: true, queue: true });
                }}
              >
                Force compress
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
