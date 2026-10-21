// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
import { useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import { ArrowDown, ArrowUpRight, Check, Circle, Copy, Link2, Radio, RefreshCw, Send, Terminal, Zap } from 'lucide-react';
import { AgentSessionView } from './sessionView';
import type { AgentSessionEvent, AgentSessionSnapshot, AgentTurn } from './types';
import { SessionClient, sessionIdFrom } from './client';

declare const __PLAYGROUND_API_TARGET__: string;
const gateway = __PLAYGROUND_API_TARGET__;
type Data = Record<string, unknown>;
const object = (v: unknown): Data => v && typeof v === 'object' && !Array.isArray(v) ? v as Data : {};
const blocks = (v: unknown): Data[] => Array.isArray(v) ? v.map(object) : [];
const short = (v: unknown) => String(v ?? '').slice(0, 12);
const textOf = (v: unknown) => blocks(v).filter(b => b.type === 'text').map(b => String(b.text ?? '')).join('');
const time = (v?: number | string) => v ? new Date(v).toLocaleTimeString('zh-CN', { hour12: false }) : '—';
const errorText = (v: unknown) => v instanceof Error ? v.message : String(v);
const initialSession = () => {
  const query = new URLSearchParams(location.search).get('session'); if (query) return query;
  try { return localStorage.getItem('session-playground.session') || ''; } catch { return ''; }
};
const examples = [
  { name: '普通对话', message: '请用中文简洁介绍你的能力，不要调用工具。' },
  { name: '一次工具执行', message: '请实际调用 execute 工具执行 printf HELLO-HANDS，拿到结果后告诉我实际输出。不要只描述操作。' },
  { name: '三阶段观察（约 1 分钟）', message: '请执行三阶段观察任务，必须依次完成。每阶段先公开说明目标，再实际调用一次 execute。第一阶段 command 为 printf phase-1-start; sleep 20; printf phase-1-done，timeout 为 60。第二阶段 command 为 printf phase-2-start; sleep 20; printf phase-2-done，timeout 为 60。第三阶段 command 为 printf phase-3-start; sleep 20; printf phase-3-done，timeout 为 60。每次拿到工具结果后，先用约150字解释这次实际命令和结果，再进入下一阶段。最后汇总三次实际工具输出。遇到错误时如实报告，不要虚构结果。' },
];
type Submission = { session: string; gateway: string; message: string; key: string };
const pendingKey = 'session-playground.pending';
function restoredSubmission(): Submission | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(pendingKey) || 'null');
    return value && value.gateway === gateway && typeof value.session === 'string' && typeof value.message === 'string' && typeof value.key === 'string' ? value : null;
  } catch { return null; }
}
const colors: Record<string, string> = { completed: 'ok', success: 'ok', ready: 'ok', active: 'ok', connected: 'ok',
  failed: 'bad', error: 'bad', interrupted: 'bad', cancelled: 'muted', disconnected: 'muted', unknown: 'muted',
  running: 'live', in_progress: 'live', queued: 'live', connecting: 'live', reconnecting: 'warn', waiting: 'warn', suspended: 'warn', requires_action: 'warn' };
function Status({ value }: { value: unknown }) { const label = String(value ?? 'unknown'); return <span className={`status ${colors[label] ?? 'muted'}`}><i />{label}</span>; }
function Json({ value, label = '原始 JSON' }: { value: unknown; label?: string }) {
  return <details className="json"><summary>{label}</summary><pre>{JSON.stringify(value, null, 2)}</pre></details>;
}
function CopyButton({ value }: { value: string }) {
  const [copied, setCopied] = useState(false);
  return <button className="icon-button" title="复制" onClick={() => { void navigator.clipboard.writeText(value).then(() => { setCopied(true); setTimeout(() => setCopied(false), 1500); }); }}>{copied ? <Check size={14} /> : <Copy size={14} />}</button>;
}
interface LiveSession { client: SessionClient; lifetime: AbortController; stream?: AbortController; view?: AgentSessionView }

