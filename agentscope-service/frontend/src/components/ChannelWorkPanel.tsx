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

/* Copyright 2024-2026 the original author or authors. Licensed under Apache-2.0. */
import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getGroups } from '@/api/resourceAccess';
import { Link } from 'react-router-dom';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { listTeams, type Team } from '@/api/collaboration';
import { AgentPicker } from '@/components/AgentPicker';
import {
  getChannelWorkSettings, saveChannelWorkSettings, createChannelPairing, getChannelWorkActivity,
  unlinkChannelIdentity, retryChannelDelivery, retryChannelIntake, unsubscribeChannelWork,
  type ChannelWorkSettings, type ChannelWorkTarget, type ChannelWorkActivity,
} from '@/api/channels';

const field = 'rounded-md border border-slate-300 bg-white p-2 text-sm';
const button = `${field} hover:bg-slate-50 disabled:opacity-50`;
const panel = 'my-4 rounded-xl border border-slate-200 bg-white p-5 space-y-4';
const states: Record<string, string> = { pending: '等待发送', submitted: '等待平台回执', provider_accepted: '平台已接受', failed: '发送失败', cancelled: '权限或订阅已撤销' };

export default function ChannelWorkPanel({ channelId, canConfigure }: { channelId: string; canConfigure: boolean }) {
  const scope = useControlPlaneScope();
  const groups = useQuery({ queryKey: ['namespace-groups', scope.namespace], queryFn: () => getGroups(scope.namespace), enabled: canConfigure });
  const [config, setConfig] = useState<ChannelWorkSettings>();
  const [activity, setActivity] = useState<ChannelWorkActivity>();
  const [teams, setTeams] = useState<Team[]>([]);
  const [pairing, setPairing] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const canUse = scope.roles.some(r => ['member', 'developer', 'admin'].includes(r));
  async function refreshActivity() { setActivity(await getChannelWorkActivity(channelId)); }
  useEffect(() => {
    let active = true;
    Promise.all([getChannelWorkSettings(channelId), getChannelWorkActivity(channelId), listTeams(scope.tenant, scope.namespace)])
      .then(([settings, a, t]) => { if (active) { setConfig({ ...settings, defaultTarget: { targetType: settings.defaultTarget?.targetType || 'agent', targetRef: settings.defaultTarget?.targetRef || '' } }); setActivity(a); setTeams(t.items); } })
      .catch(e => { if (active) setError(String(e)); });
    return () => { active = false; };
  }, [channelId, scope.tenant, scope.namespace]);
  async function perform(action: () => Promise<unknown>) {
    setBusy(true); setError(''); setNotice('');
    try { await action(); await refreshActivity(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }
  function target(value: ChannelWorkTarget, onChange: (v: ChannelWorkTarget) => void, label: string) {
    return <div className="flex flex-wrap gap-2 items-center">
      <select className={field} aria-label={`${label}类型`} value={value.targetType} disabled={!canConfigure}
        onChange={e => onChange({ targetType: e.target.value as 'agent' | 'team', targetRef: '' })}>
        <option value="agent">Agent</option><option value="team">Team</option>
      </select>
      {value.targetType === 'agent' ? <AgentPicker aria-label={label} value={value.targetRef} disabled={!canConfigure} onChange={v => onChange({ ...value, targetRef: v })} />
        : <select className={field} aria-label={label} value={value.targetRef} disabled={!canConfigure} onChange={e => onChange({ ...value, targetRef: e.target.value })}>
          <option value="">选择 Team</option>{teams.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
        </select>}
    </div>;
  }
  return <>
    {error && <p role="alert" className="text-red-700">{error}</p>}
    {notice && <p role="status" className="text-green-700">{notice}</p>}
    <section className={panel}>
      <h2 className="font-semibold text-lg">我的外部账号</h2>
      <p className="text-sm text-slate-600">生成绑定码后，在与机器人的私聊中发送命令。绑定仅代表你的账号，工作权限随空间成员资格实时变化。</p>
      <div className="flex gap-2">
        <button className={button} disabled={busy || !canUse} onClick={() => void perform(async () => { const p = await createChannelPairing(channelId); setPairing(p.command); })}>生成绑定码</button>
        <button className={button} disabled={busy || !activity?.identities.length} onClick={() => void perform(async () => { await unlinkChannelIdentity(channelId); setPairing(''); })}>解除我的绑定</button>
      </div>
      {pairing && <div className="rounded bg-slate-50 p-3"><p className="text-sm mb-2">10 分钟有效，仅在机器人私聊中使用。</p><code className="break-all select-all">{pairing}</code></div>}
      {activity?.identities.map(i => <p className="text-sm break-all" key={`${i.accountId}:${i.senderId}`}>组织 {i.accountId} · 用户 {i.senderId}</p>)}
    </section>
    {config && <section className={panel}>
      <h2 className="font-semibold text-lg">工作接待</h2>
      <p className="text-sm text-slate-600">飞书可创建和跟进 Issue，并持续回传结果。Team 会进入协作编排。关闭工作接待后，仅支持个人空间中已绑定账号的私聊会话。</p>
      <label className="flex gap-2"><input type="checkbox" checked={config.enabled} disabled={!canConfigure} onChange={e => setConfig({ ...config, enabled: e.target.checked })} />启用工作接待（飞书）</label>
      <p className="text-sm font-medium">默认接待对象</p>{target(config.defaultTarget, t => setConfig({ ...config, defaultTarget: t }), '默认接待对象')}
      <label className="flex gap-2 text-sm"><input type="checkbox" checked={config.allowGroupWork} disabled={!canConfigure} onChange={e => setConfig({ ...config, allowGroupWork: e.target.checked })} />允许群接待；创建者须使用 /new-shared 明确共享工作</label>
      <p className="text-sm text-slate-600">私聊创建的工作默认私有。群回传要求工作对空间成员可见，并由创建者关联该群。一个会话存在多个工作时，需回复具体消息或指定 Issue ID。</p>
      <h3 className="font-medium">按会话路由</h3>
      {config.routes.map((r, i) => <div className="border rounded-lg p-3 space-y-2" key={i}>
        <div className="flex gap-2 flex-wrap">
          {(['accountId', 'peerId', 'threadId'] as const).map(k => <input key={k} className={field} aria-label={`路由 ${i + 1} ${k}`} placeholder={k === 'accountId' ? '平台组织 ID' : k === 'peerId' ? '会话 ID' : '线程 ID（可选）'} value={r[k] || ''} disabled={!canConfigure} onChange={e => setConfig({ ...config, routes: config.routes.map((x, j) => i === j ? { ...x, [k]: e.target.value } : x) })} />)}
          <select className={field} aria-label={`路由 ${i + 1} 会话类型`} value={r.peerKind} disabled={!canConfigure} onChange={e => setConfig({ ...config, routes: config.routes.map((x, j) => i === j ? { ...x, peerKind: e.target.value as 'DIRECT' | 'GROUP' } : x) })}><option value="DIRECT">私聊</option><option value="GROUP">群聊</option></select>
        </div>
        {target(r, t => setConfig({ ...config, routes: config.routes.map((x, j) => i === j ? { ...x, ...t } : x) }), `路由 ${i + 1} 接待对象`)}
        {canConfigure && <div className="space-y-2 border-t pt-3"><label className="flex gap-2 text-sm"><input aria-label={`路由 ${i + 1} 限制用户组`} type="checkbox" checked={r.restrictGroups || false} onChange={e => setConfig({ ...config, routes: config.routes.map((x, j) => i === j ? { ...x, restrictGroups: e.target.checked } : x) })} />仅允许指定用户组通过此窗口提交任务和接收工作事件</label>{r.restrictGroups && <div className="flex flex-wrap gap-3">{Object.entries(groups.data?.groups || {}).map(([id, g]) => <label key={id} className="flex gap-2 text-sm"><input aria-label={`路由 ${i + 1} 用户组 ${g.name}`} type="checkbox" checked={r.allowedGroups?.includes(id) || false} onChange={e => setConfig({ ...config, routes: config.routes.map((x, j) => i === j ? { ...x, allowedGroups: e.target.checked ? [...x.allowedGroups || [], id] : x.allowedGroups?.filter(v => v !== id) } : x) })} />{g.name}</label>)}{!r.allowedGroups?.length && <span className="text-xs text-amber-700">未选择用户组时，此窗口不接收工作请求。</span>}</div>}</div>}
        {canConfigure && <button className={button} onClick={() => setConfig({ ...config, routes: config.routes.filter((_, j) => i !== j) })}>移除路由</button>}
      </div>)}
      {canConfigure && <button className={button} onClick={() => setConfig({ ...config, routes: [...config.routes, { accountId: '', peerId: '', peerKind: 'DIRECT', targetType: 'agent', targetRef: '' }] })}>添加会话路由</button>}
      <h3 className="font-medium">工作回传</h3>
      <p className="text-sm text-slate-600">仅向已关联的会话回传；绑定某个 Agent 或 Team 不会订阅其全部工作。</p>
      <div className="flex gap-4">{[['result', '工作结果'], ['status', '状态变化']].map(([event, name]) => <label key={event} className="flex gap-2 text-sm"><input type="checkbox" checked={config.notifyEvents.includes(event)} disabled={!canConfigure} onChange={e => setConfig({ ...config, notifyEvents: e.target.checked ? [...config.notifyEvents, event] : config.notifyEvents.filter(x => x !== event) })} />{name}</label>)}</div>
      {canConfigure && <button className={button} disabled={busy} onClick={() => void perform(async () => { setConfig(await saveChannelWorkSettings(channelId, config)); setNotice('工作接待与回传配置已保存。'); })}>保存工作配置</button>}
    </section>}
    <section className={panel}>
      <div className="flex justify-between"><h2 className="font-semibold text-lg">我的工作关联与回传记录</h2><button className={button} disabled={busy} onClick={() => void perform(refreshActivity)}>刷新记录</button></div>
      <p className="text-sm text-slate-600">平台接受不代表用户已读，也不会自动完成工作验收或工具审批。工具审批仍通过控制台进行。</p>
      {activity?.links.map(l => <div className="flex gap-3 items-center text-sm" key={l.id}><Link className="text-indigo-700" to={scope.scopedPath(`/work/issues/${l.issueId}`)}>{l.issueId}</Link><span className="break-all">{l.address.peerId}{l.address.threadId ? ` / ${l.address.threadId}` : ''}</span>{l.active ? <button className={button} disabled={busy} onClick={() => void perform(() => unsubscribeChannelWork(channelId, l.id))}>停止回传</button> : <span>已停止</span>}</div>)}
      {activity?.inbounds?.some(m => m.state === 'failed') && <div className="rounded border border-amber-200 p-3"><h3 className="font-medium">待重试的接收消息</h3>{activity.inbounds.filter(m => m.state === 'failed').map(m => <div className="flex gap-3 items-center text-sm" key={m.id}><code>{m.id}</code><span>{m.attempts} 次处理失败</span><button className={button} disabled={busy || !canUse} onClick={() => void perform(() => retryChannelIntake(channelId, m.id))}>重新处理</button></div>)}</div>}
      <div className="overflow-auto"><table className="w-full text-sm text-left"><thead><tr><th>状态</th><th>尝试次数</th><th>平台消息 ID</th><th>操作</th></tr></thead><tbody>
        {activity?.deliveries.map(d => <tr className="border-t" key={d.id}><td className="py-3">{states[d.state] || d.state}{d.lastError && <p className="text-xs text-red-700">{d.lastError}</p>}</td><td>{d.attempts}</td><td className="font-mono break-all">{d.providerMessageId || '—'}</td><td>{d.state === 'failed' && <button className={button} disabled={busy} onClick={() => void perform(() => retryChannelDelivery(channelId, d.id))}>重试</button>}</td></tr>)}
      </tbody></table></div>
    </section>
  </>;
}
