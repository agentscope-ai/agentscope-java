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
import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Check, Copy, Loader2, MessageCircle, QrCode, RefreshCw, Smartphone } from 'lucide-react';
import { weixin, type WeixinConnectionStatus, type WeixinLinkFlow } from '@/api/weixin';
import { disableChannel, enableChannel } from '@/api/channels';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { useAccountIdentity } from '@/lib/accountIdentity';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { isActiveFlow, useWeixinLink } from './useWeixinLink';
import { weixinQrImage } from './qrImage';

const connectionLabels: Record<WeixinConnectionStatus['status'], [string, string]> = {
  PENDING_LINK: ['等待连接', '扫码授权，将这个 Channel 连接到微信。'],
  DISCONNECTED: ['已断开', '重新扫码即可连接微信。'],
  STARTING: ['正在启动', '授权已保存，正在等待微信接收服务启动。'],
  RUNNING: ['已连接', '微信接收服务正在运行。首次对话请完成下方的聊天账号绑定。'],
  DISABLED: ['已暂停', '授权已保留，恢复后继续接收微信消息。'],
  REAUTH_REQUIRED: ['需要重新授权', '微信授权已失效，请使用原来的微信账号重新扫码。'],
  FAILED: ['连接异常', '接收服务暂时未正常运行，可以刷新状态或重新授权。'],
};
const flowLabels: Record<WeixinLinkFlow['status'], string> = {
  STARTING: '正在准备二维码', WAITING_SCAN: '等待微信扫码', SCANNED: '已扫码，请在手机上确认',
  NEED_VERIFY_CODE: '需要微信验证码', AUTHORIZED: '微信已授权，请完成连接',
  COMPLETED: '微信授权已完成', EXPIRED: '二维码已过期', CANCELLED: '本次授权已取消', FAILED: '本次授权未完成',
};
const connectButton = 'bg-emerald-700 text-white hover:bg-emerald-800';

