import { activeTurn, pendingInteractions, items } from './chat-view.mjs';

const $ = id => document.getElementById(id);
const labels = {
    RUNNING: '执行中', WAITING: '等待结果或补充', PAUSING: '正在暂停', PAUSED: '已暂停',
    CANCELLING: '正在取消', SUCCEEDED: '已完成', FAILED: '执行失败', CANCELLED: '已取消',
    QUEUED: '已排队', GENERATING_ARGUMENTS: '生成参数中', REQUESTED: '等待执行',
    SUCCESS: '已完成', ERROR: '执行失败', DENIED: '已拒绝', UNKNOWN: '结果待核实',
    SUSPENDED: '等待补充', INTERRUPTED: '已停止', completed: '已完成', generating: '生成中'
};
let session = new URLSearchParams(location.search).get('session')
    || localStorage.getItem('session-chat.selected');
let source, snapshot, sequence = 0n, eventSequence = 0n, trace = new Map();
let reloadTimer, refreshing = false, refreshAgain = false;
let connectVersion = 0, selectionVersion = 0, connected = true;
const short = value => value ? value.slice(0, 8) : '—';
const path = () => `/api/sessions/${encodeURIComponent(session)}`;
const turnPath = turnId => `${path()}/turns/${encodeURIComponent(turnId)}`;

