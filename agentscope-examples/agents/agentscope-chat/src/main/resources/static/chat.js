const $ = id => document.getElementById(id);
const labels = {
    starting: '准备中', running: '执行中', generating: '生成中', finishing: '生成中',
    generating_arguments: '生成参数中', requested: '等待执行', completed: '已完成',
    success: '已完成', error: '执行失败', denied: '已拒绝', suspended: '等待补充',
    queued: '已排队', interrupted: '已中断', cancelled: '已取消', failed: '执行失败'
};
let session = new URLSearchParams(location.search).get('session')
    || localStorage.getItem('session-chat.selected') || crypto.randomUUID();
let source, snapshot, cursor = 0, eventCursor = 0, trace = new Map(), pendingRun;
let sessionIds = [], reloadTimer, refreshing = false, refreshAgain = false;
let connectVersion = 0, selectionVersion = 0, connected = true;
const short = value => value ? value.slice(0, 8) : '—';
const path = () => `/api/sessions/${encodeURIComponent(session)}`;

async function request(url, body) {
    const response = await fetch(url, body === undefined ? {} : {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || data.message || `HTTP ${response.status}`);
    return data;
}
function notice(error) { $('notice').textContent = error?.message || error || ''; }
function text(blocks) {
    return (blocks || []).map(block => {
        if (block.type === 'text') return block.text || '';
        if (block.type === 'thinking') return block.thinking || '';
        if (block.type === 'tool_result') return text(block.output);
        return '';
    }).filter(Boolean).join('\n');
}
function bubble(role, value, className = role) {
    const node = document.createElement('article');
    node.className = `message ${className}`;
    const label = document.createElement('div'); label.className = 'role'; label.textContent = role;
    const content = document.createElement('div'); content.className = 'text'; content.textContent = value;
    node.append(label, content);
    return node;
}
function renderItem(item) {
    const state = labels[item.status] || item.status;
    let node;
    if (item.kind === 'tool') {
        const call = item.content.find(block => block.type === 'tool_use');
        const result = item.content.find(block => block.type === 'tool_result');
        const args = call && Object.keys(call.input || {}).length
            ? JSON.stringify(call.input) : call?.content || '';
        node = bubble(`${call?.name || result?.name || '工具'} · ${state}`,
            `参数：${args || '等待参数'}${result ? '\n结果：' + text(result.output) : ''}`, 'tool');
        if (item.progress.length) {
            const progress = document.createElement('div'); progress.className = 'tool-progress';
            progress.textContent = '执行进度\n' + text(item.progress); node.append(progress);
        }
    } else {
        node = bubble(item.status === 'completed' ? item.role : `${item.role} · ${state}`,
            text(item.content), item.role);
    }
    node.dataset.itemId = item.id;
    node.dataset.status = item.status;
    return node;
}
function render() {
    $('session-label').textContent = `session ${session}`;
    $('cursor').textContent = cursor;
    $('checkpoint').textContent = snapshot?.checkpointSeq || 0;
    $('counts').textContent = `${snapshot?.messages.length || 0} / ${snapshot?.workingMessages || 0}`;
    const latest = snapshot?.turns.at(-1);
    if (latest?.runId === pendingRun?.runId) pendingRun = undefined;
    const active = pendingRun || latest;
    const busy = ['starting', 'running'].includes(active?.status);
    $('status').textContent = labels[active?.status] || '就绪';
    $('run-label').textContent = active ? `turn ${short(active.turnId)} · run ${short(active.runId)}` : '';
    $('interrupt').hidden = !busy;
    $('resume').hidden = busy || !latest || !['interrupted', 'failed', 'suspended'].includes(latest.status)
        || snapshot.pending.length > 0;
    $('send').disabled = $('input-mode').value === 'steer' && !busy;
    $('queue').textContent = (snapshot?.tasks || []).filter(task => task.status === 'queued')
        .map(task => `排队任务 ${short(task.turnId)}`).join(' · ');
    const nodes = (snapshot?.items || []).map(renderItem);
    const messages = $('messages');
    const atBottom = messages.scrollHeight - messages.scrollTop - messages.clientHeight < 80;
    const scrollTop = messages.scrollTop;
    messages.replaceChildren(...(nodes.length ? nodes : [bubble('', '发送一条消息，开始这段会话。', 'empty')]));
    messages.scrollTop = atBottom ? messages.scrollHeight : scrollTop;
    $('trace').replaceChildren(...[...trace.values()].slice(-80).reverse().map(event => {
        const li = document.createElement('li'), button = document.createElement('button');
        const seq = document.createElement('span'); seq.className = 'seq'; seq.textContent = event.seq;
        const type = document.createElement('span'); type.className = 'event'; type.textContent = event.type;
        button.append(seq, type); button.title = `turn ${event.turnId || '—'} / run ${event.runId || '—'}`;
        button.onclick = async () => {
            try {
                const records = await request(`${path()}/events?after=${encodeURIComponent(session + ':' + (event.seq - 1))}&limit=1`);
                $('payload').textContent = JSON.stringify(records[0], null, 2); $('detail').showModal();
            } catch (error) { notice(error); }
        };
        li.append(button); return li;
    }));
    // Keep answer forms (including focus and partially typed input) during view refreshes.
    const pendingIds = (snapshot?.pending || []).map(event => event.data.requestId);
    for (const form of [...$('pending').children]) if (!pendingIds.includes(form.dataset.requestId)) form.remove();
    for (const event of snapshot?.pending || []) {
        let form = [...$('pending').children].find(node => node.dataset.requestId === event.data.requestId);
        if (!form) {
            form = document.createElement('form'); form.dataset.requestId = event.data.requestId;
            const question = document.createElement('p'), input = document.createElement('input');
            const button = document.createElement('button');
            question.textContent = event.data.call?.input?.question || '请提供外部执行结果';
            input.name = event.data.requestId; input.required = true; input.maxLength = 8000;
            input.setAttribute('aria-label', question.textContent); button.textContent = '提交并继续';
            form.append(question, input, button);
            form.onsubmit = async e => {
                e.preventDefault();
                await action(() => request(`${path()}/answers`, { request_id: input.name, output: input.value }));
            };
            $('pending').append(form);
        }
        form.querySelector('button').disabled = busy;
    }
}
async function loadSnapshot() {
    const version = selectionVersion;
    const data = await request(path());
    if (version !== selectionVersion) return;
    const seq = Number(data.cursor.split(':').at(-1));
    // A newer SSE notification does not invalidate a useful view. Only a newer applied view does.
    if (seq < cursor) return;
    snapshot = data; cursor = seq;
    for (const event of data.trace) trace.set(event.seq, event);
    trace = new Map([...trace].sort((a, b) => a[0] - b[0]).slice(-80));
    if (data.executionError) { pendingRun = undefined; notice(data.executionError); }
    render();
}
function scheduleSnapshot() {
    refreshAgain = true;
    if (reloadTimer || refreshing) return;
    reloadTimer = setTimeout(async () => {
        reloadTimer = undefined; refreshing = true; refreshAgain = false;
        try { await loadSnapshot(); } catch (error) { notice(error); }
        finally {
            refreshing = false;
            if (refreshAgain || (connected && pendingRun)) scheduleSnapshot();
        }
    }, 150);
}
async function connect() {
    source?.close(); source = undefined;
    const version = ++connectVersion, selected = session;
    await loadSnapshot();
    if (!connected || selected !== session || version !== connectVersion || !snapshot) return;
    eventCursor = cursor;
    const events = new EventSource(`${path()}/stream?after=${encodeURIComponent(snapshot.cursor)}`);
    source = events;
    events.onopen = () => {
        if (source !== events) return;
        $('connection').textContent = '事件已连接'; scheduleSnapshot();
    };
    events.onerror = () => {
        if (source === events) $('connection').textContent = '连接中断，自动重连中';
    };
    events.addEventListener('committed', message => {
        if (selected !== session || source !== events) return;
        try {
            const event = JSON.parse(message.data);
            if (event.seq <= eventCursor) return;
            eventCursor = event.seq;
            trace.set(event.seq, event); trace = new Map([...trace].slice(-80));
            scheduleSnapshot();
        } catch (error) { notice(error); }
    });
}
async function loadList() {
    const version = selectionVersion;
    const data = await request('/api/sessions');
    if (version !== selectionVersion) return;
    $('model').textContent = data.model;
    sessionIds = [...new Set([...sessionIds, ...data.sessions, session])].sort();
    const list = $('sessions'), scrollTop = list.scrollTop;
    const unchanged = [...list.options].map(option => option.value).join(',') === sessionIds.join(',');
    if (!unchanged) list.replaceChildren(...sessionIds.map(id => {
        const option = document.createElement('option'); option.value = id; option.textContent = id; return option;
    }));
    list.value = session;
    list.scrollTop = scrollTop;
}
async function select(id) {
    source?.close(); source = undefined; selectionVersion++; connectVersion++;
    clearTimeout(reloadTimer); reloadTimer = undefined; refreshAgain = false;
    session = id; cursor = 0; eventCursor = 0; snapshot = undefined; pendingRun = undefined; trace.clear();
    localStorage.setItem('session-chat.selected', session);
    history.replaceState(null, '', `?session=${encodeURIComponent(session)}`);
    notice(''); render(); await loadList(); await connect();
}
async function action(work) {
    const version = selectionVersion;
    try {
        notice(''); const started = await work();
        if (version !== selectionVersion) return;
        if (started?.runId) pendingRun = started;
        render(); scheduleSnapshot();
    } catch (error) { if (version === selectionVersion) notice(error); }
}
$('compose').onsubmit = async event => {
    event.preventDefault(); const message = $('prompt').value.trim(); if (!message) return;
    const mode = $('input-mode').value;
    if (mode !== 'submit') {
        await action(async () => { const ack = await request(`${path()}/${mode}`, { message }); $('prompt').value = ''; return ack; });
        return;
    }
    let saved;
    try { saved = JSON.parse(localStorage.getItem('session-chat.request')); } catch { /* No prior submission. */ }
    const body = saved?.session === session && saved.message === message
        ? saved : { session, request_id: crypto.randomUUID(), message };
    localStorage.setItem('session-chat.request', JSON.stringify(body));
    await action(async () => {
        const ack = await request(`${path()}/turns`, body);
        $('prompt').value = ''; localStorage.removeItem('session-chat.request'); return ack;
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
        source?.close(); source = undefined;
        clearTimeout(reloadTimer); reloadTimer = undefined; refreshAgain = false;
        $('connection').textContent = '已断开，后台继续执行';
    }
};
$('new-session').onclick = () => select(crypto.randomUUID()).catch(notice);
$('sessions').onchange = () => select($('sessions').value).catch(notice);
$('interrupt').onclick = () => action(() => request(`${path()}/interrupt`, { run_id: (pendingRun || snapshot?.turns.at(-1)).runId }));
$('resume').onclick = () => action(() => request(`${path()}/turns/${snapshot.turns.at(-1).turnId}/resume`, {}));
$('close-detail').onclick = () => $('detail').close();
for (const button of document.querySelectorAll('[data-prompt]')) button.onclick = () => { $('prompt').value = button.dataset.prompt; $('prompt').focus(); };
// Inbox acceptance can change the queue without advancing the execution-event cursor.
setInterval(() => { if (connected && !document.hidden) scheduleSnapshot(); }, 2000);
await select(session).catch(notice);