export default function WeixinConnectionPanel({ channelId, initialFlow, initialError, identityLinked, onReadyChange, onChanged, onInitialConsumed }: {
  channelId: string;
  initialFlow?: WeixinLinkFlow;
  initialError?: string;
  identityLinked: boolean;
  onReadyChange: (ready: boolean) => void;
  onChanged: () => void;
  onInitialConsumed: () => void;
}) {
  const scope = useControlPlaneScope();
  const account = useAccountIdentity();
  const link = useWeixinLink(channelId, `${scope.tenant}/${scope.namespace}/${account?.userId || ''}`, initialFlow, initialError);
  const [qr, setQr] = useState('');
  const [qrError, setQrError] = useState(false);
  const [code, setCode] = useState('');
  const [operation, setOperation] = useState('');
  const [operationError, setOperationError] = useState('');
  const [disconnectOpen, setDisconnectOpen] = useState(false);
  const [accountCopied, setAccountCopied] = useState(false);
  const operationPending = useRef(false);
  const current = useQuery({
    queryKey: ['weixin-connection', scope.tenant, scope.namespace, account?.userId, channelId],
    queryFn: ({ signal }) => weixin.status(channelId, signal),
    refetchInterval: 5000,
    retry: false,
  });
  const connection = current.data;
  const active = isActiveFlow(link.flow);
  const blocked = link.busy || link.restoring || !!operation || current.isPending;
  const flow = link.flow;
  const needsScan = flow && ['WAITING_SCAN', 'SCANNED', 'NEED_VERIFY_CODE'].includes(flow.status);
  const label = connection ? connectionLabels[connection.status] : undefined;
  const running = connection?.status === 'RUNNING';
  const namespace = scope.namespaces.find(n => n.tenant === scope.tenant && n.name === scope.namespace);

  useEffect(() => { onInitialConsumed(); }, [onInitialConsumed]);
  useEffect(() => { onReadyChange(running); }, [running, onReadyChange]);
  useEffect(() => {
    let live = true;
    setQr(''); setQrError(false);
    if (flow?.qrcodeImage && needsScan) void weixinQrImage(flow.qrcodeImage).then(image => {
      if (live) setQr(image);
    }).catch(() => { if (live) setQrError(true); });
    return () => { live = false; };
  }, [flow?.qrcodeImage, needsScan]);
  useEffect(() => { setCode(''); }, [flow?.flowId, flow?.status]);
  useEffect(() => { setAccountCopied(false); }, [connection?.accountId]);

  async function perform(name: string, fn: () => Promise<unknown>) {
    if (operationPending.current) return;
    operationPending.current = true;
    setOperation(name); setOperationError('');
    try {
      await fn();
      await current.refetch();
      onChanged();
    } catch (reason) {
      setOperationError(reason instanceof Error ? reason.message : '操作失败，请重试。');
    } finally {
      operationPending.current = false;
      setOperation('');
    }
  }
  async function complete() {
    const result = await link.complete();
    if (result?.status === 'COMPLETED') { void current.refetch(); onChanged(); }
  }
  async function copyAccountId() {
    if (!connection?.accountId) return;
    try {
      await navigator.clipboard.writeText(connection.accountId);
      setAccountCopied(true);
    } catch {
      setAccountCopied(false);
    }
  }

  return <section aria-label="微信连接" className="my-5 border-y border-emerald-200 bg-white">
    <div className="flex flex-wrap items-center gap-4 border-b border-emerald-100 bg-emerald-50/60 px-4 py-5 sm:px-6">
      <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg bg-emerald-700 text-white"><MessageCircle className="h-6 w-6" /></div>
      <div className="min-w-0 flex-1"><h2 className="text-lg font-semibold text-slate-900">微信连接</h2><p className="mt-1 text-sm text-slate-600">扫描二维码，授权此 Channel 接收和回复微信消息。</p></div>
      <span role="status" className={`rounded-full px-3 py-1 text-xs font-medium ${running ? 'bg-emerald-100 text-emerald-800' : 'bg-white text-slate-600'}`}>{label?.[0] || '正在读取状态'}</span>
    </div>
    <div className="space-y-5 px-4 py-5 sm:px-6">
      <ol aria-label="连接步骤" className="flex flex-wrap gap-x-6 gap-y-2 text-sm">
        {['创建 Channel', '微信扫码授权', '关联聊天账号'].map((title, index) => {
          const done = index === 0 || (index === 1 ? connection?.connected : identityLinked);
          return <li key={title} className="flex items-center gap-2 text-slate-600"><span className={`flex h-6 w-6 items-center justify-center rounded-full text-xs ${done ? 'bg-emerald-700 text-white' : 'bg-slate-100 text-slate-500'}`}>{done ? <Check className="h-3.5 w-3.5" /> : index + 1}</span>{title}</li>;
        })}
      </ol>

      {namespace && namespace.kind !== 'personal' && <p className="text-sm text-amber-800">当前为共享空间，普通微信私聊仅支持个人空间中已绑定的账号。请在个人空间中创建 Channel 并选择自己的 Agent。</p>}
      {link.restoring && <p className="flex items-center gap-2 text-sm text-slate-600"><Loader2 className="h-4 w-4 animate-spin" />正在恢复授权状态…</p>}
      {(link.error || operationError || current.isError) && <div role="alert" className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">{link.error || operationError || (current.error instanceof Error ? current.error.message : '暂时无法读取连接状态。')}</div>}

      {active && flow ? <div className="grid gap-6 sm:grid-cols-[240px_minmax(0,1fr)]">
        <div className="flex min-h-60 flex-col items-center justify-center bg-slate-50 p-3">
          {needsScan && qr ? <img src={qr} width={220} height={220} alt="微信授权二维码" className="aspect-square h-auto w-[220px] max-w-full bg-white" />
            : flow.status === 'AUTHORIZED' ? <Check className="h-16 w-16 text-emerald-600" />
              : <><QrCode className="mb-3 h-14 w-14 text-slate-400" /><p className="text-center text-sm text-slate-500">{qrError ? '二维码无法显示，请重新生成。' : flow.qrcodeImage ? '正在绘制二维码…' : '二维码未保留，请重新生成。'}</p></>}
          {needsScan && <p className="mt-2 text-xs text-slate-500">授权剩余 {Math.floor(link.remaining / 60)}:{String(link.remaining % 60).padStart(2, '0')}</p>}
        </div>
        <div className="flex flex-col justify-center gap-4">
          <div><p role="status" className="font-semibold text-slate-900">{flowLabels[flow.status]}</p><p className="mt-2 text-sm leading-6 text-slate-600">{flow.status === 'AUTHORIZED' ? '手机端授权已确认。点击完成连接，保存授权并启动微信接收服务。' : '打开微信「扫一扫」，扫描二维码，并按照手机提示确认授权。'}</p></div>
          {flow.status === 'NEED_VERIFY_CODE' && <form className="space-y-2" onSubmit={event => { event.preventDefault(); if (code.trim()) void link.verify(code); }}>
            <label className="text-sm font-medium" htmlFor="weixin-verification-code">微信验证码</label>
            <Input id="weixin-verification-code" autoComplete="one-time-code" maxLength={64} value={code} onChange={event => setCode(event.target.value)} placeholder="输入微信提示的验证码" disabled={blocked} />
            <Button type="submit" className={connectButton} disabled={blocked || !code.trim()}>验证并继续</Button>
          </form>}
          <div className="flex flex-wrap gap-2">
            {flow.status === 'AUTHORIZED' && <Button className={connectButton} disabled={blocked} onClick={() => void complete()}>{link.busy ? '正在连接…' : '完成连接'}</Button>}
            {flow.status !== 'AUTHORIZED' && <Button variant="outline" disabled={blocked} onClick={() => void link.start(!!connection?.connected)}><RefreshCw className="h-4 w-4" />重新生成二维码</Button>}
            <Button variant="ghost" disabled={blocked} onClick={() => void link.cancel()}>取消授权</Button>
          </div>
          <p className="flex items-center gap-2 text-xs text-slate-500"><Smartphone className="h-4 w-4" />请在自己的微信中完成授权。</p>
        </div>
      </div> : !link.restoring && <div className="flex flex-wrap items-center justify-between gap-4 bg-slate-50 px-4 py-4">
        <div className="max-w-xl"><p className="font-medium text-slate-900">{flow && !['COMPLETED', 'CANCELLED'].includes(flow.status) ? flowLabels[flow.status] : label?.[0] || '微信连接'}</p><p className="mt-1 text-sm leading-6 text-slate-600">{flow?.status === 'EXPIRED' ? '二维码有有效期，重新生成后再扫码即可。' : label?.[1]}</p></div>
        <div className="flex flex-wrap gap-2">
          <Button className={connectButton} disabled={blocked || current.isError} onClick={() => void link.start(!!connection?.connected)}><QrCode className="h-4 w-4" />{link.busy ? '正在生成…' : connection?.connected || connection?.status === 'REAUTH_REQUIRED' ? '重新扫码授权' : '扫码连接微信'}</Button>
          {connection?.connected && <Button variant="outline" disabled={blocked} onClick={() => void perform('toggle', () => connection.disabled ? enableChannel(channelId) : disableChannel(channelId))}>{connection.disabled ? '恢复接收' : '暂停接收'}</Button>}
          {(connection?.connected || connection?.status === 'REAUTH_REQUIRED') && <Button variant="ghost" disabled={blocked} onClick={() => setDisconnectOpen(true)}>断开连接</Button>}
        </div>
      </div>}

      {connection?.connected && connection.accountId && !active && <div className="flex min-w-0 items-center gap-3 border-t border-slate-200 pt-4">
        <span className="shrink-0 text-xs font-medium uppercase tracking-wide text-slate-500">Bot account_id</span>
        <code className="min-w-0 flex-1 break-all rounded bg-slate-50 px-2 py-1 text-xs text-slate-700">{connection.accountId}</code>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="shrink-0"
          aria-label={accountCopied ? '已复制 Bot account_id' : '复制 Bot account_id'}
          title={accountCopied ? '已复制' : '复制'}
          onClick={() => void copyAccountId()}
        >
          <Copy className="h-4 w-4" />
        </Button>
      </div>}

      {connection?.connected && !active && !identityLinked && <p className="text-sm text-slate-600">首次使用：在下方<a href="#channel-identity" className="mx-1 font-medium text-emerald-800 underline underline-offset-4">关联聊天账号</a>，将绑定命令发送给微信机器人，再向默认 Agent 发起对话。</p>}
      {running && identityLinked && !active && namespace?.kind === 'personal' && <p role="status" className="text-sm text-emerald-800">微信聊天账号已关联，可以向机器人发送消息。</p>}
      {!active && <button className="text-xs text-slate-500 underline underline-offset-4 disabled:opacity-50" disabled={current.isFetching} onClick={() => void current.refetch()}>刷新连接状态</button>}
    </div>
    <Dialog open={disconnectOpen} onOpenChange={open => { if (!operation) setDisconnectOpen(open); }}>
      <DialogContent size="md"><DialogHeader><DialogTitle>断开微信连接</DialogTitle><DialogDescription>断开后停止接收消息并移除本地授权。聊天历史会保留，再次连接需要扫码；此操作不会在微信端撤销授权。</DialogDescription></DialogHeader>
        <DialogBody><div className="flex justify-end gap-3"><Button variant="outline" disabled={!!operation} onClick={() => setDisconnectOpen(false)}>保留连接</Button><Button variant="destructive" disabled={!!operation} onClick={() => void perform('disconnect', async () => { await weixin.disconnect(channelId); setDisconnectOpen(false); })}>确认断开</Button></div></DialogBody>
      </DialogContent>
    </Dialog>
  </section>;
}