async function request(url, body) {
    const response = await fetch(url, body === undefined ? {} : {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || data.message || `HTTP ${response.status}`);
    return data;
}
// A retry of an unacknowledged operation retains its ID and complete target/payload.
async function mutate(url, payload) {
    const key = 'session-chat.pending-command';
    let saved;
    try { saved = JSON.parse(localStorage.getItem(key)); } catch { /* No saved command. */ }
    const digest = JSON.stringify({ url, payload });
    const command = saved?.digest === digest ? saved : { digest, requestId: crypto.randomUUID() };
    localStorage.setItem(key, JSON.stringify(command));
    const result = await request(url, { ...payload, requestId: command.requestId });
    if (localStorage.getItem(key) === JSON.stringify(command)) localStorage.removeItem(key);
    return result;
}
function notice(error) { $('notice').textContent = error?.message || error || ''; }
function text(blocks) {
    return (blocks || []).map(block => block.type === 'text' ? block.text || '' : '').filter(Boolean).join('\n');
}
function bubble(role, value, className) {
    const node = document.createElement('article'); node.className = `message ${className}`;
    const label = document.createElement('div'); label.className = 'role'; label.textContent = role;
    const content = document.createElement('div'); content.className = 'text'; content.textContent = value;
    node.append(label, content); return node;
}
function renderItem(item) {
    let node;
    if (item.kind === 'tool') {
        const data = item.data;
        const args = data.arguments ? JSON.stringify(data.arguments) : data.argumentsText;
        const result = text(data.content || data.result?.output?.content);
        node = bubble(`${data.name || '工具'} · ${labels[item.status] || item.status}`,
            `参数：${args || '等待参数'}${result ? '\n结果：' + result : ''}`, 'tool');
        if (data.progress?.length) {
            const progress = document.createElement('div'); progress.className = 'tool-progress';
            progress.textContent = '执行进度\n' + text(data.progress); node.append(progress);
        }
    } else {
        node = bubble(item.status === 'completed' ? item.role : `${item.role} · ${labels[item.status]}`,
            text(item.content), item.role);
    }
    node.dataset.itemId = item.id; node.dataset.status = item.status; return node;
}
function render() {
    $('session-label').textContent = session ? `session ${session}` : '请选择或新建会话';
    $('cursor').textContent = sequence.toString();
    $('turn-count').textContent = snapshot?.turns.length || 0;
    $('counts').textContent = `${Object.keys(snapshot?.projection?.messages || {}).length} / ${Object.keys(snapshot?.projection?.tools || {}).length}`;
    const active = activeTurn(snapshot), latest = active || snapshot?.turns.at(-1);
    $('status').textContent = labels[latest?.status] || '就绪';
    $('run-label').textContent = latest ? `turn ${short(latest.turnId)}` : '';
    $('pause').hidden = !active || !['QUEUED', 'RUNNING', 'WAITING'].includes(active.status);
    $('resume').hidden = active?.status !== 'PAUSED';
    $('cancel').hidden = !active || active.status === 'CANCELLING';
    $('send').disabled = !snapshot || ($('input-mode').value !== 'submit' && !active);
    $('queue').textContent = (snapshot?.turns || []).filter(turn => turn.status === 'QUEUED')
        .map(turn => `排队任务 ${short(turn.turnId)}`).join(' · ');
    const nodes = items(snapshot).map(renderItem);
    const messages = $('messages');
    const atBottom = messages.scrollHeight - messages.scrollTop - messages.clientHeight < 80;
    const scrollTop = messages.scrollTop;
    messages.replaceChildren(...(nodes.length ? nodes : [bubble('', '发送一条消息，开始这段会话。', 'empty')]));
    messages.scrollTop = atBottom ? messages.scrollHeight : scrollTop;
    $('trace').replaceChildren(...[...trace.values()].reverse().map(event => {
        const li = document.createElement('li'), button = document.createElement('button');
        const seq = document.createElement('span'); seq.className = 'seq'; seq.textContent = event.sequence;
        const type = document.createElement('span'); type.className = 'event'; type.textContent = event.type;
        button.append(seq, type); button.title = `turn ${event.turnId || '—'} / run ${event.runId || '—'} / step ${event.stepId || '—'}`;
        button.onclick = () => { $('payload').textContent = JSON.stringify(event, null, 2); $('detail').showModal(); };
        li.append(button); return li;
    }));
    const pending = pendingInteractions(snapshot);
    for (const form of [...$('pending').children]) if (!pending.some(item => item.id === form.dataset.interactionId)) form.remove();
    for (const item of pending) {
        let form = [...$('pending').children].find(node => node.dataset.interactionId === item.id);
        if (!form) {
            form = document.createElement('form'); form.dataset.interactionId = item.id;
            const question = document.createElement('p'), input = document.createElement('input'), button = document.createElement('button');
            question.textContent = item.call?.arguments?.question || '请提供外部执行结果';
            input.required = true; input.maxLength = 8000; input.setAttribute('aria-label', question.textContent);
            button.textContent = '提交答案'; form.append(question, input, button);
            form.onsubmit = event => {
                event.preventDefault();
                action(() => mutate(`${turnPath(item.turnId)}/answers`, { interactionId: item.id, output: input.value }));
            };
            $('pending').append(form);
        }
        // Answering a paused task records the result without resuming it.
        form.querySelector('button').disabled = active?.status === 'CANCELLING';
    }
    $('task-error').textContent = active?.recovery?.message || latest?.error || '';
}
async function loadSnapshot() {
    if (!session) return;
    const version = selectionVersion;
    const data = await request(path());
    if (version !== selectionVersion || BigInt(data.sequence) < sequence) return;
    snapshot = data; sequence = BigInt(data.sequence); render();
}
function scheduleSnapshot() {
    refreshAgain = true;
    if (reloadTimer || refreshing) return;
    reloadTimer = setTimeout(async () => {
        reloadTimer = undefined; refreshing = true; refreshAgain = false;
        try { await loadSnapshot(); } catch (error) { notice(error); }
        finally { refreshing = false; if (refreshAgain) scheduleSnapshot(); }
    }, 150);
}
async function connect() {
    source?.close(); source = undefined;
    const version = ++connectVersion, selected = session;
    await loadSnapshot();
    if (!connected || selected !== session || version !== connectVersion || !snapshot) return;
    eventSequence = sequence;
    const events = new EventSource(`${path()}/stream?after=${encodeURIComponent(snapshot.cursor)}`);
    source = events;
    events.onopen = () => { if (source === events) { $('connection').textContent = '事件已连接'; scheduleSnapshot(); } };
    events.onerror = () => { if (source === events) $('connection').textContent = '连接中断，自动重连中'; };
    events.addEventListener('reset', () => { if (source === events) connect().catch(notice); });
    events.addEventListener('committed', message => {
        if (selected !== session || source !== events) return;
        try {
            const event = JSON.parse(message.data), next = BigInt(event.sequence);
            if (next <= eventSequence) return;
            eventSequence = next; trace.set(event.sequence, event); trace = new Map([...trace].slice(-80));
            scheduleSnapshot();
        } catch (error) { notice(error); }
    });
}
async function loadList() {
    const version = selectionVersion, data = await request('/api/sessions');
    if (version !== selectionVersion) return;
    $('model').textContent = data.model;
    const list = $('sessions'), scrollTop = list.scrollTop;
    list.replaceChildren(...data.sessions.map(id => {
        const option = document.createElement('option'); option.value = id; option.textContent = id; return option;
    }));
    list.value = session; list.scrollTop = scrollTop; return data.sessions;
}
async function select(id) {
    source?.close(); source = undefined; selectionVersion++; connectVersion++;
    clearTimeout(reloadTimer); reloadTimer = undefined; refreshAgain = false;
    session = id; sequence = 0n; eventSequence = 0n; snapshot = undefined; trace.clear();
    localStorage.setItem('session-chat.selected', session);
    history.replaceState(null, '', `?session=${encodeURIComponent(session)}`);
    notice(''); render(); await loadList(); await connect();
}
async function create() {
    const id = crypto.randomUUID();
    await request(`/api/sessions/${encodeURIComponent(id)}`, {}); await select(id);
}
async function action(work) {
    const version = selectionVersion;
    try { notice(''); await work(); if (version === selectionVersion) await loadSnapshot(); }
    catch (error) { if (version === selectionVersion) notice(error); }
}
$('compose').onsubmit = event => {
    event.preventDefault(); const message = $('prompt').value.trim(); if (!message) return;
    const mode = $('input-mode').value, active = activeTurn(snapshot);
    if (mode !== 'submit' && !active) return;
    const target = mode === 'submit' ? `${path()}/turns` : `${turnPath(active.turnId)}/${mode}`;
    const version = selectionVersion;
    action(async () => {
        await mutate(target, { message });
        if (version === selectionVersion && $('prompt').value.trim() === message) $('prompt').value = '';
    });
};
$('input-mode').onchange = render;
$('prompt').onkeydown = event => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
        event.preventDefault(); if (!$('send').disabled) $('compose').requestSubmit();
    }
};
$('connect').onclick = async () => {
    connected = !connected;
    $('connect').textContent = connected ? '断开事件连接' : '重新连接并补历史';
    if (connected) await connect().catch(notice);
    else {
        source?.close(); source = undefined; clearTimeout(reloadTimer); reloadTimer = undefined; refreshAgain = false;
        $('connection').textContent = '已断开，后台继续执行';
    }
};
$('new-session').onclick = () => create().catch(notice);
$('sessions').onchange = () => select($('sessions').value).catch(notice);
for (const operation of ['pause', 'resume', 'cancel']) $(operation).onclick = () => {
    const turn = activeTurn(snapshot); if (!turn) return;
    const payload = operation === 'resume' ? { expectedRevision: turn.revision } : {};
    action(() => mutate(`${turnPath(turn.turnId)}/${operation}`, payload));
};
$('close-detail').onclick = () => $('detail').close();
for (const button of document.querySelectorAll('[data-prompt]')) button.onclick = () => { $('prompt').value = button.dataset.prompt; $('prompt').focus(); };
setInterval(() => { if (connected && session && !document.hidden) scheduleSnapshot(); }, 2000);
render();
if (session) await select(session).catch(notice);
else {
    try { const ids = await loadList(); if (ids.length) await select(ids[0]); else await create(); }
    catch (error) { notice(error); }
}