export default function App() {
  const [address, setAddress] = useState(initialSession);
  const [token, setToken] = useState(''), [tenant, setTenant] = useState(''), [namespace, setNamespace] = useState('');
  const [connection, setConnection] = useState('disconnected'), [connectionDetail, setConnectionDetail] = useState('');
  const [view, setView] = useState<AgentSessionSnapshot>(), [raw, setRaw] = useState<unknown>();
  const [events, setEvents] = useState<AgentSessionEvent[]>([]), [received, setReceived] = useState(0), [heartbeats, setHeartbeats] = useState(0);
  const [lastSeen, setLastSeen] = useState<number>(), [error, setError] = useState('');
  const [message, setMessage] = useState(''), [sending, setSending] = useState(false), [loading, setLoading] = useState(false);
  const [pending, setPending] = useState<Submission | null>(restoredSubmission);
  const [receipt, setReceipt] = useState<AgentTurn>(), [selectedTurn, setSelectedTurn] = useState('');
  const [turnDetails, setTurnDetails] = useState<AgentTurn>(), [filter, setFilter] = useState(''), [hideDeltas, setHideDeltas] = useState(true);
  const [tab, setTab] = useState<'events' | 'snapshot'>('events'), [follow, setFollow] = useState(true), [paging, setPaging] = useState('');
  const active = useRef<LiveSession>(), flush = useRef<ReturnType<typeof setTimeout>>(), post = useRef<AbortController>();
  const conversation = useRef<HTMLDivElement>(null);
  const addressMatches = (() => { try { return !!view && sessionIdFrom(address, gateway) === view.session.id; } catch { return false; } })();
  useEffect(() => () => { active.current?.lifetime.abort(); active.current?.stream?.abort(); post.current?.abort(); clearTimeout(flush.current); }, []);
  useEffect(() => { if (follow && conversation.current) conversation.current.scrollTop = conversation.current.scrollHeight; }, [view, follow, selectedTurn]);
  const isCurrent = (s: LiveSession) => active.current === s && !s.lifetime.signal.aborted;
  const publish = (s: LiveSession) => { if (isCurrent(s) && s.view) setView(s.view.snapshot()); };
  const schedule = (s: LiveSession) => {
    if (flush.current) return;
    flush.current = setTimeout(() => { flush.current = undefined; publish(s); }, 80);
  };
  const startStream = (s: LiveSession) => {
    if (!s.view || !isCurrent(s)) return;
    s.stream?.abort(); const controller = new AbortController(); s.stream = controller;
    setConnection('connecting'); setConnectionDetail('');
    const valid = () => isCurrent(s) && s.stream === controller && !controller.signal.aborted;
    void s.client.stream(s.view.snapshot().as_of, controller.signal, {
      onState: (state, detail) => { if (valid()) { setConnection(state); setConnectionDetail(detail ?? ''); } },
      onHeartbeat: () => { if (valid()) { setHeartbeats(n => n + 1); setLastSeen(Date.now()); } },
      onEvent: event => {
        if (!valid()) return;
        s.view!.apply(event); schedule(s);
        setReceived(n => n + 1); setLastSeen(Date.now());
        setEvents(old => [...old, event].slice(-400));
      },
    }).catch(failure => { if (valid()) { setConnection('disconnected'); setError(`${errorText(failure)} 可刷新快照后重连。`); publish(s); } });
  };
  const connect = async () => {
    setError(''); let id: string;
    try { id = sessionIdFrom(address, gateway); } catch (failure) { setError(errorText(failure)); return; }
    active.current?.lifetime.abort(); active.current?.stream?.abort(); clearTimeout(flush.current); flush.current = undefined;
    const s: LiveSession = { client: new SessionClient(id, token, tenant, namespace), lifetime: new AbortController() }; active.current = s;
    setLoading(true); setConnection('connecting'); setConnectionDetail(''); setView(undefined); setRaw(undefined);
    setEvents([]); setReceived(0); setHeartbeats(0); setLastSeen(undefined); setSelectedTurn(''); setTurnDetails(undefined); setReceipt(undefined); setPaging('');
    try {
      const snapshot = await s.client.snapshot(s.lifetime.signal);
      if (!isCurrent(s)) return;
      s.view = new AgentSessionView(snapshot.view); setRaw(snapshot.raw); publish(s);
      try { localStorage.setItem('session-playground.session', id); } catch { /* Connection does not require persistent browser preferences. */ }
      const url = new URL(location.href); url.searchParams.set('session', id); history.replaceState(null, '', url);
      startStream(s);
    } catch (failure) { if (isCurrent(s)) { setError(errorText(failure)); setConnection('disconnected'); } }
    finally { if (isCurrent(s)) setLoading(false); }
  };
  const disconnect = () => { active.current?.stream?.abort(); setConnection('disconnected'); setConnectionDetail(''); };
  const send = async (retry = false) => {
    const s = active.current;
    if (!s?.view || !addressMatches || !message.trim() && !retry || sending) return;
    const request = retry ? pending : { session: s.client.session, gateway, message: message.trim(), key: crypto.randomUUID() };
    if (!request || request.session !== s.client.session || request.gateway !== gateway) { setError('请先连接待确认请求所属的 Session。'); return; }
    // Persist before POST: reload or response loss must preserve the exact body and key.
    try { sessionStorage.setItem(pendingKey, JSON.stringify(request)); } catch { setError('浏览器无法保存请求收据，请允许 sessionStorage 后再发送。'); return; }
    setPending(request); setSending(true); setError('');
    const controller = new AbortController(); post.current = controller;
    const deadline = setTimeout(() => controller.abort(), 30000);
    try {
      const result = await s.client.submit(request.message, request.key, controller.signal);
      if (!isCurrent(s)) return;
      setReceipt(result); setSelectedTurn(result.id); setTurnDetails(result); setMessage('');
      setPending(null); sessionStorage.removeItem(pendingKey);
    } catch (failure) { if (isCurrent(s)) setError(`发送未确认：${errorText(failure)}。可以使用下方按钮以原内容和原幂等键重试。`); }
    finally { clearTimeout(deadline); if (isCurrent(s)) setSending(false); }
  };
  const refreshTurn = async () => {
    const s = active.current; if (!s || !selectedTurn) return;
    setError('');
    try { const result = await s.client.turn(selectedTurn, s.lifetime.signal); if (isCurrent(s)) setTurnDetails(result); }
    catch (failure) { if (isCurrent(s)) setError(errorText(failure)); }
  };
  const loadPage = async (kind: string) => {
    const s = active.current, page = s?.view?.snapshot().resource_pages?.[kind]; if (!s || !page?.has_more) return;
    setPaging(kind); setError('');
    try {
      const next = await s.client.page(kind, page, s.lifetime.signal);
      if (isCurrent(s)) { next.data.forEach(event => { if (event.session_id !== s.client.session) throw new Error('分页 Session 不匹配。'); }); s.view!.mergeResourcePage(kind, next); publish(s); }
    } catch (failure) { if (isCurrent(s)) setError(`${errorText(failure)}。快照分页过期时，请刷新快照。`); }
    finally { if (isCurrent(s)) setPaging(''); }
  };
  const scoped = (event: AgentSessionEvent) => !selectedTurn || event.data.turn_id === selectedTurn;
  const items = (view?.items ?? []).filter(scoped);
  const tools = (view?.tools ?? []).filter(scoped);
  const turns = [...view?.turns ?? []].sort((a, b) => new Date(b.created_at ?? 0).getTime() - new Date(a.created_at ?? 0).getTime());
  const turnState = turns.find(e => e.data.turn_id === selectedTurn)?.data;
  const selectedDetails = turnDetails?.id === selectedTurn ? turnDetails : undefined;
  const visibleEvents = events.filter(e => scoped(e) && (!hideDeltas || !['item.delta', 'tool.delta'].includes(e.type)) && (!filter || `${e.type} ${JSON.stringify(e.data)}`.toLowerCase().includes(filter.toLowerCase())));
  const totals = object(view?.usage?.totals);
  const availablePages = Object.entries(view?.resource_pages ?? {}).filter(([kind, page]) => kind !== 'model_usage' && page.has_more);
  return <div className="playground">
    <header className="topbar"><div className="brand"><span className="brand-symbol"><Zap size={22} fill="currentColor" /></span><div><strong>AgentScope Service</strong><span>SESSION PLAYGROUND</span></div></div><div className="top-meta"><span className="dev-label">LOCAL DEV</span><span>发送 · 观察 · 核对</span></div></header>
    <main>
      <section className="intro"><div><p className="eyebrow">让执行过程看得见</p><h1>一个会话，完整观察。</h1><p>发送用户指令，实时查看输出、工具执行与公开事件。</p></div><div className="intro-note"><Radio size={18} /><span>SSE 断开只停止观察<br />任务继续在服务端运行</span></div></section>
      <section className="connection-card">
        <div className="connection-main"><label htmlFor="session">Session ID / Session URL</label><div className="input-row"><Link2 size={18} /><input id="session" value={address} onChange={e => setAddress(e.target.value)} placeholder="粘贴终端创建的 Session ID 或完整 URL" disabled={sending} onKeyDown={e => { if (e.key === 'Enter' && !sending && !loading) void connect(); }} /><button className="primary" onClick={() => void connect()} disabled={loading || sending || !address.trim()}>{loading ? '读取中…' : addressMatches ? '刷新快照' : '连接并观察'}<ArrowUpRight size={16} /></button></div><div className="proxy"><span>API 代理</span><code>{gateway}</code><span>{view && !addressMatches ? '地址已修改，请重新连接后再发送' : '当前 dev 模式可留空认证'}</span></div></div>
        <div className="connection-actions"><Status value={connection} />{connection === 'disconnected' ? <button className="secondary" onClick={() => active.current && startStream(active.current)} disabled={!view || loading}><Radio size={14} />连接 SSE</button> : <button className="secondary" onClick={disconnect}>断开 SSE</button>}</div>
        <details className="auth"><summary>认证与 Namespace（可选）</summary><div className="auth-fields"><label>Bearer Token<input type="password" value={token} onChange={e => setToken(e.target.value)} placeholder="仅保存在当前页面内存" /></label><label>Tenant<input value={tenant} onChange={e => setTenant(e.target.value)} placeholder="default" /></label><label>Namespace<input value={namespace} onChange={e => setNamespace(e.target.value)} placeholder="default" /></label></div><p>修改后重新连接生效。</p></details>
      </section>
      {error && <div className="notice error" role="alert">{error}<button onClick={() => setError('')}>关闭</button></div>}
      {connectionDetail && <div className="notice">{connectionDetail}</div>}
      <section className="metrics"><div><span>Session 状态</span><strong>{view?.session.status ?? '未连接'}</strong></div><div><span>模型调用 / 总 Token</span><strong>{String(view?.usage?.model_calls ?? '—')} <small>/ {typeof totals.totalTokens === 'number' ? totals.totalTokens.toLocaleString() : '—'}</small></strong></div><div><span>本次 SSE 事件 / 心跳</span><strong>{received} <small>/ {heartbeats}</small></strong></div><div><span>最近收到数据</span><strong>{time(lastSeen)}</strong></div></section>
      <div className="workspace">
        <section className="conversation-panel panel"><div className="panel-heading"><div><p className="eyebrow">CONVERSATION</p><h2>指令与回复</h2></div><label className="toggle"><input type="checkbox" checked={follow} onChange={e => setFollow(e.target.checked)} /><ArrowDown size={13} />跟随输出</label></div>
          <div className="turn-bar"><select aria-label="筛选 Turn" value={selectedTurn} onChange={e => { setSelectedTurn(e.target.value); setTurnDetails(undefined); }}><option value="">全部 Turns · {turns.length}</option>{turns.map(e => <option key={String(e.data.turn_id)} value={String(e.data.turn_id)}>{short(e.data.turn_id)} · {String(e.data.status ?? e.type.replace('turn.', ''))}</option>)}{selectedTurn && !turns.some(e => e.data.turn_id === selectedTurn) && <option value={selectedTurn}>{short(selectedTurn)} · {receipt?.status ?? '受理中'}</option>}</select>{selectedTurn && <><Status value={turnState?.status ?? selectedDetails?.status ?? (receipt?.id === selectedTurn ? receipt.status : undefined)} /><button className="icon-button" title="查询 Turn API" aria-label="查询 Turn API" onClick={() => void refreshTurn()}><RefreshCw size={15} /></button><CopyButton value={selectedTurn} /></>}</div>
          <div className="messages" ref={conversation}>
            {!items.length && <div className="empty"><span className="empty-icon"><Send size={27} /></span><h3>{view ? '等待第一条指令' : '从一个 Session 开始'}</h3><p>{view ? '在下方发送指令，回复将在这里逐步显示。' : '在终端创建 Agent 和 Session，然后把 Session 地址粘贴到上方。'}</p></div>}
            {items.map(event => { const item = object(event.data.item), role = String(item.role ?? 'assistant').toLowerCase(), content = blocks(item.content), text = textOf(item.content); return <article className={`message ${role === 'user' ? 'user' : 'assistant'}`} key={String(event.data.item_id)}><div className="message-meta"><span>{role === 'user' ? '你' : role === 'tool' ? '工具结果' : 'Agent'}</span><span>{time(event.created_at)}</span>{event.data.final_output === true && <b>最终回复</b>}{item.status === 'in_progress' && <span className="typing">输出中…</span>}</div>{text && <div className="markdown"><ReactMarkdown>{text}</ReactMarkdown></div>}{content.filter(b => b.type !== 'text').map((b, i) => <Json key={i} value={b} label={b.type === 'tool_use' ? `工具调用 · ${String(b.name ?? '')}` : b.type === 'tool_result' ? '工具返回' : String(b.type ?? '内容块')} />)}<Json value={event} label={`消息详情 · ${short(event.data.item_id)}`} /></article>; })}
          </div>
          <div className="composer"><select className="examples" aria-label="填入测试指令" defaultValue="" disabled={!view || !addressMatches || sending || !!pending} onChange={e => { if (e.target.value) setMessage(examples[Number(e.target.value)].message); e.target.value = ''; }}><option value="">填入测试指令（不会自动发送）</option>{examples.map((example, i) => <option key={i} value={i}>{example.name}</option>)}</select>{pending && <div className="pending"><strong>有一条发送结果待确认</strong><p>{pending.message.slice(0, 160)}</p><code>key: {pending.key}</code><div><button className="secondary" onClick={() => void send(true)} disabled={sending || !view || !addressMatches || view.session.id !== pending.session}>{sending ? '确认中…' : '以原幂等键重试'}</button><button className="text-button" onClick={() => { setPending(null); sessionStorage.removeItem(pendingKey); }}>放弃本地收据</button></div><small>放弃收据不会撤回服务端已受理的请求。</small></div>}
            <textarea aria-label="用户指令" placeholder={view ? '输入用户指令…  Ctrl / ⌘ + Enter 发送' : '请先连接 Session'} value={message} onChange={e => setMessage(e.target.value)} disabled={!view || !addressMatches || sending || !!pending} onKeyDown={e => { if ((e.ctrlKey || e.metaKey) && e.key === 'Enter' && !pending) { e.preventDefault(); void send(); } }} /><div className="composer-footer"><span>POST /turns → 受理后通过 SSE 观察完成</span><button className="primary" onClick={() => void send()} disabled={!view || !addressMatches || sending || !!pending || !message.trim()}><Send size={15} />{sending ? '发送中…' : '发送指令'}</button></div>{receipt && <Json value={receipt} label={`最近受理回执 · ${short(receipt.id)} · ${receipt.status}`} />}{selectedDetails && <Json value={selectedDetails} label="Turn API 查询结果（非实时）" />}
          </div>
        </section>
        <aside className="inspection">
          <section className="panel tools-panel"><div className="panel-heading"><div><p className="eyebrow">TOOLS</p><h2>工具执行</h2></div><span className="count">{tools.length}</span></div><div className="tools-list">{!tools.length && <div className="small-empty"><Terminal size={22} /><p>发生工具调用时，将展示参数、等待状态与真实结果。</p></div>}{tools.map(e => <article className="tool-card" key={`${e.data.turn_id}:${e.data.tool_call_id}`}><div className="tool-title"><strong><Terminal size={15} />{String(e.data.name ?? 'tool')}</strong><Status value={e.data.status} /></div><div className="tool-meta">{short(e.data.tool_call_id)} · Turn {short(e.data.turn_id)}</div><pre className="tool-input">{typeof e.data.input === 'object' ? JSON.stringify(e.data.input, null, 2) : String(e.data.arguments ?? '参数生成中…')}</pre>{textOf(e.data.output) ? <pre className="tool-output">{textOf(e.data.output)}</pre> : <p className="tool-hint">{['waiting', 'suspended'].includes(String(e.data.status)) ? '等待执行或结果回传；此时还没有最终结果。' : '暂无工具输出'}</p>}<Json value={e} label="工具完整状态" /></article>)}</div></section>
          <section className="panel events-panel"><div className="inspection-tabs"><button className={tab === 'events' ? 'active' : ''} onClick={() => setTab('events')}><Radio size={14} />事件时间线</button><button className={tab === 'snapshot' ? 'active' : ''} onClick={() => setTab('snapshot')}>快照 / 状态</button></div>
            {tab === 'events' ? <><div className="event-filters"><input aria-label="事件过滤" placeholder="搜索事件类型或内容…" value={filter} onChange={e => setFilter(e.target.value)} /><label className="toggle"><input type="checkbox" checked={hideDeltas} onChange={e => setHideDeltas(e.target.checked)} />隐藏逐字 delta</label></div><div className="event-list">{!visibleEvents.length && <div className="small-empty"><Circle size={22} /><p>{received ? '当前筛选下没有事件。' : '时间线记录本次连接后的 SSE 事件；历史消息由快照恢复。'}</p></div>}{[...visibleEvents].reverse().map((e, i) => <details className={`event-row ${e.type.startsWith('turn.') ? 'turn-event' : ''}`} key={`${e.id ?? e.cursor}:${i}`}><summary><time>{time(e.created_at)}</time><strong>{e.type}</strong><span>{short(e.data.turn_id)}</span></summary><pre>{JSON.stringify(e, null, 2)}</pre></details>)}</div><div className="panel-foot">显示 {visibleEvents.length} / 最近 {events.length} 条；最多保留 400 条</div></> : <div className="snapshot-panel">{view ? <><label>当前已应用游标</label><div className="cursor"><code>{view.as_of}</code><CopyButton value={view.as_of} /></div><p>快照 + SSE 归并后的视图。消息按 item_id 更新，工具按 Turn + tool_call_id 归并。</p>{availablePages.length > 0 && <div className="pagination"><strong>还有历史资源未加载</strong>{availablePages.map(([kind]) => <button className="secondary" key={kind} disabled={!!paging} onClick={() => void loadPage(kind)}>{paging === kind ? '读取中…' : `加载更多 ${kind}`}</button>)}</div>}<Json value={view.session} label="Session 元数据" /><Json value={view.usage} label="用量统计" /><Json value={view.required_actions} label={`待处理动作 · ${view.required_actions.length}`} /><Json value={raw} label="最近一次 GET /snapshot 原始返回" /><Json value={view} label="当前归并视图（含 SSE）" /></> : <div className="small-empty">连接后可以查看快照。</div>}</div>}
          </section>
        </aside>
      </div>
      <footer>Session API v1 · 控制面资源继续通过终端创建<span>连接、刷新与观察不会自动发起模型调用</span></footer>
    </main>
  </div>;
}
